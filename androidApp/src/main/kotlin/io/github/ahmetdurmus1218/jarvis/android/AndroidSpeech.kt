package io.github.ahmetdurmus1218.jarvis.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.annotation.RequiresApi
import io.github.ahmetdurmus1218.jarvis.ui.SpeechInput
import io.github.ahmetdurmus1218.jarvis.ui.SpeechOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

private val TURKISH: Locale = Locale.forLanguageTag("tr-TR")

/**
 * Speech-to-text with Android's ON-DEVICE recognizer only (createOnDeviceSpeechRecognizer, API 31+),
 * so audio never leaves the phone. Whisper via sherpa-onnx replaces it in v0.4.
 */
class AndroidSpeechInput(
    private val context: Context,
    private val ensureMicPermission: suspend () -> Boolean,
    private val launch: (suspend () -> Unit) -> Unit,
) : SpeechInput {
    override val engineName = "Android cihaz içi tanıma (tr-TR)"
    private var recognizer: SpeechRecognizer? = null
    /** Set while we cancel on purpose, so the resulting ERROR_CLIENT is not shown as a failure. */
    @Volatile private var cancelling = false
    /** Increments per start(); callbacks and the watchdog of an older session are ignored. */
    private var session = 0

    override fun isAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /** Must run on the main thread. */
    private fun obtainRecognizer(): SpeechRecognizer =
        recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }

    private fun dropRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    override fun start(listener: SpeechInput.Listener) {
        launch { startOnMain(listener, retryIfBusy = true) }
    }

    private suspend fun startOnMain(listener: SpeechInput.Listener, retryIfBusy: Boolean) {
        val mine = ++session
        Log.i(TAG, "start: onDeviceAvailable=${isAvailable()} sdk=${Build.VERSION.SDK_INT}")
        listener.onPartial("Mikrofon açılıyor…")
        if (!ensureMicPermission()) {
            Log.i(TAG, "mic permission denied")
            listener.onError("Mikrofon izni gerekli. Telefon Ayarları → Uygulamalar → Rick → İzinler → Mikrofon.")
            return
        }
        val pack = languagePack()
        Log.i(TAG, "turkish pack: $pack")
        if (pack == SpeechInput.LanguagePack.DOWNLOADABLE) {
            listener.onError(PACK_MISSING)
            return
        }
        // Seen on the emulator and on phones: the service exists but has no Turkish model, and it
        // disconnects (error 11) the moment listening starts. Say so instead of failing cryptically.
        if (pack == SpeechInput.LanguagePack.UNSUPPORTED) {
            listener.onError(TURKISH_UNSUPPORTED)
            return
        }
        if (mine != session) return
        val r = try {
            obtainRecognizer()
        } catch (e: Exception) {
            Log.w(TAG, "createOnDeviceSpeechRecognizer failed", e)
            listener.onError("Telefonun cihaz içi ses tanıması açılamadı: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        cancelling = false
        var ready = false
        var finished = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.i(TAG, "ready for speech")
                ready = true
                if (mine == session) listener.onPartial("Dinliyorum, konuşun…")
            }
            override fun onBeginningOfSpeech() { Log.i(TAG, "speech began") }
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Log.i(TAG, "error $error (cancelling=$cancelling)")
                finished = true
                if (mine != session) return
                when {
                    cancelling && error == SpeechRecognizer.ERROR_CLIENT -> listener.onError("")
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && retryIfBusy -> {
                        // A previous session was still closing: start over with a fresh recognizer once.
                        dropRecognizer()
                        launch {
                            kotlinx.coroutines.delay(300)
                            startOnMain(listener, retryIfBusy = false)
                        }
                    }
                    else -> {
                        if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        ) dropRecognizer()
                        listener.onError(errorText(error))
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                finished = true
                Log.i(TAG, "results: ${results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.size ?: 0} candidates")
                if (mine != session) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) listener.onError(NOTHING_HEARD) else listener.onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            r.startListening(recognizerIntent())
            Log.i(TAG, "startListening sent")
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            dropRecognizer()
            listener.onError("Ses tanıma başlatılamadı: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        // Some phones accept the request and then never answer: never leave the user waiting silently.
        launch {
            kotlinx.coroutines.delay(READY_TIMEOUT_MS)
            if (mine == session && !ready && !finished) {
                Log.w(TAG, "no onReadyForSpeech after ${READY_TIMEOUT_MS} ms")
                finished = true
                cancelling = true
                runCatching { r.cancel() }
                dropRecognizer()
                listener.onError(NOT_STARTING)
            }
        }
    }

    override fun stop() {
        launch { recognizer?.stopListening() }
    }

    override fun cancel() {
        launch {
            cancelling = true
            recognizer?.cancel()
        }
    }

    override suspend fun languagePack(): SpeechInput.LanguagePack = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33 || !isAvailable()) {
            SpeechInput.LanguagePack.UNKNOWN
        } else {
            // Some vendors never answer the support query: do not let that block the mic.
            withTimeoutOrNull(2_500) { checkSupport33() } ?: SpeechInput.LanguagePack.UNKNOWN
        }
    }

    /** Uses its own short-lived recognizer so the listening one is never disturbed. */
    @RequiresApi(33)
    private suspend fun checkSupport33(): SpeechInput.LanguagePack {
        val probe = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            ?: return SpeechInput.LanguagePack.UNKNOWN
        return try {
            suspendCancellableCoroutine { cont ->
                fun isTurkish(tag: String) = tag.lowercase(Locale.ROOT).let { it == "tr" || it.startsWith("tr-") || it.startsWith("tr_") }
                try {
                    probe.checkRecognitionSupport(recognizerIntent(), context.mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            val result = when {
                                support.installedOnDeviceLanguages.any(::isTurkish) -> SpeechInput.LanguagePack.READY
                                support.supportedOnDeviceLanguages.any(::isTurkish) -> SpeechInput.LanguagePack.DOWNLOADABLE
                                else -> SpeechInput.LanguagePack.UNSUPPORTED
                            }
                            if (cont.isActive) cont.resume(result)
                        }
                        override fun onError(error: Int) {
                            if (cont.isActive) cont.resume(SpeechInput.LanguagePack.UNKNOWN)
                        }
                    })
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(SpeechInput.LanguagePack.UNKNOWN)
                }
            }
        } finally {
            runCatching { probe.destroy() }
        }
    }

    override suspend fun downloadLanguagePack(): Boolean = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < 33) return@withContext false
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return@withContext false
        // The system service downloads its own language model; this app sends nothing.
        runCatching { r.triggerModelDownload(recognizerIntent()) }.isSuccess.also {
            kotlinx.coroutines.delay(1_000)
            runCatching { r.destroy() }
        }
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NOTHING_HEARD
        SpeechRecognizer.ERROR_AUDIO -> "Mikrofon açılamadı (başka bir uygulama kullanıyor olabilir)."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni gerekli. Telefon Ayarları → Uygulamalar → Rick → İzinler → Mikrofon."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Ses tanıma meşgul, bir saniye sonra tekrar deneyin."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Telefonun cihaz içi ses tanıması Türkçe'yi desteklemiyor. $WHISPER_HINT"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> PACK_MISSING
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Telefonun ses tanıma servisi Türkçe dinlemeyi başlatamadı. $WHISPER_HINT"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "Telefonun ses tanıması internet istedi; Rick yalnızca cihaz içi tanıma kullanır. Türkçe ses paketini indirin (Ayarlar)."
        else -> "Ses tanıma hatası (kod $code). $WHISPER_HINT"
    }

    private companion object {
        const val TAG = "JarvisMic"
        const val READY_TIMEOUT_MS = 6_000L
        const val WHISPER_HINT = "Aşağıdaki düğmeyle Rick'in kendi ses tanımasını (Whisper, 375 MB) indirin; her telefonda internetsiz çalışır."
        const val TURKISH_UNSUPPORTED = "Bu telefonun kendi ses tanıması Türkçe'yi internetsiz desteklemiyor. $WHISPER_HINT"
        const val NOT_STARTING = "Telefonun cihaz içi ses tanıma servisi cevap vermedi. $WHISPER_HINT"
        const val NOTHING_HEARD = "Bir şey duyamadım. Mikrofona dokunun ve konuşun; susunca kendisi durur."
        const val PACK_MISSING = "Telefonun kendi Türkçe ses paketi yüklü değil. $WHISPER_HINT"
    }
}

/**
 * Text-to-speech through the system engine, restricted to Turkish voices that do NOT require a
 * network connection. Piper via sherpa-onnx replaces it in v0.4.
 */
class AndroidSpeechOutput(context: Context) : SpeechOutput {
    override val engineName: String get() = "Android TTS (tr-TR, yalnızca cihaz içi ses)" + if (ready && voice == null) " — Türkçe ses bulunamadı" else ""
    @Volatile private var ready = false
    @Volatile private var voice: Voice? = null

    override val silentReason: String?
        get() = when {
            !initDone.isCompleted -> null
            !ready -> "Telefonun ses motoru açılamadı."
            voice == null -> "Telefonda internetsiz çalışan bir Türkçe ses yok."
            else -> null
        }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val ids = AtomicLong()
    private val initDone = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            voice = pickOfflineTurkishVoice()
            val v = voice
            if (v != null) tts.setVoice(v) else tts.setLanguage(TURKISH)
        }
        initDone.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
        })
    }

    private fun pickOfflineTurkishVoice(): Voice? = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == "tr" && !it.isNetworkConnectionRequired }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .maxByOrNull { it.quality }
    }.getOrNull()

    override suspend fun speak(text: String, rate: Float) {
        if (!initDone.await()) return
        // Refuse to use a network voice: if no offline Turkish voice exists, stay silent rather than leak text.
        if (voice == null) return
        tts.setSpeechRate(rate)
        val id = "u" + ids.incrementAndGet()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true") }
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, params, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return
        }
        suspendCancellableCoroutine { cont ->
            done.invokeOnCompletion { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    override fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    fun shutdown() = tts.shutdown()
}

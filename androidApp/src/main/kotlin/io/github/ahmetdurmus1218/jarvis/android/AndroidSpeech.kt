package io.github.ahmetdurmus1218.jarvis.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.github.ahmetdurmus1218.jarvis.ui.SpeechInput
import io.github.ahmetdurmus1218.jarvis.ui.SpeechOutput
import kotlinx.coroutines.CompletableDeferred
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

    override fun isAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    override fun start(listener: SpeechInput.Listener) {
        launch {
            if (!ensureMicPermission()) {
                listener.onError("Mikrofon izni gerekli")
                return@launch
            }
            recognizer?.destroy()
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) = listener.onError(errorText(error))
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isBlank()) listener.onError("Bir şey duyamadım") else listener.onFinal(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(listener::onPartial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            r.startListening(intent)
        }
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    private fun errorText(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Bir şey duyamadım"
        SpeechRecognizer.ERROR_AUDIO -> "Mikrofon hatası"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni gerekli"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Ses tanıma meşgul"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Türkçe cihaz içi ses tanıma paketi yok. Telefon Ayarları → Sistem → Diller → Ses tanıma bölümünden Türkçe'yi indirin."
        else -> "Ses tanıma hatası ($code)"
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

package io.github.ahmetdurmus1218.jarvis.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.stt.UtteranceCapture
import io.github.ahmetdurmus1218.jarvis.ui.SpeechInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Rick's own speech recognition: the microphone is recorded by this app and decoded on the phone
 * with Whisper small (sherpa-onnx). It does not depend on the phone maker's speech service and works
 * the same on every device once the three hash-verified Whisper files are installed.
 */
class WhisperSpeechInput(
    private val storage: AndroidModelStorage,
    private val registry: ModelRegistry,
    private val ensureMicPermission: suspend () -> Boolean,
) : SpeechInput {
    override val engineName = "Whisper small (Rick'in kendi ses tanıması, cihaz içi)"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    @Volatile private var stopRequested = false
    private val loadLock = Mutex()
    private var transcriber: WhisperTranscriber? = null

    private fun item(id: String): LockedItem? = registry.lock.items.firstOrNull { it.id == id }

    /** Paths of the three verified Whisper files, or null when not all are installed. */
    fun installedFiles(): Triple<String, String, String>? {
        val enc = item(ENCODER) ?: return null
        val dec = item(DECODER) ?: return null
        val tok = item(TOKENS) ?: return null
        if (!storage.isInstalled(enc) || !storage.isInstalled(dec) || !storage.isInstalled(tok)) return null
        return Triple(storage.pathOf(enc), storage.pathOf(dec), storage.pathOf(tok))
    }

    override fun isAvailable(): Boolean = installedFiles() != null

    override fun start(listener: SpeechInput.Listener) {
        if (job?.isActive == true) return
        stopRequested = false
        job = scope.launch {
            try {
                if (!ensureMicPermission()) {
                    listener.onError("Mikrofon izni gerekli. Telefon Ayarları → Uygulamalar → Rick → İzinler → Mikrofon.")
                    return@launch
                }
                val paths = installedFiles() ?: run {
                    listener.onError("Whisper dosyaları kurulu değil.")
                    return@launch
                }
                listener.onPartial("Dinliyorum, konuşun…")
                val samples = withContext(Dispatchers.IO) { record(listener) }
                listener.onLevel(0f)
                if (samples == null) {
                    listener.onError("Bir şey duyamadım. Mikrofona dokunup konuşun; susunca kendisi durur.")
                    return@launch
                }
                listener.onPartial("Anlamaya çalışıyorum…")
                val text = withContext(Dispatchers.Default) { transcribe(paths, samples) }
                Log.i(TAG, "decoded ${samples.size} samples → ${text.length} chars")
                if (text.isBlank()) listener.onError("Bir şey duyamadım.") else listener.onFinal(text)
            } catch (e: CancellationException) {
                listener.onError("")
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "whisper failed", e)
                listener.onError("Ses tanıma hatası: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Ends recording; what was said so far is still transcribed. */
    override fun stop() {
        stopRequested = true
    }

    override fun cancel() {
        job?.cancel()
    }

    override suspend fun languagePack(): SpeechInput.LanguagePack = SpeechInput.LanguagePack.READY

    /** Returns 16 kHz mono samples, or null when nothing was said. */
    @SuppressLint("MissingPermission") // checked by ensureMicPermission() right before
    private suspend fun record(listener: SpeechInput.Listener): FloatArray? {
        val minBuf = AudioRecord.getMinBufferSize(WHISPER_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, WHISPER_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, WHISPER_SAMPLE_RATE / 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("Mikrofon açılamadı (başka bir uygulama kullanıyor olabilir)")
        }
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("Mikrofon kayda başlamadı (başka bir uygulama kullanıyor olabilir)")
            val ctx = currentCoroutineContext()
            return UtteranceCapture.capture(
                // Non-blocking: a device that delivers no audio must not hang the app (stop must work).
                read = { buf, off, len -> ctx.ensureActive(); rec.read(buf, off, len, AudioRecord.READ_NON_BLOCKING) },
                isStopped = { stopRequested },
                onLevel = listener::onLevel,
                waitForData = { Thread.sleep(10) },
            ).samples
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    override fun warmUp() {
        val paths = installedFiles() ?: return
        scope.launch(Dispatchers.Default) {
            runCatching {
                loadLock.withLock {
                    if (transcriber?.paths != paths) {
                        transcriber?.close()
                        transcriber = WhisperTranscriber(paths)
                    }
                }
            }
        }
    }

    private suspend fun transcribe(paths: Triple<String, String, String>, samples: FloatArray): String {
        val t = loadLock.withLock {
            transcriber?.takeIf { it.paths == paths } ?: run {
                transcriber?.close()
                WhisperTranscriber(paths).also { transcriber = it }
            }
        }
        return t.transcribe(samples)
    }

    companion object {
        private const val TAG = "JarvisMic"
        const val ENCODER = "stt-whisper-small-encoder"
        const val DECODER = "stt-whisper-small-decoder"
        const val TOKENS = "stt-whisper-small-tokens"
        const val GROUP = "stt-whisper-small"
    }
}

/**
 * Uses Whisper when its files are installed, otherwise the phone's own on-device recognizer.
 */
class PreferWhisperSpeechInput(
    private val whisper: WhisperSpeechInput,
    private val system: AndroidSpeechInput,
) : SpeechInput {
    private val current: SpeechInput get() = if (whisper.isAvailable()) whisper else system
    private var active: SpeechInput? = null
    override val engineName: String get() = current.engineName
    override fun isAvailable(): Boolean = whisper.isAvailable() || system.isAvailable()
    override fun start(listener: SpeechInput.Listener) {
        val s = current
        active = s
        s.start(listener)
    }
    override fun stop() { (active ?: current).stop() }
    override fun cancel() { (active ?: current).cancel() }
    override val ownModelGroup: String? get() = if (whisper.isAvailable()) null else WhisperSpeechInput.GROUP
    override fun warmUp() = current.warmUp()
    override suspend fun languagePack(): SpeechInput.LanguagePack = current.languagePack()
    override suspend fun downloadLanguagePack(): Boolean = current.downloadLanguagePack()
}

const val WHISPER_SAMPLE_RATE = UtteranceCapture.SAMPLE_RATE

/** Whisper small through sherpa-onnx, fully on device. Loading takes a few seconds; keep one instance. */
class WhisperTranscriber(val paths: Triple<String, String, String>) : AutoCloseable {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = WHISPER_SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = paths.first,
                    decoder = paths.second,
                    language = "tr",
                    task = "transcribe",
                ),
                tokens = paths.third,
                numThreads = 4,
                provider = "cpu",
                modelType = "whisper",
                debug = false,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, WHISPER_SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

package io.github.ahmetdurmus1218.jarvis.desktop.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.stt.Downsampler
import io.github.ahmetdurmus1218.jarvis.core.stt.UtteranceCapture
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
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
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.TargetDataLine

/** Whisper small through sherpa-onnx's Java API, fully on this computer. Keep one instance: loading takes a few seconds. */
class JvmWhisperTranscriber(val paths: WhisperFiles) : AutoCloseable {
    private val recognizer = OfflineRecognizer(
        OfflineRecognizerConfig.builder()
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(UtteranceCapture.SAMPLE_RATE).setFeatureDim(80).build())
            .setOfflineModelConfig(
                OfflineModelConfig.builder()
                    .setWhisper(
                        OfflineWhisperModelConfig.builder()
                            .setEncoder(paths.encoder)
                            .setDecoder(paths.decoder)
                            .setLanguage("tr")
                            .setTask("transcribe")
                            .build(),
                    )
                    .setTokens(paths.tokens)
                    .setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
                    .setProvider("cpu")
                    .setModelType("whisper")
                    .setDebug(false)
                    .build(),
            )
            .setDecodingMethod("greedy_search")
            .build(),
    )

    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, UtteranceCapture.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()
}

data class WhisperFiles(val encoder: String, val decoder: String, val tokens: String)

/** An open recording line and its sample rate. */
class MicLine(val line: TargetDataLine, val rate: Int)

/**
 * The PC microphone: records 16 kHz mono with Java Sound, finds the end of speech with the shared
 * [UtteranceCapture] and decodes with Whisper on this computer. Nothing leaves the PC.
 */
class DesktopWhisperSpeechInput(
    private val storage: ModelStorage,
    private val registry: ModelRegistry,
    private val openLine: () -> MicLine = ::openMicrophone,
) : SpeechInput {
    override val engineName = "Whisper small (Rick'in kendi ses tanıması, bilgisayarda)"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var stopRequested = false
    private val loadLock = Mutex()
    private var transcriber: JvmWhisperTranscriber? = null

    private fun item(id: String): LockedItem? = registry.lock.items.firstOrNull { it.id == id }

    fun installedFiles(): WhisperFiles? {
        val enc = item(ENCODER) ?: return null
        val dec = item(DECODER) ?: return null
        val tok = item(TOKENS) ?: return null
        if (!storage.isInstalled(enc) || !storage.isInstalled(dec) || !storage.isInstalled(tok)) return null
        return WhisperFiles(storage.pathOf(enc), storage.pathOf(dec), storage.pathOf(tok))
    }

    override fun isAvailable(): Boolean = installedFiles() != null
    override val ownModelGroup: String? get() = if (isAvailable()) null else GROUP
    override suspend fun languagePack(): SpeechInput.LanguagePack = SpeechInput.LanguagePack.READY

    override fun start(listener: SpeechInput.Listener) {
        if (job?.isActive == true) return
        stopRequested = false
        job = scope.launch {
            try {
                val paths = installedFiles() ?: run {
                    listener.onError("Whisper dosyaları kurulu değil.")
                    return@launch
                }
                listener.onPartial("Dinliyorum, konuşun…")
                var peak = 0f
                val samples = withContext(Dispatchers.IO) {
                    record(onLevel = { l -> peak = maxOf(peak, l); listener.onLevel(l) })
                }
                listener.onLevel(0f)
                if (samples == null) {
                    listener.onError(if (peak == 0f) SILENT_MIC else "Bir şey duyamadım. Mikrofona tıklayıp konuşun; susunca kendisi durur.")
                    return@launch
                }
                listener.onPartial("Anlamaya çalışıyorum…")
                val text = transcribe(paths, samples)
                if (text.isBlank()) listener.onError("Bir şey duyamadım.") else listener.onFinal(text)
            } catch (e: CancellationException) {
                listener.onError("")
                throw e
            } catch (e: LineUnavailableException) {
                listener.onError(NO_MIC + " (${e.message})")
            } catch (e: Throwable) {
                listener.onError("Ses tanıma hatası: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    override fun stop() {
        stopRequested = true
    }

    override fun cancel() {
        job?.cancel()
    }

    private suspend fun record(onLevel: (Float) -> Unit): FloatArray? {
        val mic = openLine()
        val line = mic.line
        try {
            line.start()
            val ctx = currentCoroutineContext()
            val down = if (mic.rate == UtteranceCapture.SAMPLE_RATE) null else Downsampler(mic.rate)
            val bytes = ByteArray(mic.rate / 5 * 2)
            val raw = ShortArray(mic.rate / 5)
            return UtteranceCapture.capture(
                read = { buf, off, len ->
                    ctx.ensureActive()
                    // Non-blocking: only what is buffered, so the stop button always works. At most as
                    // much input as fits into [len] output samples after downsampling.
                    val maxIn = if (down == null) len else ((len - 2).coerceAtLeast(1).toLong() * mic.rate / UtteranceCapture.SAMPLE_RATE).toInt()
                    val n = minOf(maxIn, line.available() / 2, raw.size)
                    if (n <= 0) {
                        0
                    } else {
                        val got = line.read(bytes, 0, n * 2) / 2
                        for (i in 0 until got) {
                            raw[i] = ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort()
                        }
                        if (down == null) {
                            raw.copyInto(buf, off, 0, got)
                            got
                        } else {
                            val tmp = ShortArray(down.maxOutput(got))
                            val produced = down.process(raw, got, tmp)
                            val fit = minOf(produced, len)
                            tmp.copyInto(buf, off, 0, fit)
                            fit
                        }
                    }
                },
                isStopped = { stopRequested },
                onLevel = onLevel,
                waitForData = { Thread.sleep(10) },
            ).samples
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
        }
    }

    private suspend fun transcribe(paths: WhisperFiles, samples: FloatArray): String {
        val t = loadLock.withLock {
            transcriber?.takeIf { it.paths == paths } ?: run {
                transcriber?.close()
                JvmWhisperTranscriber(paths).also { transcriber = it }
            }
        }
        return withContext(Dispatchers.Default) { t.transcribe(samples) }
    }

    companion object {
        const val ENCODER = "stt-whisper-small-encoder"
        const val DECODER = "stt-whisper-small-decoder"
        const val TOKENS = "stt-whisper-small-tokens"
        const val GROUP = "stt-whisper-small"

        private const val NO_MIC = "Mikrofon bulunamadı ya da başka bir uygulama kullanıyor."
        private const val SILENT_MIC = "Mikrofondan hiç ses gelmiyor. Windows Ayarlar → Gizlilik ve güvenlik → Mikrofon → " +
            "\"Masaüstü uygulamalarının mikrofonunuza erişmesine izin verin\" açık olmalı; doğru mikrofonun seçili olduğunu da kontrol edin."

        /**
         * 16 kHz mono 16-bit is what Whisper expects; most Windows sound cards offer it directly.
         * Otherwise 48 or 44.1 kHz is recorded and downsampled.
         */
        fun openMicrophone(): MicLine {
            var last: Exception? = null
            for (rate in listOf(UtteranceCapture.SAMPLE_RATE, 48_000, 44_100)) {
                val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
                val info = DataLine.Info(TargetDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(info)) continue
                try {
                    val line = AudioSystem.getLine(info) as TargetDataLine
                    line.open(format, rate) // half a second of buffer (2 bytes per sample)
                    return MicLine(line, rate)
                } catch (e: LineUnavailableException) {
                    last = e
                } catch (e: IllegalArgumentException) {
                    last = e
                }
            }
            throw LineUnavailableException(last?.message ?: "kayıt aygıtı yok")
        }
    }
}

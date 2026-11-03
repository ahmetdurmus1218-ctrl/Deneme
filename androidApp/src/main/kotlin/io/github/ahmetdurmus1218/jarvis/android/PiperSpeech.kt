package io.github.ahmetdurmus1218.jarvis.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.tts.TurkishPhonemizer
import io.github.ahmetdurmus1218.jarvis.ui.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/** The model file and token table taken out of the verified Piper archive. */
data class PiperFiles(val model: String, val tokens: String)

/**
 * Unpacks only the voice model (.onnx) and tokens.txt from the hash-verified Piper .tar.bz2 into a
 * folder next to it. Entry names are reduced to their file name (no path from the archive is ever
 * used), and a marker with the archive's SHA-256 makes it happen once per verified archive.
 */
object PiperArchive {
    fun unpacked(archive: File, sha256: String): PiperFiles? {
        val dir = File(archive.parentFile, archive.name + ".d")
        val model = File(dir, "voice.onnx")
        val tokens = File(dir, "tokens.txt")
        val marker = File(dir, "unpacked.sha256")
        return if (marker.isFile && marker.readText() == sha256 && model.isFile && tokens.isFile) PiperFiles(model.path, tokens.path) else null
    }

    fun unpack(archive: File, sha256: String): PiperFiles {
        unpacked(archive, sha256)?.let { return it }
        val dir = File(archive.parentFile, archive.name + ".d").apply { deleteRecursively(); mkdirs() }
        var gotModel = false
        var gotTokens = false
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                if (!e.isFile) continue
                val name = e.name.substringAfterLast('/')
                val target = when {
                    name.endsWith(".onnx") && !gotModel -> File(dir, "voice.onnx").also { gotModel = true }
                    name == "tokens.txt" -> File(dir, "tokens.txt").also { gotTokens = true }
                    else -> continue
                }
                if (e.size > MAX_ENTRY) error("Ses dosyası beklenenden büyük")
                target.outputStream().use { tar.copyTo(it) }
            }
        }
        check(gotModel && gotTokens) { "Ses arşivinde model ya da tokens.txt yok" }
        File(dir, "unpacked.sha256").writeText(sha256)
        return PiperFiles(File(dir, "voice.onnx").path, File(dir, "tokens.txt").path)
    }

    private const val MAX_ENTRY = 300L * 1024 * 1024
}

/**
 * Rick's own Turkish voice: Piper (tr_TR-dfki-medium) through sherpa-onnx, phonemes from
 * [TurkishPhonemizer]. The next sentence is synthesized while the current one plays. Until the
 * voice is installed (or if it fails to load) the phone's offline Turkish voice speaks instead.
 */
class PiperSpeechOutput(
    private val storage: AndroidModelStorage,
    private val registry: ModelRegistry,
    private val fallback: SpeechOutput,
) : SpeechOutput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadLock = Mutex()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var failed = false
    @Volatile private var track: AudioTrack? = null
    @Volatile private var generation = 0

    private fun item(): LockedItem? = registry.lock.items.firstOrNull { it.id == VOICE }?.takeIf { storage.isInstalled(it) }

    fun installed(): Boolean = item() != null

    override val engineName: String
        get() = when {
            tts != null -> "Piper tr_TR-dfki-medium (Rick'in kendi sesi, cihaz içi)"
            installed() && !failed -> "Piper (hazırlanıyor) · şimdilik: " + fallback.engineName
            else -> fallback.engineName
        }

    override val silentReason: String?
        get() = when {
            tts != null -> null
            installed() && !failed -> null // still loading: speak() waits for it
            else -> fallback.silentReason
        }

    override val ownModelGroup: String? get() = if (installed()) null else GROUP

    /** Loads the voice in the background so the first answer does not wait for unpacking. */
    fun warmUp() {
        if (tts == null && !failed && installed()) scope.launch { load() }
    }

    private suspend fun load(): OfflineTts? = loadLock.withLock {
        tts?.let { return it }
        if (failed) return null
        val it = item() ?: return null
        try {
            val files = withContext(Dispatchers.IO) { PiperArchive.unpack(File(storage.pathOf(it)), it.sha256) }
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = files.model, tokens = files.tokens),
                    numThreads = 2,
                ),
            )
            OfflineTts(null, cfg).also { tts = it }
        } catch (e: Throwable) {
            Log.w(TAG, "Piper voice failed to load", e)
            failed = true
            null
        }
    }

    /** Synthesizes [text] without playing it (samples, sample rate); null when the voice is not available. */
    suspend fun render(text: String, rate: Float = 1f): Pair<FloatArray, Int>? {
        val engine = load() ?: return null
        val parts = TurkishPhonemizer.codepoints(text).map { row ->
            withContext(Dispatchers.Default) { engine.generateWithConfig("x", GenerationConfig(speed = rate, phonemeCodepoints = arrayOf(row))).samples }
        }
        val all = FloatArray(parts.sumOf { it.size })
        var p = 0
        for (a in parts) { a.copyInto(all, p); p += a.size }
        return all to engine.sampleRate()
    }

    override suspend fun speak(text: String, rate: Float) {
        // Installed but still unpacking (first answer after start): wait for it rather than fall back to
        // a system voice that many phones do not have offline, which would leave the answer silent.
        val engine = if (installed()) load() else null
        if (engine == null) return fallback.speak(text, rate)
        val rows = TurkishPhonemizer.codepoints(text)
        if (rows.isEmpty()) return
        val gen = ++generation
        val sampleRate = engine.sampleRate()
        withContext(Dispatchers.IO) {
            val out = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) * 4)
                .build()
            track = out
            try {
                out.play()
                fun synth(i: Int) = async(Dispatchers.Default) {
                    engine.generateWithConfig("x", GenerationConfig(speed = rate, phonemeCodepoints = arrayOf(rows[i]))).samples
                }
                var next = synth(0)
                var written = 0L
                for (i in rows.indices) {
                    val samples = next.await()
                    if (i + 1 < rows.size) next = synth(i + 1)
                    if (gen != generation) break
                    var off = 0
                    while (off < samples.size && gen == generation) {
                        val n = out.write(samples, off, minOf(4096, samples.size - off), AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        off += n
                        written += n
                    }
                }
                // The tail is still in the track's buffer: wait until it has actually been heard.
                val deadline = System.currentTimeMillis() + 2_000 + written * 1000 / sampleRate
                while (gen == generation && (out.playbackHeadPosition.toLong() and 0xffffffffL) < written &&
                    System.currentTimeMillis() < deadline
                ) Thread.sleep(15)
            } finally {
                track = null
                runCatching { out.pause(); out.flush() }
                out.release()
            }
        }
    }

    override fun stop() {
        generation++
        runCatching { track?.pause(); track?.flush() }
        fallback.stop()
    }

    companion object {
        private const val TAG = "JarvisPiper"
        const val VOICE = "tts-piper-tr-dfki-medium"
        const val GROUP = "tts-piper-tr"
    }
}

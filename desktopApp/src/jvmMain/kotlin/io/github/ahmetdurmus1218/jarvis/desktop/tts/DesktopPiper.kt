package io.github.ahmetdurmus1218.jarvis.desktop.tts

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.tts.TurkishPhonemizer
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

data class PiperFiles(val model: String, val tokens: String)

/**
 * Takes only the voice model (.onnx) and tokens.txt out of the hash-verified Piper .tar.bz2. Entry
 * paths are reduced to their file name, so nothing in the archive can write outside the folder.
 */
object PiperArchive {
    private fun dirOf(archive: File) = File(archive.parentFile, archive.name + ".d")

    fun unpacked(archive: File, sha256: String): PiperFiles? {
        val dir = dirOf(archive)
        val model = File(dir, "voice.onnx")
        val tokens = File(dir, "tokens.txt")
        val marker = File(dir, "unpacked.sha256")
        return if (marker.isFile && marker.readText() == sha256 && model.isFile && tokens.isFile) PiperFiles(model.path, tokens.path) else null
    }

    fun unpack(archive: File, sha256: String): PiperFiles {
        unpacked(archive, sha256)?.let { return it }
        val dir = dirOf(archive).apply { deleteRecursively(); mkdirs() }
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
                require(e.size <= MAX_ENTRY) { "Ses dosyası beklenenden büyük" }
                target.outputStream().use { tar.copyTo(it) }
            }
        }
        check(gotModel && gotTokens) { "Ses arşivinde model ya da tokens.txt yok" }
        File(dir, "unpacked.sha256").writeText(sha256)
        return PiperFiles(File(dir, "voice.onnx").path, File(dir, "tokens.txt").path)
    }

    private const val MAX_ENTRY = 300L * 1024 * 1024
}

/** Piper through sherpa-onnx, fed with phonemes from [TurkishPhonemizer]. */
class PiperSynth(files: PiperFiles, threads: Int = 2) : AutoCloseable {
    private val tts = OfflineTts(
        OfflineTtsConfig.builder()
            .setModel(
                OfflineTtsModelConfig.builder()
                    .setVits(OfflineTtsVitsModelConfig.builder().setModel(files.model).setTokens(files.tokens).build())
                    .setNumThreads(threads)
                    .build(),
            )
            .build(),
    )
    val sampleRate: Int get() = tts.sampleRate

    /** One sentence of phonemes (code points) → samples. */
    fun sentence(codepoints: IntArray, speed: Float): FloatArray {
        val cfg = GenerationConfig().apply {
            this.speed = speed
            phonemeCodepoints = arrayOf(codepoints)
        }
        return tts.generateWithConfig("x", cfg).samples
    }

    override fun close() = tts.release()
}

/** Where synthesized audio goes: the sound card, or a recorder in tests. */
interface AudioSink : AutoCloseable {
    /** Blocks until the samples are queued. */
    fun write(samples: FloatArray, off: Int, len: Int)
    /** Blocks until everything written has been played. */
    fun drain()
    fun abort()
}

class SpeakerSink(rate: Int) : AudioSink {
    private val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply { open(format, rate); start() }

    override fun write(samples: FloatArray, off: Int, len: Int) {
        val buf = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in off until off + len) buf.putShort((samples[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        line.write(buf.array(), 0, buf.position())
    }
    override fun drain() = line.drain()
    override fun abort() { line.stop(); line.flush() }
    override fun close() = line.close()
}

/**
 * Jarvis's own Turkish voice on the PC (the same Piper files as the phone). The next sentence is
 * synthesized while the current one plays. Until the voice is installed, [fallback] speaks.
 */
class DesktopPiperSpeech(
    private val storage: ModelStorage,
    private val registry: ModelRegistry,
    private val fallback: SpeechOutput?,
    private val openSink: (Int) -> AudioSink = ::SpeakerSink,
) : SpeechOutput {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadLock = Mutex()
    @Volatile private var synth: PiperSynth? = null
    @Volatile private var failed: String? = null
    @Volatile private var sink: AudioSink? = null
    @Volatile private var generation = 0

    private fun item(): LockedItem? = registry.lock.items.firstOrNull { it.id == VOICE }?.takeIf { storage.isInstalled(it) }

    fun installed() = item() != null

    override val engineName: String
        get() = when {
            synth != null -> "Piper tr_TR-dfki-medium (Jarvis'in kendi sesi, bilgisayarda)"
            failed != null -> "Piper açılamadı ($failed)" + (fallback?.let { " · " + it.engineName } ?: "")
            installed() -> "Piper (hazırlanıyor)"
            else -> fallback?.engineName ?: "Ses yok: Modeller ekranından Piper Türkçe sesi indirin"
        }

    fun warmUp() {
        if (synth == null && failed == null && installed()) scope.launch { load() }
    }

    suspend fun load(): PiperSynth? = loadLock.withLock {
        synth?.let { return it }
        if (failed != null) return null
        val it = item() ?: return null
        try {
            val files = withContext(Dispatchers.IO) { PiperArchive.unpack(File(storage.pathOf(it)), it.sha256) }
            PiperSynth(files).also { synth = it }
        } catch (e: Throwable) {
            failed = e.message ?: e.javaClass.simpleName
            null
        }
    }

    override suspend fun speak(text: String, rate: Float) {
        if (!installed()) return fallback?.speak(text, rate) ?: Unit
        val engine = load() ?: return fallback?.speak(text, rate) ?: Unit
        val rows = TurkishPhonemizer.codepoints(text)
        if (rows.isEmpty()) return
        val gen = ++generation
        withContext(Dispatchers.IO) {
            val out = openSink(engine.sampleRate)
            sink = out
            try {
                fun next(i: Int) = async(Dispatchers.Default) { engine.sentence(rows[i], rate) }
                var pending = next(0)
                for (i in rows.indices) {
                    val samples = pending.await()
                    if (i + 1 < rows.size) pending = next(i + 1)
                    if (gen != generation) break
                    var off = 0
                    while (off < samples.size && gen == generation) {
                        val n = minOf(2048, samples.size - off)
                        out.write(samples, off, n)
                        off += n
                    }
                }
                if (gen == generation) out.drain()
            } finally {
                sink = null
                out.close()
            }
        }
    }

    override fun stop() {
        generation++
        runCatching { sink?.abort() }
        fallback?.stop()
    }

    companion object {
        const val VOICE = "tts-piper-tr-dfki-medium"
    }
}

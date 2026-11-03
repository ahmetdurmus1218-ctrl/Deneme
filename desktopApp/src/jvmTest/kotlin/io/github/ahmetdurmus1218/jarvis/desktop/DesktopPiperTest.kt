package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.desktop.stt.JvmWhisperTranscriber
import io.github.ahmetdurmus1218.jarvis.desktop.stt.WhisperFiles
import io.github.ahmetdurmus1218.jarvis.desktop.tts.AudioSink
import io.github.ahmetdurmus1218.jarvis.desktop.tts.DesktopPiperSpeech
import io.github.ahmetdurmus1218.jarvis.desktop.tts.PiperArchive
import io.github.ahmetdurmus1218.jarvis.ui.ImportOutcome
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Piper on the PC: archive handling always; with the real voice (JARVIS_PIPER_ARCHIVE) and Whisper
 * (JARVIS_WHISPER_DIR) installed, Jarvis speaks a sentence into a recorder and Whisper must hear it.
 */
class DesktopPiperTest {
    private fun tarBz2(file: File, entries: Map<String, String>) {
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            for ((name, body) in entries) {
                val bytes = body.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    @Test
    fun onlyTheModelAndTokensLeaveTheArchive() {
        val root = Files.createTempDirectory("piper").toFile()
        val models = File(root, "models").apply { mkdirs() }
        val archive = File(models, "voice.tar.bz2")
        tarBz2(
            archive,
            mapOf(
                "vits/tr.onnx" to "MODEL",
                "vits/tokens.txt" to "a 1\n",
                "../../evil.txt" to "no",
                "vits/espeak-ng-data/tr_dict" to "skip",
                "vits/other.onnx" to "SECOND",
            ),
        )
        val files = PiperArchive.unpack(archive, "abc")
        assertEquals("MODEL", File(files.model).readText(), "the first model in the archive")
        assertEquals("a 1\n", File(files.tokens).readText())
        assertFalse(File(root, "evil.txt").exists())
        assertFalse(File(models, "evil.txt").exists())
        assertEquals(setOf("voice.onnx", "tokens.txt", "unpacked.sha256"), File(files.model).parentFile.list()!!.toSet())
        // Unpacked once per verified archive; a different hash unpacks again.
        assertEquals(files, PiperArchive.unpacked(archive, "abc"))
        assertEquals(null, PiperArchive.unpacked(archive, "other"))

        val broken = File(models, "broken.tar.bz2")
        tarBz2(broken, mapOf("vits/tr.onnx" to "MODEL"))
        assertFailsWith<IllegalStateException> { PiperArchive.unpack(broken, "x") }
    }

    private class OneFile(val path: File?) : ModelStorage {
        override fun isInstalled(item: LockedItem) = path != null && item.id == DesktopPiperSpeech.VOICE && path.isFile
        override fun pathOf(item: LockedItem) = path!!.absolutePath
        override fun commit(item: LockedItem, tempPath: String) {}
        override fun delete(item: LockedItem) {}
        override fun deleteTemp(tempPath: String) {}
        override fun importedUnverifiedFiles() = emptyList<String>()
        override fun keepUnverified(tempPath: String, fileName: String) {}
        override suspend fun pickAndImport(): ImportOutcome = ImportOutcome.Cancelled
    }

    private class Recorder : AudioSink {
        val samples = ArrayList<Float>()
        var drained = false
        override fun write(samples: FloatArray, off: Int, len: Int) { for (i in off until off + len) this.samples += samples[i] }
        override fun drain() { drained = true }
        override fun abort() {}
        override fun close() {}
    }

    @Test
    fun withoutTheVoiceTheFallbackSpeaks() = runBlocking {
        val spoken = mutableListOf<String>()
        val fallback = object : io.github.ahmetdurmus1218.jarvis.ui.SpeechOutput {
            override val engineName = "yedek"
            override suspend fun speak(text: String, rate: Float) { spoken += text }
            override fun stop() {}
        }
        val tts = DesktopPiperSpeech(OneFile(null), ModelRegistry.bundled, fallback) { error("no sound card in this test") }
        tts.speak("Merhaba.", 1f)
        assertEquals(listOf("Merhaba."), spoken)
        assertEquals("yedek", tts.engineName)
    }

    @Test
    fun jarvisSaysItAndWhisperHearsIt() = runBlocking {
        val archive = System.getenv("JARVIS_PIPER_ARCHIVE")?.let(::File)
        val whisper = System.getenv("JARVIS_WHISPER_DIR")?.let(::File)
        assumeTrue("Piper voice not available", archive?.isFile == true)
        assumeTrue("Whisper not available", whisper != null && File(whisper, "small-encoder.int8.onnx").isFile)
        // Work on a copy: unpacking writes next to the archive.
        val copy = File(Files.createTempDirectory("piper-real").toFile(), archive!!.name).also { archive.copyTo(it) }
        val rec = Recorder()
        var rate = 0
        val tts = DesktopPiperSpeech(OneFile(copy), ModelRegistry.bundled, null) { r -> rate = r; rec }
        val t0 = System.nanoTime()
        tts.speak("Merhaba efendim, Jarvis hazır. Saat on dört otuz beş.", 1f)
        println("Piper: ${rec.samples.size} samples at $rate Hz in ${(System.nanoTime() - t0) / 1_000_000} ms; engine: ${tts.engineName}")
        assertTrue(rec.drained)
        assertTrue(rec.samples.size > rate * 2, "at least two seconds of speech")

        // 22.05 kHz → 16 kHz for Whisper (linear interpolation is enough for a check).
        val n = (rec.samples.size.toLong() * 16000 / rate).toInt()
        val pcm = FloatArray(n) { i ->
            val x = i * (rec.samples.size - 1).toDouble() / (n - 1)
            val a = x.toInt()
            val f = (x - a).toFloat()
            rec.samples[a] * (1 - f) + rec.samples[minOf(a + 1, rec.samples.lastIndex)] * f
        }
        val heard = JvmWhisperTranscriber(
            WhisperFiles(
                File(whisper, "small-encoder.int8.onnx").path,
                File(whisper, "small-decoder.int8.onnx").path,
                File(whisper, "small-tokens.txt").path,
            ),
        ).use { it.transcribe(pcm) }
        println("Whisper heard: $heard")
        val norm = Turkish.normalize(heard)
        assertTrue("merhaba" in norm, heard)
        assertTrue("hazir" in norm, heard)
    }
}

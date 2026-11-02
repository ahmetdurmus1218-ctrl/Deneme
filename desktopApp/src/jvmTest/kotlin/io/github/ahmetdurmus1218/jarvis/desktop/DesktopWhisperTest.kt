package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.desktop.stt.DesktopWhisperSpeechInput
import io.github.ahmetdurmus1218.jarvis.desktop.stt.MicLine
import io.github.ahmetdurmus1218.jarvis.ui.ImportOutcome
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
import io.github.ahmetdurmus1218.jarvis.ui.SpeechInput
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.TargetDataLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The PC microphone path end to end, without a real microphone: a fake recording line plays a
 * Turkish recording (at 48 kHz, to also exercise the downsampling path) into the same capture,
 * end-of-speech detection and Whisper decoder the app uses. Needs the three Whisper files in
 * JARVIS_WHISPER_DIR (CI downloads and hash-checks them); skipped otherwise.
 */
class DesktopWhisperTest {
    private val dir = System.getenv("JARVIS_WHISPER_DIR")?.let(::File)

    private class DirStorage(val dir: File?) : ModelStorage {
        override fun isInstalled(item: LockedItem) = dir != null && File(dir, item.fileName).isFile
        override fun pathOf(item: LockedItem) = File(dir, item.fileName).absolutePath
        override fun commit(item: LockedItem, tempPath: String) {}
        override fun delete(item: LockedItem) {}
        override fun deleteTemp(tempPath: String) {}
        override fun importedUnverifiedFiles() = emptyList<String>()
        override fun keepUnverified(tempPath: String, fileName: String) {}
        override suspend fun pickAndImport(): ImportOutcome = ImportOutcome.Cancelled
    }

    private fun wavSamples(): ShortArray {
        val bytes = javaClass.getResourceAsStream("/tr-merhaba.wav")!!.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val len = b.getInt(p + 4)
            if (id == "data") {
                val out = ShortArray(len / 2)
                b.position(p + 8)
                b.asShortBuffer().get(out)
                return out
            }
            p += 8 + len + (len and 1)
        }
        error("no data chunk")
    }

    /** A recording line that delivers [pcm] (little-endian 16-bit) in small pieces, like a sound card. */
    private fun fakeLine(pcm: ShortArray): TargetDataLine {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(pcm) }.array()
        var pos = 0
        var calls = 0
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(TargetDataLine::class.java)) { _, m, args ->
            when (m.name) {
                // Odd amounts, and sometimes nothing, as a real device does.
                "available" -> if (calls++ % 3 == 0) 0 else minOf(data.size - pos, 1234)
                "read" -> {
                    val buf = args[0] as ByteArray
                    val off = args[1] as Int
                    val n = minOf(args[2] as Int, data.size - pos)
                    System.arraycopy(data, pos, buf, off, n)
                    pos += n
                    n
                }
                "isOpen", "isActive", "isRunning" -> true
                "hashCode" -> 1
                "equals" -> false
                "toString" -> "fake line"
                else -> null
            }
        } as TargetDataLine
    }

    private fun upsample3(x: ShortArray) = ShortArray(x.size * 3) { i -> x[i / 3] }

    @Test
    fun microphonePathDecodesTurkishAt48kHz() = runBlocking {
        assumeTrue("JARVIS_WHISPER_DIR not set: Whisper files not available", dir?.isDirectory == true)
        // 4.9 s of speech followed by 6 s of near-silence: the end-of-speech detector must stop it.
        val pcm = upsample3(wavSamples() + ShortArray(16_000 * 6) { ((it % 7) - 3).toShort() })
        val stt = DesktopWhisperSpeechInput(DirStorage(dir), ModelRegistry.bundled, openLine = { MicLine(fakeLine(pcm), 48_000) })
        assertTrue(stt.isAvailable())
        val result = CompletableDeferred<String>()
        val partials = mutableListOf<String>()
        stt.start(object : SpeechInput.Listener {
            override fun onPartial(text: String) { partials += text }
            override fun onFinal(text: String) { result.complete(text) }
            override fun onError(message: String) { result.completeExceptionally(AssertionError("error: $message")) }
        })
        val text = withTimeout(120_000) { result.await() }
        println("Whisper (PC path): '$text'; steps: $partials")
        val n = Turkish.normalize(text)
        assertTrue("merhaba" in n && "hava" in n && "nasil" in n, text)
        assertEquals(listOf("Dinliyorum, konuşun…", "Anlamaya çalışıyorum…"), partials)
    }

    @Test
    fun missingFilesOfferTheDownload() {
        val stt = DesktopWhisperSpeechInput(DirStorage(null), ModelRegistry.bundled)
        assertEquals(false, stt.isAvailable())
        assertEquals("stt-whisper-small", stt.ownModelGroup)
    }
}

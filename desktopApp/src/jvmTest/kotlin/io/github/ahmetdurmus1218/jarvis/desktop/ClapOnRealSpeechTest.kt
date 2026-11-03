package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.stt.ClapDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/** A real Turkish recording (speech with pauses) must never wake Rick like a double clap. */
class ClapOnRealSpeechTest {
    @Test
    fun speechIsNotAClap() {
        val bytes = javaClass.getResourceAsStream("/tr-merhaba.wav")!!.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        var pcm = ShortArray(0)
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val len = b.getInt(p + 4)
            if (id == "data") { pcm = ShortArray(len / 2); b.position(p + 8); b.asShortBuffer().get(pcm); break }
            p += 8 + len + (len and 1)
        }
        val d = ClapDetector()
        var wakes = 0
        // Played three times in a row, louder each time.
        for (gain in listOf(1f, 2f, 4f)) {
            val f = FloatArray(pcm.size) { (pcm[it] / 32768f * gain).coerceIn(-1f, 1f) }
            var i = 0
            while (i < f.size) { val n = minOf(1600, f.size - i); if (d.feed(f.copyOfRange(i, i + n))) wakes++; i += n }
        }
        assertEquals(0, wakes)
    }
}

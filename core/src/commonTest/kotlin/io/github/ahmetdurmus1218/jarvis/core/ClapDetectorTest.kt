package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.stt.ClapDetector
import io.github.ahmetdurmus1218.jarvis.core.stt.WakePhrase
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClapDetectorTest {
    private val sr = 16_000
    private val rnd = Random(3)

    /** Room noise with events placed at given times (ms). */
    private fun audio(totalMs: Int, vararg events: Pair<Int, (Int) -> Float>): FloatArray {
        val out = FloatArray(totalMs * sr / 1000) { (rnd.nextFloat() - 0.5f) * 0.01f }
        for ((at, ev) in events) {
            val start = at * sr / 1000
            var i = 0
            while (start + i < out.size) {
                val v = ev(i)
                if (v.isNaN()) break
                out[start + i] += v
                i++
            }
        }
        return out
    }

    /** A clap: instant attack, ~15 ms decay of broadband noise, over in ~70 ms. */
    private val clap: (Int) -> Float = { i -> if (i > sr * 70 / 1000) Float.NaN else (rnd.nextFloat() - 0.5f) * 1.2f * exp(-i / (sr * 0.015f)) }

    /** A spoken vowel: 40 ms rise, 350 ms long. */
    private val vowel: (Int) -> Float = { i ->
        if (i > sr * 350 / 1000) Float.NaN else (minOf(1f, i / (sr * 0.04f)) * 0.35f * sin(2 * PI * 180 * i / sr).toFloat())
    }

    /** A door slam: loud, but rings for 400 ms. */
    private val slam: (Int) -> Float = { i -> if (i > sr * 400 / 1000) Float.NaN else (rnd.nextFloat() - 0.5f) * 1.2f * exp(-i / (sr * 0.2f)) }

    private fun feedChunks(d: ClapDetector, a: FloatArray): Int {
        var wakes = 0
        var p = 0
        while (p < a.size) {
            val n = minOf(1600, a.size - p)
            if (d.feed(a.copyOfRange(p, p + n))) wakes++
            p += n
        }
        return wakes
    }

    @Test
    fun twoClapsWake() {
        assertEquals(1, feedChunks(ClapDetector(), audio(3_000, 1_000 to clap, 1_400 to clap)))
    }

    @Test
    fun oneClapSpeechOrASlamDoNot() {
        assertEquals(0, feedChunks(ClapDetector(), audio(3_000, 1_000 to clap)))
        assertEquals(0, feedChunks(ClapDetector(), audio(3_000, 500 to vowel, 1_000 to vowel, 1_400 to vowel)))
        assertEquals(0, feedChunks(ClapDetector(), audio(3_000, 1_000 to slam, 1_500 to slam)))
        assertEquals(0, feedChunks(ClapDetector(), audio(4_000, 1_000 to clap, 2_300 to clap)), "too far apart")
    }

    @Test
    fun aRecordedRhythmIsRequiredWhenSet() {
        val d = ClapDetector().apply { rhythm = ClapDetector.rhythmOf(listOf(0L, 300L, 900L)) }
        assertEquals(0, feedChunks(d, audio(3_000, 500 to clap, 900 to clap)), "a plain double clap is not the rhythm")
        val d2 = ClapDetector().apply { rhythm = listOf(300L, 600L) }
        assertEquals(1, feedChunks(d2, audio(3_000, 500 to clap, 820 to clap, 1_400 to clap)), "within ±20%")
    }

    @Test
    fun wakePhraseAndGreeting() {
        assertTrue(WakePhrase.matches("Uyan Jarvis"))
        assertTrue(WakePhrase.matches("uyan cervis!"))
        assertFalse(WakePhrase.matches("hava nasıl"))
        assertEquals("Günaydın efendim.", WakePhrase.greeting(8))
        assertEquals("İyi akşamlar efendim.", WakePhrase.greeting(20))
    }
}

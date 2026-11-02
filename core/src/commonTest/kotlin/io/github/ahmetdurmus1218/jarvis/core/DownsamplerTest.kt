package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.stt.Downsampler
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownsamplerTest {
    private fun tone(rate: Int, seconds: Double, hz: Double = 300.0) =
        ShortArray((rate * seconds).toInt()) { i -> (sin(2 * PI * hz * i / rate) * 10_000).toInt().toShort() }

    private fun run(d: Downsampler, input: ShortArray, piece: Int): ShortArray {
        val out = ShortArray(d.maxOutput(input.size))
        var w = 0
        var i = 0
        while (i < input.size) {
            val n = minOf(piece, input.size - i)
            w += d.process(input.copyOfRange(i, i + n), n, out, w)
            i += n
        }
        return out.copyOf(w)
    }

    @Test
    fun oneSecondStaysOneSecond() {
        for (rate in listOf(48_000, 44_100, 32_000, 16_000)) {
            val out = run(Downsampler(rate), tone(rate, 1.0), piece = 1_000)
            assertEquals(16_000, out.size, "from $rate Hz")
        }
    }

    @Test
    fun pieceSizeDoesNotMatter() {
        val input = tone(44_100, 0.5)
        val a = run(Downsampler(44_100), input, piece = 44_100)
        val b = run(Downsampler(44_100), input, piece = 337)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun speechBandToneSurvives() {
        // A 300 Hz tone resampled from 48 kHz must still be that tone at 16 kHz.
        val out = run(Downsampler(48_000), tone(48_000, 0.1), piece = 480)
        val expected = tone(16_000, 0.1)
        val worst = out.indices.drop(2).maxOf { abs(out[it] - expected[it]) }
        assertTrue(worst < 600, "max deviation $worst of 10000")
    }
}

package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.stt.SpeechEndpointer.Decision
import io.github.ahmetdurmus1218.jarvis.core.stt.UtteranceCapture
import io.github.ahmetdurmus1218.jarvis.core.stt.UtteranceCapture.END_OF_AUDIO
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtteranceCaptureTest {
    private val rate = UtteranceCapture.SAMPLE_RATE

    /** quiet ms, then a "voice" (220 Hz tone, amplitude 0.3) for voice ms, then quiet ms. */
    private fun recording(before: Int, voice: Int, after: Int): ShortArray {
        val total = (before + voice + after) * rate / 1000
        var seed = 7
        return ShortArray(total) { i ->
            seed = seed * 1103515245 + 12345
            val noise = ((seed ushr 16) % 200 - 100) // ±100 of 32768 ≈ 0.003
            val inVoice = i >= before * rate / 1000 && i < (before + voice) * rate / 1000
            val tone = if (inVoice) (sin(2 * PI * 220 * i / rate) * 0.3 * 32767).toInt() else 0
            (tone + noise).coerceIn(-32768, 32767).toShort()
        }
    }

    /** Source that hands out [pieces] samples per call (odd sizes, like a real microphone). */
    private fun source(pcm: ShortArray, pieces: Int, zerosBetween: Int = 0): (ShortArray, Int, Int) -> Int {
        var pos = 0
        var zeros = 0
        return { buf, off, len ->
            when {
                pos >= pcm.size -> END_OF_AUDIO
                zeros < zerosBetween -> { zeros++; 0 }
                else -> {
                    zeros = 0
                    val n = minOf(len, pieces, pcm.size - pos)
                    pcm.copyInto(buf, off, pos, pos + n)
                    pos += n
                    n
                }
            }
        }
    }

    private fun capture(read: (ShortArray, Int, Int) -> Int, stop: () -> Boolean = { false }) =
        UtteranceCapture.capture(read, stop, onLevel = {}, waitForData = {})

    @Test
    fun oddSizedReadsGiveTheSameTimingAsWholeChunks() {
        val pcm = recording(before = 800, voice = 2000, after = 3000)
        val whole = capture(source(pcm, 1600))
        val odd = capture(source(pcm, 337, zerosBetween = 2))
        assertEquals(Decision.END, whole.decision)
        assertEquals(Decision.END, odd.decision)
        assertEquals(whole.samples!!.size, odd.samples!!.size, "reassembly must not change when speech ends")
        // Ends 1.2 s after the voice stops: 0.8 + 2.0 + 1.2 = 4.0 s, well before the recording ends.
        assertEquals(4_000, odd.samples!!.size * 1000 / rate)
        // The samples are the recording itself, in order.
        assertEquals(pcm[20_000] / 32768f, odd.samples!![20_000])
    }

    @Test
    fun theWholeVoiceIsKept() {
        val pcm = recording(before = 300, voice = 3000, after = 2000)
        val u = capture(source(pcm, 500))
        val s = assertNotNull(u.samples)
        assertTrue(s.size * 1000 / rate >= 3_300, "voice not cut off")
    }

    @Test
    fun silenceGivesNothing() {
        val u = capture(source(recording(before = 9000, voice = 0, after = 0), 1600))
        assertEquals(Decision.NO_SPEECH, u.decision)
        assertNull(u.samples)
    }

    @Test
    fun recordingThatEndsMidSentenceIsStillUsed() {
        val u = capture(source(recording(before = 500, voice = 1500, after = 0), 999))
        assertEquals(Decision.CONTINUE, u.decision)
        assertEquals(2_000, assertNotNull(u.samples).size * 1000 / rate)
    }

    @Test
    fun stopButtonKeepsWhatWasSaid() {
        val pcm = recording(before = 200, voice = 10_000, after = 0)
        var reads = 0
        val read = source(pcm, 1600)
        val u = capture({ b, o, l -> reads++; read(b, o, l) }, stop = { reads >= 20 })
        assertEquals(20 * 1600, assertNotNull(u.samples).size)
    }

    @Test
    fun deadMicrophoneIsReportedNotHung() {
        val e = assertFailsWith<IllegalStateException> { capture({ _, _, _ -> 0 }) }
        assertTrue("ses gelmiyor" in e.message!!)
    }

    @Test
    fun deviceErrorIsReported() {
        val e = assertFailsWith<IllegalStateException> { capture({ _, _, _ -> -3 }) }
        assertTrue("-3" in e.message!!)
    }
}

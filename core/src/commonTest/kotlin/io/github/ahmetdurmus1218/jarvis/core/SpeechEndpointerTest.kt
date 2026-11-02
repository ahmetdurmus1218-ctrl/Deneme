package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.stt.SpeechEndpointer
import io.github.ahmetdurmus1218.jarvis.core.stt.SpeechEndpointer.Decision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechEndpointerTest {
    private fun run(e: SpeechEndpointer, levels: List<Float>): Pair<Decision, Int> {
        levels.forEachIndexed { i, l ->
            val d = e.feed(l)
            if (d != Decision.CONTINUE) return d to i
        }
        return Decision.CONTINUE to levels.size
    }

    private fun quiet(n: Int, level: Float = 0.003f) = List(n) { level }
    private fun loud(n: Int, level: Float = 0.1f) = List(n) { level }

    @Test
    fun endsAfterSpeechFollowedBySilence() {
        val e = SpeechEndpointer(chunkMs = 100)
        val (d, at) = run(e, quiet(5) + loud(15) + quiet(30))
        assertEquals(Decision.END, d)
        assertTrue(e.heardSpeech)
        assertEquals(5 + 15 + 12 - 1, at, "1.2 s of silence after speech")
    }

    @Test
    fun talkingImmediatelyIsStillSpeech() {
        val e = SpeechEndpointer(chunkMs = 100)
        val (d, _) = run(e, loud(10) + quiet(20))
        assertEquals(Decision.END, d)
        assertTrue(e.heardSpeech)
    }

    @Test
    fun shortPausesDoNotCutTheSentence() {
        val e = SpeechEndpointer(chunkMs = 100)
        val (d, at) = run(e, loud(10) + quiet(6) + loud(10) + quiet(20))
        assertEquals(Decision.END, d)
        assertTrue(at > 26, "the 0.6 s pause did not end it")
    }

    @Test
    fun givesUpWhenNothingIsSaid() {
        val e = SpeechEndpointer(chunkMs = 100)
        val (d, at) = run(e, quiet(200))
        assertEquals(Decision.NO_SPEECH, d)
        assertFalse(e.heardSpeech)
        assertEquals(79, at)
    }

    @Test
    fun aSingleClickIsNotSpeech() {
        val e = SpeechEndpointer(chunkMs = 100)
        val (d, _) = run(e, quiet(10) + loud(1, 0.5f) + quiet(100))
        assertEquals(Decision.NO_SPEECH, d)
    }

    @Test
    fun noisyRoomRaisesTheThreshold() {
        val e = SpeechEndpointer(chunkMs = 100)
        // Steady fan noise at 0.02 must not count as speech; a voice at 0.2 must.
        val (d, _) = run(e, quiet(30, 0.02f) + loud(10, 0.2f) + quiet(20, 0.02f))
        assertEquals(Decision.END, d)
        assertTrue(e.heardSpeech)
        val e2 = SpeechEndpointer(chunkMs = 100)
        assertEquals(Decision.NO_SPEECH, run(e2, quiet(100, 0.02f)).first)
    }

    @Test
    fun neverRecordsForever() {
        val e = SpeechEndpointer(chunkMs = 100)
        val (d, at) = run(e, loud(1000))
        assertEquals(Decision.END, d)
        assertEquals(299, at)
    }
}

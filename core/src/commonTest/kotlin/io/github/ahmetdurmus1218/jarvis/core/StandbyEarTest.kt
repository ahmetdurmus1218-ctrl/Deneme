package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.stt.ClapDetector
import io.github.ahmetdurmus1218.jarvis.core.stt.StandbyEar
import io.github.ahmetdurmus1218.jarvis.core.stt.WakeCall
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StandbyEarTest {
    private val sr = 16_000
    private val rnd = Random(7)

    private fun room(ms: Int, level: Float = 0.01f) = FloatArray(ms * sr / 1000) { (rnd.nextFloat() - 0.5f) * level }

    /** A voiced sound of [ms] at [amp], on top of the room. */
    private fun speech(ms: Int, amp: Float = 0.3f) = FloatArray(ms * sr / 1000) { i ->
        (rnd.nextFloat() - 0.5f) * 0.01f + amp * sin(2 * PI * 170 * i / sr).toFloat()
    }

    private fun clap() = FloatArray(sr * 70 / 1000) { i -> (rnd.nextFloat() - 0.5f) * 1.2f * exp(-i / (sr * 0.015f)) }

    /** Feeds in uneven pieces, like a real microphone; returns every event. */
    private fun feed(ear: StandbyEar, vararg parts: FloatArray): List<StandbyEar.Event> {
        val all = parts.fold(FloatArray(0)) { a, b -> a + b }
        val out = ArrayList<StandbyEar.Event>()
        var p = 0
        var step = 800
        while (p < all.size) {
            val n = minOf(step, all.size - p)
            ear.feed(all.copyOfRange(p, p + n))?.let(out::add)
            p += n
            step = if (step == 800) 1_130 else 800
        }
        return out
    }

    @Test
    fun silenceIsNeverCutOut() {
        assertTrue(feed(StandbyEar(), room(20_000)).isEmpty())
    }

    @Test
    fun aSentenceIsCutOutOnceWithALittleBeforeItAndNotMuchMore() {
        val events = feed(StandbyEar(), room(6_000), speech(1_500), room(2_000))
        val u = assertIs<StandbyEar.Event.Utterance>(events.single())
        val ms = u.samples.size * 1000 / sr
        // 1.5 s of speech + about half a second before + the 0.9 s of quiet that ended it; not the 6 s of waiting.
        assertTrue(ms in 2_300..3_400, "utterance of $ms ms")
    }

    @Test
    fun twoSentencesAreTwoUtterances() {
        val events = feed(StandbyEar(), room(1_000), speech(1_000), room(1_500), speech(800), room(1_500))
        assertEquals(2, events.size)
    }

    @Test
    fun aShortPauseInsideASentenceDoesNotSplitIt() {
        val events = feed(StandbyEar(), room(1_000), speech(700), room(400), speech(700), room(1_500))
        assertEquals(1, events.size)
    }

    @Test
    fun aTelevisionThatNeverStopsIsNeverHandedOver() {
        val ear = StandbyEar()
        // Three minutes of steady loud sound without a pause: nothing is cut out, at the start or later.
        assertTrue(feed(ear, room(1_000), speech(120_000, amp = 0.08f)).isEmpty())
        assertTrue(feed(ear, speech(60_000, amp = 0.08f)).isEmpty())
        // A person clearly louder than it is still heard.
        val person = feed(ear, speech(1_500, amp = 0.6f), speech(1_500, amp = 0.08f))
        assertEquals(1, person.size)
    }

    @Test
    fun aSoundWithoutAnyPauseUpToTheLimitIsDropped() {
        val ear = StandbyEar(maxSpeechMs = 5_000)
        assertTrue(feed(ear, room(500), speech(7_000)).isEmpty())
        // … and a sentence after it is heard as usual.
        assertEquals(1, feed(ear, room(2_000), speech(1_200), room(1_500)).size)
    }

    @Test
    fun theClickWhenTheMicrophoneOpensIsNotSpeech() {
        val ear = StandbyEar(claps = ClapDetector())
        // Every time the microphone opens it delivers a loud 250 ms burst first: never an utterance, never a clap.
        repeat(5) {
            ear.opened()
            val events = feed(ear, FloatArray(sr * 250 / 1000) { (rnd.nextFloat() - 0.5f) * 0.9f }, room(3_000))
            assertTrue(events.isEmpty(), "opening click taken for $events")
        }
        // A real sentence after opening is still heard, from its first word.
        ear.opened()
        assertEquals(1, feed(ear, room(600), speech(1_000), room(1_500)).size)
    }

    @Test
    fun twoClapsAreReportedAtOnceAndNotAsSpeech() {
        val events = feed(StandbyEar(claps = ClapDetector()), room(1_500), clap(), room(350), clap(), room(2_500))
        assertEquals(listOf<StandbyEar.Event>(StandbyEar.Event.Claps), events)
    }

    @Test
    fun theNameMakesARequest() {
        assertEquals(WakeCall.Heard.Request("Saat kaç"), WakeCall.parse("Rick, saat kaç"))
        assertEquals(WakeCall.Heard.Request("Saat kaç"), WakeCall.parse("Hey Rik saat kaç"))
        assertEquals(WakeCall.Heard.Request("Feneri aç"), WakeCall.parse("feneri aç Rick"))
        assertEquals(WakeCall.Heard.Request("Yarın sabah yedide alarm kur"), WakeCall.parse("RİK! yarın sabah yedide alarm kur"))
        assertEquals(WakeCall.Heard.Request("Bir şey soracağım"), WakeCall.parse("Rick'e bir şey soracağım"))
        assertEquals(WakeCall.Heard.Request("Müziği durdur"), WakeCall.parse("Jarvis müziği durdur"))
    }

    @Test
    fun theNameAloneIsACall() {
        assertEquals(WakeCall.Heard.NameOnly, WakeCall.parse("Rick"))
        assertEquals(WakeCall.Heard.NameOnly, WakeCall.parse("Hey Rick!"))
        assertEquals(WakeCall.Heard.NameOnly, WakeCall.parse("Rik efendim"))
    }

    @Test
    fun talkWithoutTheNameIsNotForRick() {
        assertEquals(WakeCall.Heard.NotForMe, WakeCall.parse("saat kaç oldu acaba"))
        assertEquals(WakeCall.Heard.NotForMe, WakeCall.parse("Erik aldım pazardan, brik de var"))
        assertEquals(WakeCall.Heard.NotForMe, WakeCall.parse("İzlediğiniz için teşekkürler"))
        assertEquals(WakeCall.Heard.NotForMe, WakeCall.parse(""))
    }
}

package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.ahmetdurmus1218.jarvis.core.stt.ClapDetector
import io.github.ahmetdurmus1218.jarvis.core.stt.StandbyEar
import io.github.ahmetdurmus1218.jarvis.ui.MicStream
import io.github.ahmetdurmus1218.jarvis.ui.PlatformBridge
import io.github.ahmetdurmus1218.jarvis.ui.Settings
import io.github.ahmetdurmus1218.jarvis.ui.SpeechInput
import io.github.ahmetdurmus1218.jarvis.ui.screens.SleepScreen
import io.github.ahmetdurmus1218.jarvis.ui.screens.WAKE_CLAP_AND_PHRASE
import io.github.ahmetdurmus1218.jarvis.ui.screens.WAKE_CLAP_ONLY
import org.junit.Rule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Does Rick really wake up to two claps, and only to them? Claps of different kinds (crisp, cupped, far away
 * with the room's echo, clipped loud, over a television) and things that must not wake it (real Turkish speech,
 * one clap, claps too far apart, a door slammed twice, a television, typing, plucked music) are played into:
 *  - the sleep screen itself, through a microphone that plays the sound (clap only, and clap + "uyan Rick"),
 *  - the hands-free ear the awake app and the floating Rick listen with.
 */
class ClapWakeTest {
    @get:Rule
    val rule = createComposeRule()

    private val sr = 16_000
    private val rnd = Random(11)

    // ---- sounds -------------------------------------------------------------------------------------------

    private fun silence(ms: Int, level: Float = 0.004f) = FloatArray(ms * sr / 1000) { (rnd.nextFloat() - 0.5f) * 2f * level }

    /**
     * A hand clap: a burst of noise that rises within a millisecond and dies in a few (crisp: flat palms,
     * cupped: hollow hands, darker with a resonance near 900 Hz), and in a room an echo that lingers.
     */
    private fun clap(peak: Float = 0.5f, cupped: Boolean = false, echo: Float = 0f): FloatArray {
        val n = sr * 320 / 1000
        val tau = if (cupped) 0.010f else 0.005f
        var low = 0f
        var last = 0f
        return FloatArray(n) { i ->
            val t = i.toFloat() / sr
            val env = (if (t < 0.001f) t / 0.001f else exp(-(t - 0.001f) / tau))
            val white = rnd.nextFloat() * 2f - 1f
            low += 0.3f * (white - low)
            val tone = if (cupped) 0.6f * sin(2 * PI.toFloat() * 900f * t) else 0f
            val body = if (cupped) low * 1.6f + tone else white - 0.6f * last
            last = white
            val tail = echo * exp(-t / 0.12f) * (rnd.nextFloat() * 2f - 1f)
            ((peak * env * body) + peak * tail).coerceIn(-1f, 1f)
        }
    }

    /** A television in the room: speech-like noise rising and falling a few times a second. */
    private fun television(ms: Int, level: Float = 0.03f): FloatArray {
        var low = 0f
        return FloatArray(ms * sr / 1000) { i ->
            val t = i.toFloat() / sr
            low += 0.15f * ((rnd.nextFloat() * 2f - 1f) - low)
            val syllables = 0.55f + 0.45f * sin(2 * PI.toFloat() * 4.3f * t) * sin(2 * PI.toFloat() * 0.7f * t + 1f)
            low * 3f * level * syllables
        }
    }

    /** A door slammed: a low thump with a long boom. */
    private fun slam(peak: Float = 0.7f) = FloatArray(sr * 600 / 1000) { i ->
        val t = i.toFloat() / sr
        val env = if (t < 0.004f) t / 0.004f else exp(-(t - 0.004f) / 0.12f)
        peak * env * (0.8f * sin(2 * PI.toFloat() * 70f * t) + 0.2f * (rnd.nextFloat() * 2f - 1f))
    }

    /** A plucked string (guitar, piano): a sharp start but a long ringing note. */
    private fun pluck(freq: Float, peak: Float = 0.5f) = FloatArray(sr * 450 / 1000) { i ->
        val t = i.toFloat() / sr
        val env = if (t < 0.002f) t / 0.002f else exp(-(t - 0.002f) / 0.25f)
        peak * env * (sin(2 * PI.toFloat() * freq * t) + 0.4f * sin(4 * PI.toFloat() * freq * t))
    }

    /** One keystroke: a tiny click. */
    private fun key() = FloatArray(sr * 15 / 1000) { i -> 0.04f * exp(-i / (sr * 0.002f)) * (rnd.nextFloat() * 2f - 1f) }

    private fun join(vararg parts: FloatArray) = parts.fold(FloatArray(0)) { a, b -> a + b }

    /** [over] played on top of [under] (same length as [under]). */
    private fun mix(under: FloatArray, over: FloatArray, at: Int = 0) = under.copyOf().also { out ->
        for (i in over.indices) if (at + i < out.size) out[at + i] = (out[at + i] + over[i]).coerceIn(-1f, 1f)
    }

    /** The real Turkish recording bundled with the tests (speech with pauses), at a gain. */
    private fun speech(gain: Float): FloatArray {
        val bytes = javaClass.getResourceAsStream("/tr-merhaba.wav")!!.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val len = b.getInt(p + 4)
            if (id == "data") {
                val pcm = ShortArray(len / 2)
                b.position(p + 8)
                b.asShortBuffer().get(pcm)
                return FloatArray(pcm.size) { (pcm[it] / 32768f * gain).coerceIn(-1f, 1f) }
            }
            p += 8 + len + (len and 1)
        }
        error("no audio in tr-merhaba.wav")
    }

    private val wakes: Map<String, FloatArray> by lazy {
        mapOf(
            "two crisp claps" to join(silence(1_200), clap(), silence(350), clap(), silence(1_500)),
            "two cupped claps" to join(silence(1_200), clap(cupped = true), silence(450), clap(cupped = true), silence(1_500)),
            "far away, with the room's echo" to join(silence(1_200), clap(0.16f, echo = 0.12f), silence(300), clap(0.16f, echo = 0.12f), silence(1_500)),
            "very loud (clipped)" to join(silence(1_200), clap(2.5f), silence(260), clap(2.5f), silence(1_500)),
            "slow claps, a second apart" to join(silence(1_200), clap(), silence(700), clap(), silence(1_500)),
            "over a television" to mix(television(6_000), join(clap(0.6f), silence(380), clap(0.6f)), at = sr * 3),
        )
    }

    private val notWakes: Map<String, FloatArray> by lazy {
        mapOf(
            "real Turkish speech, normal" to speech(1f),
            "real Turkish speech, loud" to speech(4f),
            "one clap" to join(silence(1_200), clap(), silence(2_500)),
            "two claps too far apart" to join(silence(1_200), clap(), silence(1_300), clap(), silence(1_500)),
            "a door slammed twice" to join(silence(1_200), slam(), silence(300), slam(), silence(1_500)),
            "a television" to television(20_000),
            "typing" to join(silence(800), *Array(40) { join(key(), silence(rnd.nextInt(90, 260))) }, silence(800)),
            "plucked music" to join(silence(800), pluck(220f), pluck(330f), pluck(262f), pluck(392f), pluck(330f), silence(800)),
        )
    }

    // ---- the hands-free ear (awake app, floating Rick) ------------------------------------------------------

    private fun earHearsClaps(audio: FloatArray): Boolean {
        val ear = StandbyEar(claps = ClapDetector())
        ear.opened()
        val all = join(silence(600), audio)
        var i = 0
        var claps = false
        var step = 800
        while (i < all.size) {
            val n = minOf(step, all.size - i)
            if (ear.feed(all.copyOfRange(i, i + n)) == StandbyEar.Event.Claps) claps = true
            i += n
            step = if (step == 800) 1_130 else 800
        }
        return claps
    }

    @Test
    fun theHandsFreeEarWakesToEveryKindOfDoubleClapAndToNothingElse() {
        val missed = wakes.filterValues { !earHearsClaps(it) }.keys
        val wrong = notWakes.filterValues { earHearsClaps(it) }.keys
        println("hands-free ear: woke to ${wakes.size - missed.size}/${wakes.size} double claps, to ${wrong.size}/${notWakes.size} other sounds")
        assertTrue(missed.isEmpty(), "did not wake to: $missed")
        assertTrue(wrong.isEmpty(), "woke to: $wrong")
    }

    // ---- the sleep screen ------------------------------------------------------------------------------------

    /** A microphone that plays [audio] (faster than real time), then 2 s of silence, then nothing (so the UI can settle). */
    private class PlayingMic(private val audio: FloatArray) : MicStream {
        val starts = AtomicInteger()
        val played = AtomicBoolean(false)
        @Volatile private var running = false

        override suspend fun start(onSamples: (FloatArray, Int) -> Unit): Boolean {
            stop()
            running = true
            val first = starts.incrementAndGet() == 1
            Thread {
                var i = 0
                val chunk = 1_600
                var quiet = 0
                while (running && quiet < 20) {
                    val buf = if (first && i < audio.size) {
                        audio.copyOfRange(i, minOf(audio.size, i + chunk)).also { i += it.size }
                    } else {
                        quiet++
                        FloatArray(chunk) { (Math.random().toFloat() - 0.5f) * 0.004f }
                    }
                    onSamples(buf, buf.size)
                    Thread.sleep(10) // 100 ms of sound every 10 ms
                }
                played.set(true)
            }.apply { isDaemon = true }.start()
            return true
        }

        override fun stop() { running = false }
    }

    /** A recognizer that "hears" [said] after the claps. */
    private class Hears(private val said: String) : SpeechInput {
        override val engineName = "test"
        override fun isAvailable() = true
        override fun start(listener: SpeechInput.Listener) {
            Thread { Thread.sleep(50); listener.onFinal(said) }.apply { isDaemon = true }.start()
        }
        override fun stop() {}
        override fun cancel() {}
    }

    private fun sleepScreenWakes(audio: FloatArray, mode: String, said: String = "uyan Rick"): Boolean {
        val mic = PlayingMic(audio)
        val bridge = object : PlatformBridge by AppUiTest.UiTestBridge(Hears(said)) {
            override val micStream: MicStream = mic
        }
        val woke = AtomicBoolean(false)
        rule.setContent { SleepScreen(bridge, Settings(wakeMode = mode)) { woke.set(true) } }
        // Plain waiting: the screen keeps changing while sound comes in, so it is not waited on to settle.
        val end = System.currentTimeMillis() + 25_000
        while (!woke.get() && !mic.played.get() && System.currentTimeMillis() < end) Thread.sleep(50)
        // A moment more after the sound ended, for the recognizer's answer.
        val until = System.currentTimeMillis() + 1_500
        while (!woke.get() && System.currentTimeMillis() < until) Thread.sleep(50)
        mic.stop()
        return woke.get()
    }

    @Test
    fun theSleepScreenWakesToTwoClaps() {
        assertTrue(sleepScreenWakes(wakes.getValue("two crisp claps"), WAKE_CLAP_ONLY), "clap only: two claps")
    }

    @Test
    fun theSleepScreenWakesToFarAwayClapsOverTheRoomsEcho() {
        assertTrue(sleepScreenWakes(wakes.getValue("far away, with the room's echo"), WAKE_CLAP_ONLY))
    }

    @Test
    fun theSleepScreenWakesToClapsAndUyanRick() {
        assertTrue(sleepScreenWakes(wakes.getValue("two cupped claps"), WAKE_CLAP_AND_PHRASE, said = "Uyan Rick"))
    }

    @Test
    fun theSleepScreenStaysAsleepWhenTheWordsAfterTheClapsAreNotForRick() {
        assertEquals(false, sleepScreenWakes(wakes.getValue("two crisp claps"), WAKE_CLAP_AND_PHRASE, said = "Bugün hava çok güzel"))
    }

    @Test
    fun theSleepScreenStaysAsleepThroughSpeechAndATelevision() {
        assertEquals(false, sleepScreenWakes(join(speech(2f), television(6_000)), WAKE_CLAP_ONLY))
    }
}

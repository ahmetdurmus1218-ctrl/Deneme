package io.github.ahmetdurmus1218.jarvis.core.stt

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds hand claps in 16 kHz microphone audio, entirely on the device: a clap is a sudden, loud,
 * short burst — the 5 ms energy jumps far above the room's noise floor within a few milliseconds
 * and is gone again within ~120 ms. Speech, music and door slams rise more slowly or last longer.
 * Two claps 0.15–0.8 s apart (or the user's own recorded rhythm) make a wake signal.
 */
class ClapDetector(
    private val sampleRate: Int = UtteranceCapture.SAMPLE_RATE,
    /** How far above the noise floor a clap must jump (×). Calibrated in Settings. */
    var threshold: Float = 8f,
) {
    private val win = sampleRate / 200 // 5 ms
    private var acc = 0.0
    private var n = 0
    private var floor = 0.003f
    private var prev = 0f
    private var t = 0L // samples seen

    private var inBurst = false
    private var burstStart = 0L
    private var burstPeak = 0f
    private var lastClapAt = Long.MIN_VALUE / 2
    private val claps = ArrayList<Long>()

    /** Single claps found so far (for the calibration dot). */
    var clapsSeen = 0; private set

    /** Live level for the calibration bar (0..1, relative to the threshold). */
    var level = 0f; private set

    /** A rhythm (gaps between claps in ms) to wait for instead of a double clap; null = double clap. */
    var rhythm: List<Long>? = null

    /** Feeds samples; returns true when the wake pattern was just completed. */
    fun feed(samples: FloatArray, count: Int = samples.size): Boolean {
        var wake = false
        for (i in 0 until count) {
            val s = samples[i]
            acc += (s * s).toDouble()
            n++
            t++
            if (n < win) continue
            val rms = sqrt(acc / n).toFloat()
            acc = 0.0
            n = 0
            if (window(rms)) wake = true
        }
        return wake
    }

    fun feed(pcm: ShortArray, count: Int = pcm.size): Boolean {
        val f = FloatArray(count) { pcm[it] / 32768f }
        return feed(f, count)
    }

    private fun ms(samples: Long) = samples * 1000 / sampleRate

    private fun window(rms: Float): Boolean {
        val ratio = rms / floor.coerceAtLeast(1e-4f)
        level = (ratio / threshold).coerceIn(0f, 1.5f) / 1.5f
        var wake = false
        if (!inBurst) {
            // Onset: a jump far above the floor AND a steep rise from the previous 5 ms.
            if (ratio >= threshold && rms > prev * 3f && rms > 0.02f) {
                inBurst = true
                burstStart = t
                burstPeak = rms
            } else {
                // Slowly follow the room (faster down than up, so speech does not raise it much).
                floor = if (rms < floor) floor * 0.9f + rms * 0.1f else floor * 0.995f + rms * 0.005f
            }
        } else {
            burstPeak = max(burstPeak, rms)
            val len = ms(t - burstStart)
            if (rms < burstPeak * 0.18f || rms < floor * 2.5f) {
                inBurst = false
                if (len in 5..MAX_CLAP_MS) wake = clap(burstStart)
            } else if (len > MAX_CLAP_MS) {
                // Too long for a clap (speech, music, a slam with echo): wait until it has died down.
                inBurst = false
                claps.clear()
                floor = floor * 0.9f + rms * 0.02f
            }
        }
        prev = rms
        return wake
    }

    private fun clap(at: Long): Boolean {
        val now = ms(at)
        if (now - lastClapAt < 90) return false // the same clap's echo
        lastClapAt = now
        clapsSeen++
        claps += now
        val pattern = rhythm
        if (pattern == null) {
            while (claps.size > 2) claps.removeAt(0)
            if (claps.size == 2) {
                val gap = claps[1] - claps[0]
                if (gap in 150..800) { claps.clear(); return true }
                if (gap > 800) claps.removeAt(0)
            }
            return false
        }
        val need = pattern.size + 1
        while (claps.size > need) claps.removeAt(0)
        if (claps.size == need) {
            val gaps = claps.zipWithNext { a, b -> b - a }
            if (gaps.zip(pattern).all { (g, p) -> abs(g - p) <= p * 0.2f }) { claps.clear(); return true }
        }
        return false
    }

    companion object {
        const val MAX_CLAP_MS = 120L

        /** Records a rhythm: the gaps between the claps' times (ms). */
        fun rhythmOf(clapTimes: List<Long>): List<Long> = clapTimes.zipWithNext { a, b -> b - a }
    }
}

/** "uyan Jarvis" (and Whisper spellings) after the claps. */
object WakePhrase {
    private val RE = Regex("\\b(?:uyan|uyanin|kalk|gunaydin)\\b")
    fun matches(text: String): Boolean = RE.containsMatchIn(io.github.ahmetdurmus1218.jarvis.core.text.Turkish.normalize(text))

    /** "Günaydın / İyi günler / İyi akşamlar efendim" by the hour. */
    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Günaydın efendim."
        in 12..17 -> "İyi günler efendim."
        in 18..22 -> "İyi akşamlar efendim."
        else -> "İyi geceler efendim."
    }
}

package io.github.ahmetdurmus1218.jarvis.core.stt

import io.github.ahmetdurmus1218.jarvis.core.AssistantName
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hands-free standby: watches a continuous 16 kHz microphone stream and cuts out what was said, one
 * utterance at a time, without recognizing anything itself. Only loudness is looked at here; the
 * caller transcribes an utterance and keeps it only when the assistant's name is in it.
 *
 * - Silence costs nothing: no recognizer runs until somebody spoke and went quiet again.
 * - Half a second before the first loud moment is kept, so the first word is not clipped.
 * - A sound that never stops (a television) slowly becomes the background and is no longer cut out.
 * - Two claps (or the user's rhythm) are reported at once through [claps].
 */
class StandbyEar(
    /** Reports the wake claps when set (may be changed between sessions). */
    var claps: ClapDetector? = null,
    private val endSilenceMs: Int = 900,
    private val maxSpeechMs: Int = 12_000,
    private val prerollMs: Int = 500,
    private val minSpeechRms: Float = 0.012f,
) {
    sealed interface Event {
        /** The wake rhythm was clapped. */
        data object Claps : Event

        /** Somebody said something and stopped: 16 kHz mono samples in -1..1. */
        class Utterance(val samples: FloatArray) : Event
    }

    /** Loudness of the last chunk, 0..1, for the listening indicator. */
    var level: Float = 0f
        private set

    private val chunk = FloatArray(CHUNK)
    private var filled = 0
    private var buf = FloatArray(RATE * 4)
    private var size = 0
    private var noise = -1f
    private var loudRun = 0
    private var speaking = false
    private var speechMs = 0
    private var silenceMs = 0

    /** Feeds microphone samples; returns an event when there is one (at most one per call). */
    fun feed(samples: FloatArray, count: Int = samples.size): Event? {
        if (claps?.feed(samples, count) == true) {
            forget()
            return Event.Claps
        }
        var result: Event? = null
        var i = 0
        while (i < count) {
            val n = min(CHUNK - filled, count - i)
            samples.copyInto(chunk, filled, i, i + n)
            filled += n
            i += n
            if (filled < CHUNK) break
            filled = 0
            val done = onChunk()
            if (done != null && result == null) result = done
        }
        return result
    }

    private fun onChunk(): Event? {
        var sum = 0.0
        for (v in chunk) sum += v * v
        val rms = sqrt(sum / CHUNK).toFloat()
        level = (rms * 12f).coerceIn(0f, 1f)
        if (noise < 0f) noise = min(rms, INITIAL_NOISE_CAP)
        val loud = rms > max(minSpeechRms, noise * 3f)
        if (loud) {
            loudRun++
            // A sound that goes on and on is the room, not a request: it becomes the floor after a while.
            noise += (rms - noise) * STEADY_SOUND_RATE
        } else {
            loudRun = 0
            noise = if (rms < noise) rms else noise + (rms - noise) * 0.05f
        }
        append()
        if (!speaking) {
            if (loudRun >= 2) {
                speaking = true
                speechMs = CHUNK_MS * 2
                silenceMs = 0
            } else {
                // Nothing said yet: remember only the last moment.
                val keep = RATE / 1000 * prerollMs + CHUNK * loudRun
                if (size > keep) {
                    buf.copyInto(buf, 0, size - keep, size)
                    size = keep
                }
            }
            return null
        }
        speechMs += CHUNK_MS
        silenceMs = if (loud) 0 else silenceMs + CHUNK_MS
        if (silenceMs < endSilenceMs && speechMs < maxSpeechMs) return null
        val out = buf.copyOf(size)
        forget()
        return Event.Utterance(out)
    }

    private fun append() {
        if (size + CHUNK > buf.size) buf = buf.copyOf(buf.size * 2)
        chunk.copyInto(buf, size)
        size += CHUNK
    }

    /** Drops what was collected (the room's noise level is kept). */
    fun forget() {
        size = 0
        filled = 0
        loudRun = 0
        speaking = false
        speechMs = 0
        silenceMs = 0
        level = 0f
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK_MS = 100
        private const val CHUNK = RATE * CHUNK_MS / 1000
        private const val INITIAL_NOISE_CAP = 0.02f
        private const val STEADY_SOUND_RATE = 0.004f
    }
}

/**
 * What a sentence heard in standby means: nothing (the name was not said), only the name ("Rick?"),
 * or a request with the name in it ("Rick, saat kaç", "saat kaç Rick").
 */
object WakeCall {
    sealed interface Heard {
        data object NotForMe : Heard
        data object NameOnly : Heard
        data class Request(val text: String) : Heard
    }

    private val NAME = Regex("^${AssistantName.WAKE}$")
    /** Little words around the name that are not a request by themselves. */
    private val FILLER = setOf("hey", "ey", "hop", "alo", "merhaba", "selam", "efendim", "bak", "baksana", "ya", "be")

    fun parse(text: String): Heard {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val at = words.indexOfFirst { isName(it) }
        if (at < 0) return Heard.NotForMe
        val rest = words.filterIndexed { i, w -> i != at && !(i < at && Turkish.normalize(w).trim() in FILLER) }
        val meaningful = rest.filter { Turkish.normalize(it).trim().let { n -> n.isNotBlank() && n !in FILLER } }
        if (meaningful.isEmpty()) return Heard.NameOnly
        return Heard.Request(rest.joinToString(" ").trim().trimStart(',', '.', '!', '?', ';', ':', '-', ' ').replaceFirstChar { it.uppercaseChar() })
    }

    /** "Rick", "Rick,", "Rick'e", "RİK!" … */
    private fun isName(word: String): Boolean {
        val stem = word.substringBefore('\'').substringBefore('’')
        return NAME.matches(Turkish.normalize(stem).trim())
    }
}

package io.github.ahmetdurmus1218.jarvis.core.stt

import kotlin.math.max
import kotlin.math.min

/**
 * Decides, from the loudness (RMS, 0..1) of consecutive microphone chunks, when the user has finished
 * talking: after speech was heard and then [endSilenceMs] of quiet followed. Gives up when nothing is
 * said within [noSpeechMs], and never records longer than [maxMs]. The noise floor adapts to the room.
 */
class SpeechEndpointer(
    private val chunkMs: Int,
    private val endSilenceMs: Int = 1_200,
    private val noSpeechMs: Int = 8_000,
    private val maxMs: Int = 30_000,
    private val minSpeechRms: Float = 0.012f,
    /** Consecutive loud chunks needed before it counts as speech (ignores clicks and taps). */
    private val minSpeechChunks: Int = 2,
) {
    enum class Decision { CONTINUE, END, NO_SPEECH }

    var heardSpeech: Boolean = false
        private set
    private var noise = -1f
    private var elapsed = 0
    private var loudRun = 0
    private var silence = 0

    fun feed(rms: Float): Decision {
        elapsed += chunkMs
        // Capped first estimate, so talking right away is not taken for background noise.
        if (noise < 0f) noise = min(rms, INITIAL_NOISE_CAP)
        val loud = rms > max(minSpeechRms, noise * 3f)
        if (loud) {
            loudRun++
            silence = 0
            if (loudRun >= minSpeechChunks) heardSpeech = true
        } else {
            loudRun = 0
            // Noise floor: follows quieter levels at once (gaps between words), louder ones slowly (a fan).
            noise = if (rms < noise) rms else noise + (rms - noise) * 0.05f
            if (heardSpeech) silence += chunkMs
        }
        return when {
            heardSpeech && silence >= endSilenceMs -> Decision.END
            !heardSpeech && elapsed >= noSpeechMs -> Decision.NO_SPEECH
            elapsed >= maxMs -> Decision.END
            else -> Decision.CONTINUE
        }
    }

    private companion object {
        const val INITIAL_NOISE_CAP = 0.02f
    }
}

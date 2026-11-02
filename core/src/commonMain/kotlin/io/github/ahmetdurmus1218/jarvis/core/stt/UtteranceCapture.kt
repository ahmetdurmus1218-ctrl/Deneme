package io.github.ahmetdurmus1218.jarvis.core.stt

import kotlin.math.sqrt

/** One spoken request: 16 kHz mono samples in -1..1, or null when nothing was said. */
class Utterance(val samples: FloatArray?, val decision: SpeechEndpointer.Decision, val heardSpeech: Boolean)

/**
 * Collects one utterance from a 16 kHz PCM source (the microphone, or a recording in the on-device
 * test) until the speaker goes quiet, the user stops, or the source ends. The end-of-speech detector
 * always sees whole 100 ms chunks, however the source splits the data.
 */
object UtteranceCapture {
    const val SAMPLE_RATE = 16_000
    const val CHUNK_MS = 100
    /** Returned by a non-microphone source when its audio is used up. */
    const val END_OF_AUDIO = Int.MIN_VALUE
    /** A microphone that delivers nothing for this long is broken or taken by another app. */
    const val MAX_NO_DATA_MS = 3_000
    private const val IDLE_STEP_MS = 10

    /**
     * @param read fills (buffer, offset, count) and returns the samples read; 0 = nothing yet,
     *   [END_OF_AUDIO] = finished, other negative values = device error.
     * @param waitForData called when nothing was available (the microphone sleeps ~10 ms here).
     */
    fun capture(
        read: (ShortArray, Int, Int) -> Int,
        isStopped: () -> Boolean,
        onLevel: (Float) -> Unit,
        waitForData: () -> Unit,
        endpointer: SpeechEndpointer = SpeechEndpointer(chunkMs = CHUNK_MS),
    ): Utterance {
        val chunk = ShortArray(SAMPLE_RATE * CHUNK_MS / 1000)
        var filled = 0
        var out = FloatArray(SAMPLE_RATE * 10)
        var size = 0
        var decision = SpeechEndpointer.Decision.CONTINUE
        var waitedMs = 0
        while (!isStopped()) {
            val n = read(chunk, filled, chunk.size - filled)
            if (n == END_OF_AUDIO) break
            if (n < 0) error("Mikrofon okunamadı (kod $n)")
            if (n == 0) {
                waitedMs += IDLE_STEP_MS
                if (waitedMs > MAX_NO_DATA_MS) error("Mikrofondan ses gelmiyor (başka bir uygulama kullanıyor olabilir)")
                waitForData()
                continue
            }
            waitedMs = 0
            if (size + n > out.size) out = out.copyOf(maxOf(out.size * 2, size + n))
            for (i in 0 until n) out[size + i] = chunk[filled + i] / 32768f
            size += n
            filled += n
            if (filled < chunk.size) continue
            filled = 0
            var sum = 0.0
            for (v in chunk) {
                val f = v / 32768f
                sum += f * f
            }
            val rms = sqrt(sum / chunk.size).toFloat()
            onLevel((rms * 12f).coerceIn(0f, 1f))
            decision = endpointer.feed(rms)
            if (decision != SpeechEndpointer.Decision.CONTINUE) break
        }
        val enough = endpointer.heardSpeech || (isStopped() && size >= SAMPLE_RATE / 2)
        return Utterance(if (enough) out.copyOf(size) else null, decision, endpointer.heardSpeech)
    }
}

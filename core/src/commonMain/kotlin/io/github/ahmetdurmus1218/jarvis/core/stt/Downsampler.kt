package io.github.ahmetdurmus1218.jarvis.core.stt

/**
 * Converts a higher sample rate (e.g. 48 or 44.1 kHz from a PC sound card) to 16 kHz by averaging
 * the input samples that fall into each output sample's time slot (a box filter, enough for speech
 * recognition). Keeps state between calls, so the input may arrive in pieces of any size.
 */
class Downsampler(private val fromRate: Int, private val toRate: Int = UtteranceCapture.SAMPLE_RATE) {
    init {
        require(fromRate >= toRate && toRate > 0) { "only downsampling: $fromRate → $toRate" }
    }

    private var inputCount = 0L   // input samples consumed so far
    private var outputCount = 0L  // output samples produced so far
    private var sum = 0L
    private var n = 0

    /** Feeds [count] samples of [input]; writes output samples to [out] from [outOffset]; returns how many. */
    fun process(input: ShortArray, count: Int, out: ShortArray, outOffset: Int = 0): Int {
        var w = outOffset
        for (i in 0 until count) {
            sum += input[i]
            n++
            inputCount++
            // Output sample k covers input samples [k·from/to, (k+1)·from/to).
            val boundary = (outputCount + 1) * fromRate / toRate
            if (inputCount >= boundary) {
                out[w++] = (sum / n).toInt().toShort()
                outputCount++
                sum = 0
                n = 0
            }
        }
        return w - outOffset
    }

    /** Upper bound of output samples for [count] input samples (for buffer sizing). */
    fun maxOutput(count: Int): Int = (count.toLong() * toRate / fromRate).toInt() + 2
}

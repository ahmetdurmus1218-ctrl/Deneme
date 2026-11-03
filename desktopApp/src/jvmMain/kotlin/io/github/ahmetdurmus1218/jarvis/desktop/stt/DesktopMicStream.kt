package io.github.ahmetdurmus1218.jarvis.desktop.stt

import io.github.ahmetdurmus1218.jarvis.core.stt.Downsampler
import io.github.ahmetdurmus1218.jarvis.core.stt.UtteranceCapture
import io.github.ahmetdurmus1218.jarvis.ui.MicStream
import kotlin.concurrent.thread

/** The PC microphone as a 16 kHz stream (for the clap wake-up), with the same 48/44.1 kHz fallback. */
class DesktopMicStream(private val open: () -> MicLine = { DesktopWhisperSpeechInput.openMicrophone() }) : MicStream {
    @Volatile private var running = false
    private var worker: Thread? = null

    override suspend fun start(onSamples: (FloatArray, Int) -> Unit): Boolean {
        stop()
        val mic = try { open() } catch (_: Exception) { return false }
        running = true
        worker = thread(name = "jarvis-clap-mic", isDaemon = true) {
            val line = mic.line
            val down = if (mic.rate != UtteranceCapture.SAMPLE_RATE) Downsampler(mic.rate) else null
            val bytes = ByteArray(mic.rate / 10 * 2)
            val shorts = ShortArray(bytes.size / 2)
            val out = ShortArray(down?.maxOutput(shorts.size) ?: shorts.size)
            val f = FloatArray(out.size)
            try {
                line.start()
                while (running) {
                    val n = line.read(bytes, 0, bytes.size)
                    if (n <= 0) continue
                    val count = n / 2
                    for (i in 0 until count) shorts[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                    val m = down?.process(shorts, count, out) ?: run { shorts.copyInto(out, 0, 0, count); count }
                    for (i in 0 until m) f[i] = out[i] / 32768f
                    onSamples(f, m)
                }
            } finally {
                runCatching { line.stop(); line.close() }
            }
        }
        return true
    }

    override fun stop() {
        running = false
        worker?.join(500)
        worker = null
    }
}

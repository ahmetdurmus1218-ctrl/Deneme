package io.github.ahmetdurmus1218.jarvis.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.ahmetdurmus1218.jarvis.ui.MicStream
import kotlin.concurrent.thread

/** The phone microphone as a 16 kHz stream for the clap wake-up; only while the app is on screen. */
class AndroidMicStream(private val ensurePermission: suspend () -> Boolean) : MicStream {
    @Volatile private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission") // ensurePermission() is checked first
    override suspend fun start(onSamples: (FloatArray, Int) -> Unit): Boolean {
        stop()
        if (!ensurePermission()) return false
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        running = true
        worker = thread(name = "jarvis-clap-mic", isDaemon = true) {
            val buf = ShortArray(rate / 20)
            val f = FloatArray(buf.size)
            try {
                rec.startRecording()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    for (i in 0 until n) f[i] = buf[i] / 32768f
                    onSamples(f, n)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { rec.stop() }
                rec.release()
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

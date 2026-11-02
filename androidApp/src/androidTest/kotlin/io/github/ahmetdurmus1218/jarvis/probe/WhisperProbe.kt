package io.github.ahmetdurmus1218.jarvis.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import io.github.ahmetdurmus1218.jarvis.android.AndroidModelStorage
import io.github.ahmetdurmus1218.jarvis.android.WhisperSpeechInput
import io.github.ahmetdurmus1218.jarvis.android.WhisperTranscriber
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.stt.SpeechEndpointer
import io.github.ahmetdurmus1218.jarvis.core.stt.UtteranceCapture
import io.github.ahmetdurmus1218.jarvis.core.stt.UtteranceCapture.END_OF_AUDIO
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device check of Jarvis's own speech recognition, run by the CI emulator test after the Whisper
 * files were downloaded through Kapı:
 *   adb shell am instrument -w io.github.ahmetdurmus1218.jarvis.test/io.github.ahmetdurmus1218.jarvis.probe.WhisperProbe
 * A Turkish recording ("Merhaba Jarvis, bugün hava nasıl?", 0.8 s noise before, 2 s after) goes through
 * the SAME capture loop (end-of-speech detection) and the SAME Whisper decoder the microphone uses,
 * with the model files the app installed and verified. No test framework is needed.
 */
class WhisperProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val out = Bundle()
        val ok = try {
            run(out)
        } catch (t: Throwable) {
            out.putString("error", t.stackTraceToString().take(3000))
            false
        }
        out.putString("result", if (ok) "PASS" else "FAIL")
        finish(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, out)
    }

    private fun run(out: Bundle): Boolean {
        val app = targetContext
        val storage = AndroidModelStorage(app) { null }
        val paths = WhisperSpeechInput(storage, ModelRegistry.bundled) { true }.installedFiles()
            ?: error("Whisper files are not installed in the app")

        val pcm = readWav16kMono(context.assets.open("tr-merhaba.wav").use { it.readBytes() })
        out.putString("audio_ms", (pcm.size / 16).toString())

        var pos = 0
        val utterance = UtteranceCapture.capture(
            // Deliver odd-sized pieces like a real microphone does, to exercise chunk reassembly.
            read = { buf, off, len ->
                if (pos >= pcm.size) END_OF_AUDIO
                else {
                    val n = minOf(len, 337, pcm.size - pos)
                    System.arraycopy(pcm, pos, buf, off, n)
                    pos += n
                    n
                }
            },
            isStopped = { false },
            onLevel = {},
            waitForData = {},
        )
        val samples = utterance.samples ?: error("end-of-speech detector heard nothing")
        out.putString("endpointer", "${utterance.decision} after ${samples.size / 16} ms")

        val t0 = System.nanoTime()
        val t = WhisperTranscriber(paths)
        val t1 = System.nanoTime()
        val text = try { t.transcribe(samples) } finally { t.close() }
        val t2 = System.nanoTime()
        out.putString("text", text)
        out.putString("load_ms", ((t1 - t0) / 1_000_000).toString())
        out.putString("decode_ms", ((t2 - t1) / 1_000_000).toString())

        val n = Turkish.normalize(text)
        val stoppedAfterSpeech = utterance.decision == SpeechEndpointer.Decision.END && samples.size < pcm.size
        return "merhaba" in n && "hava" in n && "nasil" in n && stoppedAfterSpeech
    }

    /** Minimal RIFF reader: finds the "data" chunk of a 16-bit mono 16 kHz WAV. */
    private fun readWav16kMono(bytes: ByteArray): ShortArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val len = b.getInt(p + 4)
            if (id == "fmt ") {
                require(b.getShort(p + 10).toInt() == 1 && b.getInt(p + 12) == 16_000 && b.getShort(p + 22).toInt() == 16) {
                    "expected 16 kHz mono 16-bit"
                }
            }
            if (id == "data") {
                val samples = ShortArray(len / 2)
                b.position(p + 8)
                b.asShortBuffer().get(samples)
                return samples
            }
            p += 8 + len + (len and 1)
        }
        error("no data chunk")
    }
}

package io.github.ahmetdurmus1218.jarvis.probe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.runBlocking
import java.io.File
import io.github.ahmetdurmus1218.jarvis.android.AndroidModelStorage
import io.github.ahmetdurmus1218.jarvis.android.AndroidCamera
import io.github.ahmetdurmus1218.jarvis.android.AndroidZim
import io.github.ahmetdurmus1218.jarvis.android.PiperSpeechOutput
import io.github.ahmetdurmus1218.jarvis.core.vision.Gesture
import io.github.ahmetdurmus1218.jarvis.core.vision.GestureClassifier
import io.github.ahmetdurmus1218.jarvis.core.knowledge.Wikipedia
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
 * On-device checks run by the CI emulator test (no test framework needed):
 *   adb shell am instrument -w -e probe whisper io.github.ahmetdurmus1218.jarvis.test/io.github.ahmetdurmus1218.jarvis.probe.Probes
 *   adb shell am instrument -w -e probe wiki -e zim /data/local/tmp/test.zim io.github.ahmetdurmus1218.jarvis.test/...Probes
 *
 * whisper: a Turkish recording ("Merhaba Rick, bugün hava nasıl?", 0.8 s noise before, 2 s after)
 *   goes through the SAME capture loop and Whisper decoder the microphone uses, with the model files
 *   the app installed and verified through Kapı.
 * wiki: a real Wikipedia ZIM is opened with the app's AndroidZim (libzim on the device) and searched
 *   and read through the shared core Wikipedia logic, as wiki_ara / wiki_oku do.
 */
class Probes : Instrumentation() {
    private var args: Bundle = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val out = Bundle()
        val ok = try {
            when (val probe = args.getString("probe") ?: "whisper") {
                "whisper" -> whisper(out)
                "wiki" -> wiki(out)
                "tracking" -> tracking(out)
                "piper" -> piper(out)
                else -> error("unknown probe $probe")
            }
        } catch (t: Throwable) {
            out.putString("error", t.stackTraceToString().take(3000))
            false
        }
        out.putString("result", if (ok) "PASS" else "FAIL")
        finish(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, out)
    }

    private fun whisper(out: Bundle): Boolean {
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

    /**
     * Rick's own voice ON the device: the Piper archive the app installed through Kapı is unpacked
     * and speaks a sentence (phonemes from the core Turkish phonemizer); Whisper must hear it.
     */
    private fun piper(out: Bundle): Boolean = runBlocking {
        val app = targetContext
        val storage = AndroidModelStorage(app) { null }
        val tts = PiperSpeechOutput(storage, ModelRegistry.bundled, NoSpeech)
        if (!tts.installed()) error("Piper voice is not installed in the app")
        val t0 = System.nanoTime()
        val (samples, rate) = tts.render("Merhaba efendim, ben Rick. Her şey hazır. Saat on dört otuz beş.") ?: error("Piper did not load: ${tts.engineName}")
        val t1 = System.nanoTime()
        out.putString("tts_ms", ((t1 - t0) / 1_000_000).toString())
        out.putString("audio_ms", (samples.size * 1000L / rate).toString())
        out.putString("engine", tts.engineName)
        val n = (samples.size.toLong() * 16000 / rate).toInt()
        val pcm = FloatArray(n) { i ->
            val x = i * (samples.size - 1).toDouble() / (n - 1)
            val a = x.toInt()
            val f = (x - a).toFloat()
            samples[a] * (1 - f) + samples[minOf(a + 1, samples.lastIndex)] * f
        }
        val paths = WhisperSpeechInput(storage, ModelRegistry.bundled) { true }.installedFiles() ?: error("Whisper files are not installed")
        val t = WhisperTranscriber(paths)
        val text = try { t.transcribe(pcm) } finally { t.close() }
        out.putString("heard", text)
        val norm = Turkish.normalize(text)
        "merhaba" in norm && "hazir" in norm
    }

    private object NoSpeech : io.github.ahmetdurmus1218.jarvis.ui.SpeechOutput {
        override val engineName = "-"
        override suspend fun speak(text: String, rate: Float) {}
        override fun stop() {}
    }

    private fun wiki(out: Bundle): Boolean = runBlocking {
        val src = args.getString("zim") ?: error("-e zim <path> missing")
        // The shell (not the app) can read /data/local/tmp: stream the file through it into the app's cache.
        val dst = File(targetContext.cacheDir, "probe.zim")
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("cat $src")).use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        out.putString("zim_bytes", dst.length().toString())
        val wiki = Wikipedia(AndroidZim { dst.absolutePath })
        val t0 = System.nanoTime()
        val hits = wiki.search("Greenhouse gas emissions nedir?")
        val t1 = System.nanoTime()
        out.putString("search_ms", ((t1 - t0) / 1_000_000).toString())
        out.putString("hits", hits.joinToString(" | ") { it.title })
        val titleHit = wiki.search("global warming").firstOrNull()
        out.putString("title_hit", titleHit?.title ?: "-")
        val article = hits.firstOrNull()?.let { wiki.read(it.path) }
        out.putString("article_title", article?.title ?: "-")
        out.putString("article_start", article?.text?.take(200) ?: "-")
        hits.any { it.title == "Greenhouse gas emissions" } &&
            titleHit?.title?.contains("global warming", ignoreCase = true) == true &&
            article != null && "greenhouse" in article.text.lowercase() && "<" !in article.text.take(500)
    }

    /**
     * MediaPipe on the device with the tracking models the app installed through Kapı: real hand
     * photos (MediaPipe's own gesture test images, pushed to /data/local/tmp) must be classified as
     * the right gesture by Rick's core classifier, and the portrait must show exactly one face.
     */
    private fun tracking(out: Bundle): Boolean {
        val dir = args.getString("dir") ?: error("-e dir <path> missing")
        val app = targetContext
        val storage = AndroidModelStorage(app) { null }
        val reg = ModelRegistry.bundled
        fun path(id: String) = reg.lock.items.first { it.id == id }.takeIf { storage.isInstalled(it) }?.let(storage::pathOf)
            ?: error("$id is not installed in the app")
        val trackers = AndroidCamera.Trackers.create(app, path(AndroidCamera.HAND_MODEL), path(AndroidCamera.FACE_MODEL))
            ?: error("tracking models failed to load")
        fun image(name: String): android.graphics.Bitmap {
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("cat $dir/$name")).use { it.readBytes() }
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("cannot decode $name")
        }
        val expected = mapOf(
            "thumb_up.jpg" to Gesture.THUMBS_UP, "pointing_up.jpg" to Gesture.POINTING,
            "victory.jpg" to Gesture.VICTORY, "fist.jpg" to Gesture.FIST,
        )
        var ts = 1L
        var ok = true
        trackers.use { t ->
            for ((name, want) in expected) {
                val hands = t.hands(image(name), ts++)
                val got = hands.firstOrNull()?.let(GestureClassifier::classify)
                out.putString(name, "${hands.size} el → ${got ?: "-"}")
                if (got != want) ok = false
            }
            val faces = t.faces(image("portrait.jpg"), ts++)
            out.putString("portrait.jpg", "${faces.size} yüz")
            if (faces.size != 1) ok = false
        }
        return ok
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

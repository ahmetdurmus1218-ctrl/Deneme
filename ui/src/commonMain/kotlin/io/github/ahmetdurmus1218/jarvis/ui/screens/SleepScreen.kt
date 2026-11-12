package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.stt.ClapDetector
import io.github.ahmetdurmus1218.jarvis.core.stt.WakePhrase
import io.github.ahmetdurmus1218.jarvis.ui.PlatformBridge
import io.github.ahmetdurmus1218.jarvis.ui.Settings
import io.github.ahmetdurmus1218.jarvis.ui.SpeechInput
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sleep: an almost black screen with a breathing dot. Two claps (or the user's own rhythm), then
 * "uyan Rick" within a few seconds, wake it; so does a double tap. The microphone only listens
 * for claps here, on the device; nothing is recorded or kept.
 */
@Composable
fun SleepScreen(bridge: PlatformBridge, settings: Settings, onWake: () -> Unit) {
    var phase by remember { mutableStateOf(Phase.SLEEP) }
    var micOk by remember { mutableStateOf<Boolean?>(null) }
    var heard by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0f) }

    var status by remember { mutableStateOf("") }

    // The microphone is this screen's while it shows: hands-free standby steps aside first.
    androidx.compose.runtime.DisposableEffect(Unit) {
        io.github.ahmetdurmus1218.jarvis.ui.MicUse.take()
        onDispose {
            bridge.micStream?.stop()
            io.github.ahmetdurmus1218.jarvis.ui.MicUse.release()
        }
    }
    LaunchedEffect(Unit) {
        // Load speech recognition now, so "uyan" is understood right after the claps.
        if (settings.wakeMode != WAKE_CLAP_ONLY) bridge.speechInput?.warmUp()
        val mic = bridge.micStream
        while (true) {
            if (mic == null) { micOk = false; return@LaunchedEffect }
            phase = Phase.SLEEP
            status = ""
            val claps = CompletableDeferred<Unit>()
            val detector = ClapDetector(threshold = settings.clapThreshold).apply { rhythm = settings.clapRhythm }
            micOk = mic.start { s, n ->
                if (detector.feed(s, n)) claps.complete(Unit)
                level = detector.level
            }
            if (micOk != true) return@LaunchedEffect
            claps.await()
            mic.stop()
            if (settings.wakeMode == WAKE_CLAP_ONLY) { onWake(); return@LaunchedEffect }
            // Claps heard: now "uyan" (the recognizer itself gives up after ~8 s of silence).
            phase = Phase.PHRASE
            status = "Dinliyorum…"
            val stt = bridge.speechInput
            if (stt == null || !stt.isAvailable()) { onWake(); return@LaunchedEffect }
            val said = CompletableDeferred<String?>()
            stt.start(object : SpeechInput.Listener {
                override fun onPartial(text: String) { if (text.isNotBlank()) status = text }
                override fun onFinal(text: String) { said.complete(text) }
                override fun onError(message: String) { said.complete(null) }
            })
            // Generous: recording ends by itself; decoding on a phone can take several seconds.
            val text = withTimeoutOrNull(PHRASE_RESULT_MS) { said.await() }
            if (text == null) stt.cancel()
            heard = text.orEmpty()
            if (text != null && WakePhrase.matches(text)) { onWake(); return@LaunchedEffect }
            delay(300) // back to sleep, quietly
        }
    }

    val breath by rememberInfiniteTransition().animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(2_600, easing = LinearEasing), RepeatMode.Reverse),
    )
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures(onDoubleTap = { onWake() }) },
        contentAlignment = Alignment.Center,
    ) {
        if (io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi.face) {
            val listening = phase == Phase.PHRASE
            io.github.ahmetdurmus1218.jarvis.ui.components.RickFace(
                if (listening) io.github.ahmetdurmus1218.jarvis.ui.CoreMode.LISTENING else io.github.ahmetdurmus1218.jarvis.ui.CoreMode.IDLE,
                level, Modifier, size = 220.dp, sleeping = !listening,
            )
        } else Canvas(Modifier.size(220.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val listening = phase == Phase.PHRASE
            val r = (if (listening) 22.dp.toPx() else 7.dp.toPx()) * (0.85f + 0.15f * breath)
            drawCircle(Brush.radialGradient(listOf(JarvisColors.Cyan.copy(alpha = 0.5f * breath), Color.Transparent), center = c, radius = r * 4), radius = r * 4, center = c)
            drawCircle(JarvisColors.CyanBright.copy(alpha = 0.4f + 0.6f * breath), radius = r, center = c)
            if (listening) {
                val rr = 70.dp.toPx()
                drawArc(JarvisColors.Cyan, -90f, 360f * breath, false, topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2), style = Stroke(width = 2.dp.toPx()))
            } else if (level > 0.05f) {
                drawCircle(JarvisColors.Cyan.copy(alpha = 0.15f), radius = r * (1.5f + level * 3f), center = c, style = Stroke(width = 1.dp.toPx()))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val hint = when {
                phase == Phase.PHRASE -> "\"Uyan\" deyin · $status"
                micOk == false -> "Uyandırmak için ekrana iki kez dokunun."
                settings.clapRhythm != null -> "Ritminizi çalın · ya da iki kez dokunun"
                else -> "İki kez alkışlayın · ya da iki kez dokunun"
            }
            Text(hint, color = JarvisColors.TextDim.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            if (heard.isNotBlank() && phase == Phase.SLEEP) Text("duyulan: \"$heard\" · tekrar alkışlayıp \"uyan\" deyin", color = JarvisColors.TextDim.copy(alpha = 0.5f), fontSize = 10.sp)
        }
    }
}

private enum class Phase { SLEEP, PHRASE }

const val WAKE_CLAP_AND_PHRASE = "alkis+soz"
const val WAKE_CLAP_ONLY = "alkis"
/** Upper bound for one wake-word attempt: up to 8 s of waiting, the words, then decoding. */
private const val PHRASE_RESULT_MS = 40_000L

/**
 * Waking up: the rings draw one by one, real system checks appear, panels come back, and Rick
 * greets by the time of day.
 */
@Composable
fun WakeSequence(checks: List<Pair<String, String>>, greeting: String, onGreet: (String) -> Unit, onDone: () -> Unit) {
    var shown by remember { mutableStateOf(0) }
    var ring by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        for (i in 1..20) { ring = i / 20f; delay(30) }
        for (i in checks.indices) { shown = i + 1; delay(260) }
        onGreet(greeting)
        delay(900)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(JarvisColors.Background), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(260.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            for (k in 0 until 4) {
                val f = ((ring * 4f) - k).coerceIn(0f, 1f)
                if (f <= 0f) continue
                val r = size.minDimension / 2 * (0.45f + k * 0.17f)
                drawArc(JarvisColors.Cyan.copy(alpha = 0.9f - k * 0.18f), -90f + k * 30f, 360f * f, false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(width = (3 - k * 0.5f).dp.toPx()))
            }
            drawCircle(Brush.radialGradient(listOf(Color.White, JarvisColors.Cyan, Color.Transparent), center = c, radius = 60.dp.toPx() * ring), radius = 60.dp.toPx() * ring.coerceAtLeast(0.01f), center = c)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("R.I.C.K · SİSTEM KONTROLÜ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 2.sp)
            checks.take(shown).forEach { (k, v) ->
                Text("▸ $k: $v", color = JarvisColors.Text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

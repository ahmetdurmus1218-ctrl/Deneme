package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.formatBytes
import io.github.ahmetdurmus1218.jarvis.core.vision.FaceBox
import io.github.ahmetdurmus1218.jarvis.core.vision.Gesture
import io.github.ahmetdurmus1218.jarvis.core.vision.GestureClassifier
import io.github.ahmetdurmus1218.jarvis.core.vision.GestureTrigger
import io.github.ahmetdurmus1218.jarvis.core.vision.Hand
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.JarvisController
import io.github.ahmetdurmus1218.jarvis.ui.ModelInstaller
import io.github.ahmetdurmus1218.jarvis.ui.VisionCamera
import io.github.ahmetdurmus1218.jarvis.ui.VisionFrame
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** MediaPipe's hand skeleton: pairs of landmark indices joined by a bone. */
private val BONES = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
    9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
)
private val TIPS = listOf(4, 8, 12, 16, 20)

const val QUESTION_WHAT = "Bu ne? Gördüğünü kısaca anlat."
const val QUESTION_READ = "Buradaki yazıyı oku."

/**
 * Jarvis's eyes: the live camera with a HUD drawn over hands and faces, gestures as commands
 * (👍 listen, ✋ quiet, ✌️ what is this, ☝️ read), and "Bu ne?" / "Oku" through the vision model.
 * Faces are only located, never identified; frames never leave the phone.
 */
@Composable
fun CameraScreen(
    camera: VisionCamera,
    controller: JarvisController,
    installer: ModelInstaller,
    trackingItems: List<LockedItem>,
    onClose: () -> Unit,
) {
    var front by remember { mutableStateOf(true) }
    var frame by remember { mutableStateOf<VisionFrame?>(null) }
    var gesture by remember { mutableStateOf(Gesture.NONE) }
    var fired by remember { mutableStateOf<Gesture?>(null) }
    val trigger = remember { GestureTrigger() }
    val scope = rememberCoroutineScope()
    val mode by controller.mode.collectAsState()
    val turns by controller.turns.collectAsState()
    val partial by controller.partialTranscript.collectAsState()
    val installed by installer.installedVersion.collectAsState()
    val tracking = remember(installed) { camera.trackingReady() }

    fun ask(question: String) {
        scope.launch {
            val jpeg = camera.snapshotJpeg()
            if (jpeg == null) controller.showNotice("Kamera görüntüsü alınamadı.") else controller.askAboutImage(question, jpeg)
        }
    }

    LaunchedEffect(fired) {
        when (fired) {
            Gesture.THUMBS_UP -> controller.startListening()
            Gesture.OPEN_PALM -> controller.stopSpeaking()
            Gesture.VICTORY -> ask(QUESTION_WHAT)
            Gesture.POINTING -> ask(QUESTION_READ)
            else -> {}
        }
        fired = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        camera.Preview(Modifier.fillMaxSize(), front) { f ->
            frame = f
            val g = f.hands.firstOrNull()?.let(GestureClassifier::classify) ?: Gesture.NONE
            gesture = g
            trigger.feed(g, io.github.ahmetdurmus1218.jarvis.core.time.NowProvider.System.now().epochMillis)?.let { fired = it }
        }
        HudOverlay(frame, gesture, mode, Modifier.fillMaxSize())

        // Top bar
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("← GERİ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("J.A.R.V.I.S · GÖRÜŞ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, fontSize = 13.sp)
                Text(
                    "${frame?.hands?.size ?: 0} el · ${frame?.faces?.size ?: 0} yüz" + if (gesture != Gesture.NONE) " · ${gesture.label}" else "",
                    color = JarvisColors.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                )
            }
            TextButton(onClick = { front = !front }) { Text("⟲", color = JarvisColors.Cyan, fontSize = 20.sp) }
        }

        // Bottom: answer, gesture legend, buttons
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val last = turns.lastOrNull()
            val answer = partial.ifBlank { last?.scenes?.joinToString(" ") { it.sahne.ses }.orEmpty() }
            if (mode == CoreMode.THINKING) {
                Text("Bakıyorum…", color = JarvisColors.Violet, fontFamily = FontFamily.Monospace)
            } else if (answer.isNotBlank()) {
                Text(answer, color = JarvisColors.Text, fontSize = 17.sp, textAlign = TextAlign.Center)
            }
            if (!tracking) {
                TrackingOffer(installer, trackingItems)
            } else {
                Text(
                    "👍 dinle   ✋ sus   ✌️ bu ne?   ☝️ oku",
                    color = JarvisColors.TextDim, fontSize = 13.sp,
                    modifier = Modifier.border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { ask(QUESTION_WHAT) }, enabled = mode != CoreMode.THINKING) { Text("BU NE?") }
                OutlinedButton(onClick = { ask(QUESTION_READ) }, enabled = mode != CoreMode.THINKING) { Text("OKU", color = JarvisColors.Cyan) }
            }
        }
    }
}

@Composable
private fun TrackingOffer(installer: ModelInstaller, items: List<LockedItem>) {
    val busy by installer.busy.collectAsState()
    if (items.isEmpty()) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("El ve yüz takibi için küçük bir model paketi gerekiyor.", color = JarvisColors.TextDim, fontSize = 12.sp)
        OutlinedButton(enabled = !busy, onClick = {
            installer.installInBackground(items, "Kamera: el ve yüz takibi (MediaPipe). Görüntüler telefondan çıkmaz.")
        }) { Text(if (busy) "İndiriliyor…" else "El ve yüz takibini indir (${formatBytes(items.sumOf { it.size })})", color = JarvisColors.Cyan) }
    }
}

/** Maps 0..1 image coordinates onto the preview, which fills the screen and crops the rest. */
private class Mapper(w: Int, h: Int, canvas: Size) {
    private val scale = max(canvas.width / w, canvas.height / h)
    private val ox = (canvas.width - w * scale) / 2f
    private val oy = (canvas.height - h * scale) / 2f
    private val iw = w.toFloat()
    private val ih = h.toFloat()
    fun x(v: Float) = ox + v * iw * scale
    fun y(v: Float) = oy + v * ih * scale
}

@Composable
private fun HudOverlay(frame: VisionFrame?, gesture: Gesture, mode: CoreMode, modifier: Modifier) {
    val t by rememberInfiniteTransition().animateFloat(0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing)))
    Canvas(modifier) {
        val f = frame ?: return@Canvas
        if (f.width <= 0 || f.height <= 0) return@Canvas
        val m = Mapper(f.width, f.height, size)
        f.faces.forEach { drawFace(it, m, t) }
        f.hands.forEachIndexed { i, h -> drawHand(h, m, t, if (i == 0) gesture else Gesture.NONE, mode) }
    }
}

private fun DrawScope.drawHand(h: Hand, m: Mapper, t: Float, gesture: Gesture, mode: CoreMode) {
    val c = when (gesture) {
        Gesture.THUMBS_UP -> JarvisColors.Green
        Gesture.OPEN_PALM -> JarvisColors.Amber
        Gesture.VICTORY, Gesture.POINTING -> JarvisColors.Violet
        else -> if (mode == CoreMode.LISTENING) JarvisColors.Amber else JarvisColors.Cyan
    }
    val pts = h.points.map { Offset(m.x(it.x), m.y(it.y)) }
    val palmSize = (pts[0] - pts[9]).getDistance()
    // Bones: a wide soft glow under a thin bright line.
    for ((a, b) in BONES) {
        drawLine(c.copy(alpha = 0.25f), pts[a], pts[b], strokeWidth = palmSize * 0.16f, cap = StrokeCap.Round)
        drawLine(c.copy(alpha = 0.95f), pts[a], pts[b], strokeWidth = palmSize * 0.035f, cap = StrokeCap.Round)
    }
    pts.forEachIndexed { i, p -> if (i !in TIPS) drawCircle(Color.White.copy(alpha = 0.85f), radius = palmSize * 0.035f, center = p) }
    // Fingertips: pulsing energy orbs.
    TIPS.forEachIndexed { k, i ->
        val pulse = 0.75f + 0.25f * sin(t * 6f + k)
        val r = palmSize * 0.11f * pulse
        drawCircle(Brush.radialGradient(listOf(Color.White, c, Color.Transparent), center = pts[i], radius = r * 1.8f), radius = r * 1.8f, center = pts[i])
    }
    // Open palm: an energy ring turning around the palm centre.
    if (gesture == Gesture.OPEN_PALM) {
        val centre = Offset((pts[0].x + pts[9].x) / 2f, (pts[0].y + pts[9].y) / 2f)
        val r = palmSize * 0.9f
        for (k in 0 until 3) {
            drawArc(
                c.copy(alpha = 0.8f), startAngle = t * 120f + k * 120f, sweepAngle = 70f, useCenter = false,
                topLeft = Offset(centre.x - r, centre.y - r), size = Size(r * 2, r * 2), style = Stroke(width = palmSize * 0.05f, cap = StrokeCap.Round),
            )
        }
        for (k in 0 until 12) {
            val a = (t * 2f + k * PI.toFloat() / 6f)
            val rr = r * (1.15f + 0.15f * sin(t * 5f + k))
            drawCircle(c.copy(alpha = 0.7f), radius = palmSize * 0.025f, center = Offset(centre.x + rr * cos(a), centre.y + rr * sin(a)))
        }
    }
}

private fun DrawScope.drawFace(f: FaceBox, m: Mapper, t: Float) {
    val l = m.x(f.left)
    val r = m.x(f.right)
    val top = m.y(f.top)
    val b = m.y(f.bottom)
    // The front camera preview is mirrored, so left/right may arrive swapped.
    val left = minOf(l, r)
    val right = maxOf(l, r)
    val w = right - left
    val hgt = b - top
    val c = JarvisColors.Cyan
    val len = minOf(w, hgt) * 0.22f
    val sw = 3.dp.toPx()
    // Corner brackets
    drawLine(c, Offset(left, top), Offset(left + len, top), sw); drawLine(c, Offset(left, top), Offset(left, top + len), sw)
    drawLine(c, Offset(right, top), Offset(right - len, top), sw); drawLine(c, Offset(right, top), Offset(right, top + len), sw)
    drawLine(c, Offset(left, b), Offset(left + len, b), sw); drawLine(c, Offset(left, b), Offset(left, b - len), sw)
    drawLine(c, Offset(right, b), Offset(right - len, b), sw); drawLine(c, Offset(right, b), Offset(right, b - len), sw)
    // Scan line sweeping down the box
    val y = top + (t * 0.8f % 1f) * hgt
    drawLine(Brush.horizontalGradient(listOf(Color.Transparent, c.copy(alpha = 0.8f), Color.Transparent), startX = left, endX = right), Offset(left, y), Offset(right, y), sw * 0.7f)
    // Faint fill
    drawRect(c.copy(alpha = 0.06f), topLeft = Offset(left, top), size = Size(w, hgt))
}

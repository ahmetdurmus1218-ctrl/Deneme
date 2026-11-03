package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.formatBytes
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.vision.DemoHands
import io.github.ahmetdurmus1218.jarvis.core.vision.EffectSettings
import io.github.ahmetdurmus1218.jarvis.core.vision.FaceLock
import io.github.ahmetdurmus1218.jarvis.core.vision.Gesture
import io.github.ahmetdurmus1218.jarvis.core.vision.GestureClassifier
import io.github.ahmetdurmus1218.jarvis.core.vision.Hand
import io.github.ahmetdurmus1218.jarvis.core.vision.HandEffects
import io.github.ahmetdurmus1218.jarvis.core.vision.PlanetKind
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.JarvisController
import io.github.ahmetdurmus1218.jarvis.ui.ModelInstaller
import io.github.ahmetdurmus1218.jarvis.ui.SettingsStore
import io.github.ahmetdurmus1218.jarvis.ui.VisionCamera
import io.github.ahmetdurmus1218.jarvis.ui.VisionFrame
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/** MediaPipe's hand skeleton: pairs of landmark indices joined by a bone. */
private val BONES = listOf(
    0 to 1, 1 to 2, 2 to 3, 3 to 4, 0 to 5, 5 to 6, 6 to 7, 7 to 8, 5 to 9, 9 to 10, 10 to 11, 11 to 12,
    9 to 13, 13 to 14, 14 to 15, 15 to 16, 13 to 17, 0 to 17, 17 to 18, 18 to 19, 19 to 20,
)
private val TIPS = listOf(4, 8, 12, 16, 20)

const val QUESTION_WHAT = "Bu ne? Gördüğünü kısaca anlat."
const val QUESTION_READ = "Buradaki yazıyı oku."
const val QUESTION_FORTUNE = "Bu bir Türk kahvesi fincanı. Telvede gerçekten gördüğün şekillerden esprili, kısa bir kahve falı yorumla; bunun bilim olmadığını bir kez söyle."
const val THUMB_COFFEE = "Jarvis, bana bol köpüklü bir Türk kahvesi yap"
const val GESTURE_LEGEND = "✋ ateş · 🤏 gezegen · ✊→✋ patlat · ☝️ iz · ☝️☝️ şimşek · basılı tut: 👍 dinle ✌️ bu ne? ✊ sus"

/**
 * Hand control: the live camera (or a camera-less demo) with effects that follow the hands — fire,
 * seal rings, pinch planets, bursts, lightning, a light trail — hold-to-act controls (👍 listen,
 * ✌️ what is this, ✊ quiet), the face HUD lock, and "Bu ne?" / "Oku" / "Fal" through the vision
 * model. Faces are only located, never identified; frames never leave the device.
 */
@Composable
fun CameraScreen(
    camera: VisionCamera?,
    controller: JarvisController,
    installer: ModelInstaller,
    trackingItems: List<LockedItem>,
    settingsStore: SettingsStore,
    onClose: () -> Unit,
) {
    val settings by settingsStore.state.collectAsState()
    var front by remember { mutableStateOf(true) }
    var demo by remember { mutableStateOf(camera == null) }
    var panel by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<VisionFrame?>(null) }
    var faceLabels by remember { mutableStateOf<List<FaceLock.Label>>(emptyList()) }
    var demoTitle by remember { mutableStateOf("") }
    var canvasW by remember { mutableStateOf(1f) }
    var canvasH by remember { mutableStateOf(1f) }
    var tick by remember { mutableStateOf(0L) }
    var fps by remember { mutableStateOf(0) }
    val engine = remember { HandEffects() }
    val faceLock = remember { FaceLock() }
    val scope = rememberCoroutineScope()
    val mode by controller.mode.collectAsState()
    val turns by controller.turns.collectAsState()
    val partial by controller.partialTranscript.collectAsState()
    val installed by installer.installedVersion.collectAsState()
    val tracking = remember(installed) { camera?.trackingReady() == true }
    val text = rememberTextMeasurer()
    engine.settings = settings.effects

    fun ask(question: String) {
        val cam = camera ?: return controller.showNotice("Bu cihazda kamera yok: PC'de 📷 menüsünden ekran ya da resim gösterebilirsiniz.")
        scope.launch {
            val jpeg = cam.snapshotJpeg()
            if (jpeg == null) controller.showNotice("Kamera görüntüsü alınamadı.") else controller.askAboutImage(question, jpeg)
        }
    }

    // "Jarvis, Mars yap" / "el kontrolünü kapat"
    LaunchedEffect(Unit) {
        controller.appCommands.collect { c ->
            when (c) {
                is QuickCommand.ShowPlanet -> engine.requestPlanet(c.kind)
                is QuickCommand.HandControl -> if (!c.on) onClose()
                else -> {}
            }
        }
    }

    // One effects step per display frame, with the newest tracked hands (or the demo's).
    LaunchedEffect(demo) {
        // Frame time drives everything (deterministic under a test clock).
        var start = -1L
        var frames = 0
        var fpsAt = -1L
        while (isActive) {
            // UI tests refuse endless animations while their clock runs by itself (they step it by hand
            // to see effects); on a device this never throws.
            val now = try {
                withInfiniteAnimationFrameMillis { it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!isActive) throw e
                return@LaunchedEffect
            }
            if (start < 0) { start = now; fpsAt = now }
            val f = frame
            val fired: Gesture?
            if (demo) {
                val s = minOf(canvasW, canvasH)
                val (scene, hands) = DemoHands.at(now - start)
                demoTitle = scene.title
                engine.update(now, hands, s.toInt(), s.toInt())
                fired = null // the demo never sends commands
            } else {
                fired = engine.update(now, f?.hands.orEmpty(), f?.width ?: 1, f?.height ?: 1)
                if (settings.effects.faceLock && f != null) {
                    val (labels, greet) = faceLock.update(now, f.faces, settings.userName, busy = controller.mode.value != CoreMode.IDLE)
                    faceLabels = labels
                    if (greet) controller.speakTest(FaceLock.GREETING)
                } else {
                    faceLabels = emptyList()
                }
            }
            when (fired) {
                Gesture.THUMBS_UP -> if (settings.effects.thumbCoffee) controller.send(THUMB_COFFEE) else controller.startListening()
                Gesture.VICTORY -> ask(QUESTION_WHAT)
                Gesture.FIST -> controller.stopSpeaking()
                else -> {}
            }
            frames++
            if (now - fpsAt >= 1_000) { fps = frames; frames = 0; fpsAt = now }
            tick = now
        }
    }

    val clock by rememberInfiniteTransition().animateFloat(0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing)))

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!demo && camera != null) {
            camera.Preview(Modifier.fillMaxSize(), front) { f -> frame = f }
        } else {
            DemoBackdrop(Modifier.fillMaxSize(), clock)
        }
        val shakeX = if (engine.shake > 0f) sin(tick * 0.09f) * engine.shake * 18f else 0f
        val shakeY = if (engine.shake > 0f) sin(tick * 0.13f + 1f) * engine.shake * 12f else 0f
        Canvas(Modifier.fillMaxSize().graphicsLayer { translationX = shakeX; translationY = shakeY }) {
            canvasW = size.width
            canvasH = size.height
            if (tick < 0) return@Canvas // reading the frame tick redraws every frame
            val m = if (demo) {
                val s = minOf(size.width, size.height)
                ImageMapper(s, s, size, crop = false)
            } else {
                val f = frame ?: return@Canvas
                if (f.width <= 0 || f.height <= 0) return@Canvas
                ImageMapper(f.width.toFloat(), f.height.toFloat(), size, crop = true)
            }
            if (!demo) {
                val f = frame
                if (f != null) faceLabels.forEach { l ->
                    val x0 = m.x(l.face.left * f.width); val x1 = m.x(l.face.right * f.width)
                    drawFaceLabel(l, minOf(x0, x1), m.y(l.face.top * f.height), maxOf(x0, x1), m.y(l.face.bottom * f.height), clock, text)
                }
            }
            val hands = engine.lastHands
            val iw = if (demo) minOf(size.width, size.height) else (frame?.width ?: 1).toFloat()
            val ih = if (demo) minOf(size.width, size.height) else (frame?.height ?: 1).toFloat()
            hands.forEach { h -> drawHand(h, m, iw, ih, clock, mode) }
            drawEffects(engine, m, clock)
        }

        // Top bar
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("← GERİ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("J.A.R.V.I.S · EL KONTROLÜ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, fontSize = 13.sp)
                val g = frame?.hands?.firstOrNull()?.let(GestureClassifier::classify) ?: Gesture.NONE
                Text(
                    if (demo) "DEMO · $demoTitle · $fps FPS"
                    else "${frame?.hands?.size ?: 0} el · ${frame?.faces?.size ?: 0} yüz · $fps FPS" + if (g != Gesture.NONE) " · ${g.label}" else "",
                    color = JarvisColors.TextDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                )
            }
            TextButton(onClick = { panel = !panel }) { Text("⚙", color = JarvisColors.Cyan, fontSize = 20.sp) }
            if (camera != null) {
                TextButton(onClick = { demo = !demo }) { Text(if (demo) "KAMERA" else "DEMO", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                if (!demo) TextButton(onClick = { front = !front }) { Text("⟲", color = JarvisColors.Cyan, fontSize = 20.sp) }
            }
        }

        if (panel) {
            EffectsPanel(
                settings.effects, settings.userName,
                onChange = { e -> settingsStore.update { it.copy(effects = e) } },
                onName = { n -> settingsStore.update { it.copy(userName = n.take(24)) } },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 8.dp),
            )
        }

        // Bottom: answer, legend, buttons
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
            if (!demo && camera != null && !tracking) {
                TrackingOffer(installer, trackingItems)
            } else {
                Text(
                    GESTURE_LEGEND,
                    color = JarvisColors.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (camera != null && !demo) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { ask(QUESTION_WHAT) }, enabled = mode != CoreMode.THINKING) { Text("BU NE?") }
                    OutlinedButton(onClick = { ask(QUESTION_READ) }, enabled = mode != CoreMode.THINKING) { Text("OKU", color = JarvisColors.Cyan) }
                    OutlinedButton(onClick = { ask(QUESTION_FORTUNE) }, enabled = mode != CoreMode.THINKING) { Text("☕ FAL", color = JarvisColors.Amber) }
                }
            }
        }
    }
}

@Composable
private fun DemoBackdrop(modifier: Modifier, t: Float) {
    Canvas(modifier.background(JarvisColors.Background)) {
        val step = 48.dp.toPx()
        var x = (t * 20f) % step
        while (x < size.width) {
            drawLine(JarvisColors.Cyan.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
        }
        var y = 0f
        while (y < size.height) {
            drawLine(JarvisColors.Cyan.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
    }
}

@Composable
private fun EffectsPanel(e: EffectSettings, name: String, onChange: (EffectSettings) -> Unit, onName: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier.widthIn(max = 320.dp).background(JarvisColors.Surface.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
            .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(14.dp)).padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("EFEKTLER", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        @Composable
        fun row(label: String, on: Boolean, set: (Boolean) -> Unit) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = JarvisColors.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = set)
            }
        }
        row("✋ Ateş ve kıvılcım", e.fire) { onChange(e.copy(fire = it)) }
        row("✋✋ Mühür halkaları", e.seals) { onChange(e.copy(seals = it)) }
        row("🤏 Gezegen", e.planets) { onChange(e.copy(planets = it)) }
        row("✊→✋ Patlatma", e.burst) { onChange(e.copy(burst = it)) }
        row("☝️☝️ Şimşek", e.lightning) { onChange(e.copy(lightning = it)) }
        row("☝️ Işık izi", e.trail) { onChange(e.copy(trail = it)) }
        row("👍 ✌️ ✊ Jarvis kontrolleri", e.controls) { onChange(e.copy(controls = it)) }
        row("👍 basılı: Türk kahvesi yap (kapalıysa: dinle)", e.thumbCoffee) { onChange(e.copy(thumbCoffee = it)) }
        row("Yüz kilidi", e.faceLock) { onChange(e.copy(faceLock = it)) }
        Text("Gezegen", color = JarvisColors.TextDim, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip(e.fixedPlanet == null, "Sıradaki") { onChange(e.copy(fixedPlanet = null)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PlanetKind.entries.take(3).forEach { k -> Chip(e.fixedPlanet == k, k.label) { onChange(e.copy(fixedPlanet = k)) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PlanetKind.entries.drop(3).forEach { k -> Chip(e.fixedPlanet == k, k.label) { onChange(e.copy(fixedPlanet = k)) } }
        }
        OutlinedTextField(value = name, onValueChange = onName, singleLine = true, label = { Text("Adınız (yüz kilidi)") })
    }
}

@Composable
private fun Chip(selected: Boolean, label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 11.sp,
        color = if (selected) JarvisColors.Background else JarvisColors.Cyan,
        modifier = Modifier
            .background(if (selected) JarvisColors.Cyan else Color.Transparent, RoundedCornerShape(50))
            .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun DrawScope.drawHand(h: Hand, m: ImageMapper, iw: Float, ih: Float, t: Float, mode: CoreMode) {
    val c = if (mode == CoreMode.LISTENING) JarvisColors.Amber else JarvisColors.Cyan
    val pts = h.points.map { Offset(m.x(it.x * iw), m.y(it.y * ih)) }
    val palm = (pts[0] - pts[9]).getDistance()
    for ((a, b) in BONES) {
        drawLine(c.copy(alpha = 0.2f), pts[a], pts[b], strokeWidth = palm * 0.14f, cap = StrokeCap.Round)
        drawLine(c.copy(alpha = 0.9f), pts[a], pts[b], strokeWidth = palm * 0.03f, cap = StrokeCap.Round)
    }
    pts.forEachIndexed { i, p -> if (i !in TIPS) drawCircle(Color.White.copy(alpha = 0.8f), radius = palm * 0.03f, center = p) }
    TIPS.forEachIndexed { k, i ->
        val r = palm * 0.08f * (0.75f + 0.25f * sin(t * 6f + k))
        drawCircle(Brush.radialGradient(listOf(Color.White, c, Color.Transparent), center = pts[i], radius = r * 1.8f), radius = r * 1.8f, center = pts[i])
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


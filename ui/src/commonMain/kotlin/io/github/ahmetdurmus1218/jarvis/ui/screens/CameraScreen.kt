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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import io.github.ahmetdurmus1218.jarvis.ui.components.airTarget
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
const val THUMB_COFFEE = "Rick, bana bol köpüklü bir Türk kahvesi yap"
const val GESTURE_LEGEND = "✋ ateş · 🤏 gezegen · ✊→✋ patlat · ☝️ iz · ☝️☝️ şimşek · basılı tut: 👍 dinle ✌️ bu ne? ✊ sus"
const val AIR_LEGEND = "☝️ işaret et · 🤏 parmakları birleştirip aç = tıkla · 🤏↕ kaydır · 👋 salla = çık"

/**
 * Control mode: the hand only points and clicks — no fire, planets, bursts or held-shape actions
 * that would go off while the user is aiming at a button.
 */
val CONTROL_ONLY = EffectSettings(
    fire = false, seals = false, planets = false, burst = false, lightning = false, trail = false,
    controls = false, faceLock = false, air = true,
)


/**
 * Hand control with the live camera (or a camera-less demo), in two separate modes:
 * - KONTROL: the index finger is a cursor that snaps onto nearby buttons; 🤏 or a held ☝️ clicks,
 *   🤏 drag scrolls, ✋ swipe turns the answer cards, 👋 wave closes. Nothing else reacts.
 * - EFEKTLER: fire, seal rings, pinch planets, bursts, lightning, a light trail, hold-to-act
 *   controls (👍 listen, ✌️ what is this, ✊ quiet) and the face HUD lock; no cursor.
 * "Bu ne?" / "Oku" / "Fal" ask the vision model. Faces are only located, never identified;
 * frames never leave the device.
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
    // Air control: the hand as a cursor over everything below that is registered as a target.
    val targets = remember { io.github.ahmetdurmus1218.jarvis.ui.components.AirTargets() }
    val air = remember { io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer() }
    // Never stuck here either: 👋 a wave or ✋✋ both hands close the effects screen.
    val escape = remember { io.github.ahmetdurmus1218.jarvis.core.vision.HandNavigator() }
    var airState by remember { mutableStateOf<io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.State?>(null) }
    var airFlash by remember { mutableStateOf(0L) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var card by remember { mutableStateOf(0) }
    val answerScroll = rememberScrollState()
    val lastTurn = turns.lastOrNull()
    LaunchedEffect(lastTurn?.id) { card = 0; answerScroll.scrollTo(0) }

    fun showCard(i: Int) {
        val scenes = turns.lastOrNull()?.scenes.orEmpty()
        if (i !in scenes.indices || i == card) return
        card = i
        scope.launch { answerScroll.scrollTo(0) }
        controller.stopSpeaking()
        controller.speakTest(scenes[i].sahne.ses)
    }
    // Two clear modes: KONTROL (the hand is a pointer, nothing else happens) and EFEKTLER (the fun
    // effects, no cursor). Mixing them made every pinch a planet while the user aimed at a button.
    val control = settings.effects.air && !demo
    val fx = when {
        control -> CONTROL_ONLY
        settings.reduceMotion -> settings.effects.copy(fire = false, air = false)
        else -> settings.effects.copy(air = false)
    }
    engine.settings = fx
    val magnet = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }
    fun setControl(on: Boolean) = settingsStore.update { it.copy(effects = it.effects.copy(air = on)) }

    fun ask(question: String) {
        val cam = camera ?: return controller.showNotice("Bu cihazda kamera yok: PC'de 📷 menüsünden ekran ya da resim gösterebilirsiniz.")
        scope.launch {
            val jpeg = cam.snapshotJpeg()
            if (jpeg == null) controller.showNotice("Kamera görüntüsü alınamadı.") else controller.askAboutImage(question, jpeg)
        }
    }

    // "Rick, Mars yap" / "el kontrolünü kapat"
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
        var counted: Any? = null
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
                if (fx.faceLock && f != null) {
                    val (labels, greet) = faceLock.update(now, f.faces, settings.userName, busy = controller.mode.value != CoreMode.IDLE)
                    faceLabels = labels
                    if (greet) controller.speakTest(FaceLock.GREETING)
                } else {
                    faceLabels = emptyList()
                }
            }
            if (!demo && control && f != null && f.width > 0 && f.height > 0) {
                val px = f.hands.map { hd -> Hand(hd.points.map { io.github.ahmetdurmus1218.jarvis.core.vision.Point(it.x * canvasW, it.y * canvasH, it.z) }) }
                val esc = escape.update(now, px.firstOrNull(), canvasW, canvasH, second = px.getOrNull(1)).commands
                if (io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.EXIT in esc || io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.HOME in esc) onClose()
            }
            // The hand as a cursor (camera only: the demo hands must not click anything).
            if (!demo && control) {
                val h0 = f?.hands?.firstOrNull()
                val mapped = if (f != null && h0 != null && f.width > 0 && f.height > 0) {
                    val m = ImageMapper(f.width.toFloat(), f.height.toFloat(), androidx.compose.ui.geometry.Size(canvasW, canvasH), crop = true)
                    Hand(h0.points.map { io.github.ahmetdurmus1218.jarvis.core.vision.Point(m.x(it.x * f.width), m.y(it.y * f.height), it.z) })
                } else null
                val events = air.update(now, mapped, canvasW, canvasH) { x, y -> targets.near(origin.x + x, origin.y + y, magnet) != null }
                airState = air.state
                for (e in events) when (e) {
                    is io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.Event.Click ->
                        targets.near(origin.x + e.x, origin.y + e.y, magnet)?.let { hit -> airFlash = now; hit.action() }
                    is io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.Event.Scroll -> scope.launch { answerScroll.scrollBy(-e.dy) }
                    is io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.Event.Rotate -> engine.rotatePlanets(e.degrees)
                    is io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.Event.Swipe -> when (e.direction) {
                        io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.Direction.LEFT -> showCard(card + 1)
                        io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.Direction.RIGHT -> showCard(card - 1)
                        io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.Direction.UP -> scope.launch { answerScroll.animateScrollBy(canvasH * 0.25f) }
                        io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.Direction.DOWN -> scope.launch { answerScroll.animateScrollBy(-canvasH * 0.25f) }
                    }
                }
            } else {
                airState = null
            }
            when (fired) {
                Gesture.THUMBS_UP -> if (settings.effects.thumbCoffee) controller.send(THUMB_COFFEE) else controller.startListening()
                Gesture.VICTORY -> ask(QUESTION_WHAT)
                Gesture.FIST -> controller.stopSpeaking()
                else -> {}
            }
            // Camera frames that were really analysed (not screen refreshes): what hand tracking runs at.
            if (demo) frames++ else if (f != null && f !== counted) { counted = f; frames++ }
            if (now - fpsAt >= 1_000) { fps = frames; frames = 0; fpsAt = now }
            tick = now
        }
    }

    val clock by rememberInfiniteTransition().animateFloat(0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing)))

    Box(Modifier.fillMaxSize().background(Color.Black).onGloballyPositioned { origin = it.positionInRoot() }) {
        if (!demo && camera != null) {
            camera.Preview(Modifier.fillMaxSize(), front) { f -> frame = f }
        } else {
            DemoBackdrop(Modifier.fillMaxSize(), clock)
        }
        val shakeX = if (engine.shake > 0f && !settings.reduceMotion) sin(tick * 0.09f) * engine.shake * 18f else 0f
        val shakeY = if (engine.shake > 0f && !settings.reduceMotion) sin(tick * 0.13f + 1f) * engine.shake * 12f else 0f
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
            airState?.let { a ->
                // The cursor snaps onto the button it is near, so the user sees what a click will hit.
                val hit = targets.near(origin.x + a.x, origin.y + a.y, magnet)
                drawAirCursor(a, hit?.let { Offset(it.center.x - origin.x, it.center.y - origin.y) }, tick - airFlash < 300)
            }
        }

        // Top: buttons on one line, the title and one plain status line below. Nothing here may wrap
        // letter by letter, however narrow the phone or large the font.
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose, modifier = Modifier.airTarget(targets, onClose)) { Text("← GERİ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false) }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    // Effects (fire, planets…) are a separate, small switch: off, the hand only points and clicks.
                    if (!demo) ModeTab(if (control) "✨" else "✨ açık", !control, targets) { panel = false; setControl(!control) }
                    if (!control || demo) TextButton(onClick = { panel = !panel }, modifier = Modifier.airTarget(targets) { panel = !panel }) { Text("⚙", color = JarvisColors.Cyan, fontSize = 20.sp) }
                    if (camera != null) {
                        TextButton(onClick = { panel = false; demo = !demo }, modifier = Modifier.airTarget(targets) { panel = false; demo = !demo }) { Text(if (demo) "KAMERA" else "DEMO", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, softWrap = false) }
                        if (!demo) TextButton(onClick = { front = !front }, modifier = Modifier.airTarget(targets) { front = !front }) { Text("⟲", color = JarvisColors.Cyan, fontSize = 20.sp) }
                    }
                }
                Text("R.I.C.K · EL KONTROLÜ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, fontSize = 13.sp, maxLines = 1, softWrap = false)
                val seen = frame?.hands.orEmpty()
                val g = seen.firstOrNull()?.let(GestureClassifier::classify) ?: Gesture.NONE
                val status = when {
                    demo -> "DEMO · $demoTitle"
                    camera == null -> ""
                    !tracking -> "El takibi henüz kurulu değil"
                    seen.isEmpty() -> "El görünmüyor · elinizi kameraya gösterin"
                    else -> "● El görüldü" + (if (g != Gesture.NONE) " · ${g.label}" else "") + (if (fps > 0) " · $fps kare/sn" else "")
                }
                if (status.isNotBlank()) {
                    Text(
                        status, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = if (!demo && seen.isNotEmpty()) JarvisColors.Green else JarvisColors.TextDim, fontSize = 11.sp,
                    )
                }
            }
            if (panel) {
                // The settings cover the rest of the screen by themselves: nothing shows through or on top.
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).clickable { panel = false }.padding(8.dp)) {
                    EffectsPanel(
                        settings.effects, settings.userName,
                        onChange = { e -> settingsStore.update { it.copy(effects = e) } },
                        onName = { n -> settingsStore.update { it.copy(userName = n.take(24)) } },
                        onClose = { panel = false },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }

        // Bottom: answer, one hint, one row of buttons
        if (!panel) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val last = turns.lastOrNull()
            val scenes = last?.scenes.orEmpty()
            if (mode == CoreMode.THINKING) {
                Text("Bakıyorum…", color = JarvisColors.Violet, fontFamily = FontFamily.Monospace)
            } else if (partial.isNotBlank()) {
                Text(partial, color = JarvisColors.Text, fontSize = 17.sp, textAlign = TextAlign.Center)
            } else if (scenes.isNotEmpty()) {
                // The answer card by card: swipe, or click ‹ ›; pinch-drag scrolls a long one.
                val i = card.coerceIn(0, scenes.lastIndex)
                Box(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(answerScroll)) {
                    io.github.ahmetdurmus1218.jarvis.ui.components.SceneCard(scenes[i])
                }
                if (scenes.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AirChip("‹ önceki", targets, enabled = i > 0) { showCard(i - 1) }
                        Text("${i + 1} / ${scenes.size}", color = JarvisColors.TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        AirChip("sonraki ›", targets, enabled = i < scenes.lastIndex) { showCard(i + 1) }
                    }
                }
            }
            // Rick asked something ("… okuyayım mı?"): answer in the air.
            if (last?.offer != null && last.offerAnswer == null && last.offer !is io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.AskWhat) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val yes = { controller.answerOffer(last.id, true) }
                    val no = { controller.answerOffer(last.id, false) }
                    Button(onClick = yes, modifier = Modifier.airTarget(targets, yes)) { Text("Evet") }
                    OutlinedButton(onClick = no, modifier = Modifier.airTarget(targets, no)) { Text("Hayır", color = JarvisColors.Cyan) }
                }
            }
            if (!demo && camera != null && !tracking) {
                TrackingOffer(installer, trackingItems)
            } else if (demo || frame?.hands.isNullOrEmpty()) {
                // How it works, only while no hand is in the picture: with a hand up, the screen stays clear.
                Text(
                    if (!demo && control) AIR_LEGEND else GESTURE_LEGEND,
                    color = JarvisColors.TextDim, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            if (!demo) {
                // One row: talk (or quiet while Rick speaks) and what the camera can be asked.
                val idle = mode != CoreMode.THINKING
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (mode) {
                        CoreMode.SPEAKING -> AirChip("🤫 Sus", targets, accent = true) { controller.stopSpeaking() }
                        CoreMode.LISTENING -> AirChip("■ Dinliyorum", targets, accent = true) { controller.toggleListening() }
                        else -> AirChip("🎤 Konuş", targets, accent = true) { controller.toggleListening() }
                    }
                    if (camera != null) {
                        AirChip("BU NE?", targets, enabled = idle) { ask(QUESTION_WHAT) }
                        AirChip("OKU", targets, enabled = idle) { ask(QUESTION_READ) }
                        if (!control) AirChip("☕ FAL", targets, enabled = idle) { ask(QUESTION_FORTUNE) }
                    }
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
private fun EffectsPanel(e: EffectSettings, name: String, onChange: (EffectSettings) -> Unit, onName: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Column(
        modifier.widthIn(max = 340.dp).background(JarvisColors.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(14.dp))
            // Taps inside the panel are the panel's (the dimmed area around it closes it).
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("EFEKTLER", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Kapat ✕", color = JarvisColors.Cyan, fontSize = 13.sp) }
        }
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
        row("👍 ✌️ ✊ Rick kontrolleri", e.controls) { onChange(e.copy(controls = it)) }
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


/** A small button that also answers a click in the air. */
@Composable
private fun AirChip(label: String, targets: io.github.ahmetdurmus1218.jarvis.ui.components.AirTargets, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val act = { if (enabled) onClick() }
    Text(
        label, fontSize = 12.sp,
        color = if (!enabled) JarvisColors.TextDim else if (accent) JarvisColors.Background else JarvisColors.Cyan,
        modifier = Modifier
            .background(if (accent && enabled) JarvisColors.Cyan else Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
            .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .airTarget(targets, act)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/** The small ✨ effects switch in the top bar. */
@Composable
private fun ModeTab(label: String, selected: Boolean, targets: io.github.ahmetdurmus1218.jarvis.ui.components.AirTargets, onClick: () -> Unit) {
    Text(
        label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        color = if (selected) JarvisColors.Background else JarvisColors.Cyan,
        modifier = Modifier
            .background(if (selected) JarvisColors.Cyan else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .airTarget(targets, onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The air cursor: a ring on the fingertip, filling while it waits to click. Near a button it
 * jumps onto the button's middle ([snap]) and turns amber, so the user sees what a click will hit.
 */
private fun DrawScope.drawAirCursor(a: io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer.State, snap: Offset?, flash: Boolean) {
    val overTarget = snap != null
    val c = snap ?: Offset(a.x, a.y)
    val r = 22.dp.toPx()
    val color = if (overTarget) JarvisColors.Amber else JarvisColors.Cyan
    if (snap != null) drawLine(color.copy(alpha = 0.5f), Offset(a.x, a.y), snap, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    if (flash) drawCircle(color.copy(alpha = 0.45f), r * 1.8f, c)
    drawCircle(color.copy(alpha = 0.9f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
    if (a.dwell > 0f) {
        drawArc(color, -90f, 360f * a.dwell, false, topLeft = Offset(c.x - r * 1.3f, c.y - r * 1.3f), size = androidx.compose.ui.geometry.Size(r * 2.6f, r * 2.6f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    }
    drawCircle(color, if (a.pinching) r * 0.55f else r * 0.18f, c)
}

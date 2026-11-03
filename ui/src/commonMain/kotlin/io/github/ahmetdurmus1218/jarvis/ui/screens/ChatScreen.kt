package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.formatBytes
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.ItemStatus
import io.github.ahmetdurmus1218.jarvis.ui.ModelInstaller
import io.github.ahmetdurmus1218.jarvis.ui.JarvisController
import io.github.ahmetdurmus1218.jarvis.ui.Turn
import io.github.ahmetdurmus1218.jarvis.ui.components.HudCore
import io.github.ahmetdurmus1218.jarvis.ui.components.SceneCard
import io.github.ahmetdurmus1218.jarvis.ui.components.SourceChips
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import io.github.ahmetdurmus1218.jarvis.ui.components.accent
import io.github.ahmetdurmus1218.jarvis.ui.components.hudBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu

/** Jarvis's own speech model, offered in the chat when the phone's recognizer cannot do Turkish. */
class SpeechModelOffer(val items: List<LockedItem>, val installer: ModelInstaller)

/** Ways to show Jarvis something: the live camera (phone) or a screenshot / image file (PC). */
class VisionActions(
    val openCamera: (() -> Unit)?,
    val screenshot: suspend () -> ByteArray?,
    val pickImage: suspend () -> ByteArray?,
    val desktop: Boolean,
)

@Composable
fun ChatScreen(
    controller: JarvisController,
    engineLabel: String,
    micAvailable: Boolean,
    speechModel: SpeechModelOffer? = null,
    vision: VisionActions? = null,
    onHome: (() -> Unit)? = null,
    onLibrary: (() -> Unit)? = null,
) {
    val turns by controller.turns.collectAsState()
    val mode by controller.mode.collectAsState()
    val level by controller.level.collectAsState()
    val subtitle by controller.subtitle.collectAsState()
    val partial by controller.partialTranscript.collectAsState()
    val settings by controller.settings.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val accent by animateColorAsState(mode.accent(), tween(600))
    val focus = LocalFocusManager.current

    Column(Modifier.fillMaxSize().hudBackground(accent)) {
        StatusBar(
            mode = mode,
            conversationMode = settings.conversationMode,
            historyCount = turns.size,
            showHistory = showHistory,
            onConversationMode = { on -> controller.settings.update { it.copy(conversationMode = on) } },
            onHistory = { showHistory = !showHistory },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showHistory) {
                History(turns)
            } else {
                Stage(
                    mode = mode,
                    level = level,
                    engineLabel = engineLabel,
                    last = turns.lastOrNull(),
                    subtitle = subtitle,
                    onCoreTap = if (micAvailable) controller::toggleListening else null,
                )
            }
        }
        val caption = partial.ifBlank { if (mode == CoreMode.SPEAKING) "" else subtitle }
        if (caption.isNotBlank()) {
            Text(
                caption,
                textAlign = TextAlign.Center,
                color = JarvisColors.Text,
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }
        if (speechModel != null && partial.isNotBlank() && mode != CoreMode.LISTENING) {
            SpeechModelBar(speechModel) { ok ->
                if (ok) controller.showNotice("Ses tanıma hazır. Mikrofona dokunun ve konuşun; susunca Jarvis kendisi durur.")
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .background(JarvisColors.Glass, RoundedCornerShape(28.dp))
                .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(28.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Jarvis'e yazın…", color = JarvisColors.TextDim) },
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accent.copy(alpha = 0.7f),
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
            )
            if (vision != null) VisionButton(vision, input, controller) { input = "" }
            if (onHome != null) TextButton(onClick = onHome) { Text("🏠", fontSize = 20.sp) }
            if (onLibrary != null) TextButton(onClick = onLibrary) { Text("📚", fontSize = 20.sp) }
            if (mode == CoreMode.SPEAKING) TextButton(onClick = controller::stopSpeaking) { Text("Sus", color = JarvisColors.Amber) }
            Button(
                onClick = {
                    controller.send(input)
                    input = ""
                    focus.clearFocus() // closes the phone keyboard so the answer is visible
                },
                enabled = input.isNotBlank(),
                shape = RoundedCornerShape(22.dp),
            ) { Text("Gönder") }
            if (micAvailable) MicButton(mode == CoreMode.LISTENING, level, onTap = controller::toggleListening, onHoldStart = controller::startListening, onHoldEnd = controller::stopListening)
        }
    }
}

/**
 * 📷: camera / hand control, a photo (what is this, coffee-cup reading) and, on the PC, the screen.
 * A question typed in the input box is used, otherwise "what is this".
 */
@Composable
private fun VisionButton(vision: VisionActions, typed: String, controller: JarvisController, onUsed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    fun ask(defaultQuestion: String, source: suspend () -> ByteArray?) {
        scope.launch {
            val jpeg = source()
            if (jpeg == null) return@launch
            controller.askAboutImage(typed.ifBlank { defaultQuestion }, jpeg)
            onUsed()
        }
    }
    Box {
        TextButton(onClick = { menu = true }) {
            Text("📷", fontSize = 20.sp)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (vision.desktop) {
                DropdownMenuItem(text = { Text("Ekranı göster") }, onClick = { menu = false; ask("Ekranda ne var? Kısaca anlat.", vision.screenshot) })
            }
            DropdownMenuItem(text = { Text("Resim seç") }, onClick = { menu = false; ask(QUESTION_WHAT, vision.pickImage) })
            DropdownMenuItem(text = { Text("☕ Kahve falı (fincan fotoğrafı)") }, onClick = { menu = false; ask(QUESTION_FORTUNE, vision.pickImage) })
            vision.openCamera?.let { open -> DropdownMenuItem(text = { Text(if (vision.desktop) "✋ El kontrolü (demo)" else "Kamera · el kontrolü") }, onClick = { menu = false; open() }) }
        }
    }
}

/** Top line of the HUD: name, privacy state, conversation mode, history and the clock. */
@Composable
private fun StatusBar(
    mode: CoreMode,
    conversationMode: Boolean,
    historyCount: Int,
    showHistory: Boolean,
    onConversationMode: (Boolean) -> Unit,
    onHistory: () -> Unit,
) {
    val tick = rememberInfiniteTransition().animateFloat(0f, 60f, infiniteRepeatable(tween(60_000, easing = LinearEasing)))
    // Recomposes only when the minute changes, not on every animation frame.
    val minute by remember { derivedStateOf { (tick.value / 20f).toInt() } }
    val clock = remember(minute, mode) { NowProvider.System.now().hhmm() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("J.A.R.V.I.S", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontSize = 15.sp)
            Text("● ÇEVRİMDIŞI", color = JarvisColors.Green, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp, maxLines = 1)
        }
        HudChip(if (conversationMode) "SOHBET MODU ✓" else "SOHBET MODU", conversationMode) { onConversationMode(!conversationMode) }
        Spacer(Modifier.width(6.dp))
        HudChip(if (showHistory) "GERİ" else "GEÇMİŞ $historyCount", showHistory, onHistory)
        Spacer(Modifier.width(10.dp))
        Text(clock, color = JarvisColors.Text, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
    }
}

@Composable
private fun HudChip(text: String, active: Boolean, onClick: () -> Unit) {
    val c = if (active) JarvisColors.Amber else JarvisColors.CyanDim
    Text(
        text,
        color = if (active) JarvisColors.Amber else JarvisColors.TextDim,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        modifier = Modifier
            .border(1.dp, c, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

/**
 * The stage: the living core in the middle, its state below, then the latest exchange — what you
 * said, Jarvis's words as they are spoken (typewriter), and the answer's scene cards.
 */
@Composable
private fun Stage(mode: CoreMode, level: Float, engineLabel: String, last: Turn?, subtitle: String, onCoreTap: (() -> Unit)?) {
    val scroll = rememberScrollState()
    // Keep the newest answer in view (the keyboard or a small screen can push it below the fold).
    LaunchedEffect(last?.id, last?.scenes?.size, last?.done) {
        if (last != null) scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        val coreModifier = if (onCoreTap != null) Modifier.clip(CircleShape).clickable(onClick = onCoreTap) else Modifier
        // Large while waiting; smaller once there is an answer to read.
        val coreSize by animateDpAsState(if (last == null) 250.dp else 170.dp, tween(600))
        HudCore(mode, level, coreModifier, size = coreSize)
        Text(
            when (mode) {
                CoreMode.IDLE -> "HAZIR"
                CoreMode.LISTENING -> "DİNLİYORUM"
                CoreMode.THINKING -> "DÜŞÜNÜYORUM"
                CoreMode.SPEAKING -> "KONUŞUYOR"
            },
            color = mode.accent(), fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, fontSize = 13.sp,
        )
        Text("Model: $engineLabel", color = JarvisColors.TextDim, fontSize = 11.sp)
        if (last == null) {
            Greeting()
        } else {
            Text(
                "› ${last.user}",
                color = JarvisColors.TextDim,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (mode == CoreMode.SPEAKING && subtitle.isNotBlank()) {
                Typewriter(subtitle, Modifier.fillMaxWidth())
            }
            if (!last.done && last.scenes.isEmpty()) {
                Text(if (last.tools.isEmpty()) "…" else "Araçlar: ${last.tools.joinToString(", ")}", color = JarvisColors.TextDim, fontSize = 12.sp)
            }
            last.scenes.forEachIndexed { i, scene ->
                key(last.id, i) { Appear { SceneCard(scene) } }
            }
            last.detay?.takeIf { it.isNotBlank() && last.done }?.let {
                Text(it.replace("**", ""), color = JarvisColors.TextDim, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
            }
            SourceChips(last.kaynaklar)
            last.error?.let { Text("Hata: $it", color = JarvisColors.Red, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Greeting() {
    val hour = remember { NowProvider.System.now().hour }
    val hello = when (hour) {
        in 5..10 -> "Günaydın efendim."
        in 11..16 -> "İyi günler efendim."
        in 17..21 -> "İyi akşamlar efendim."
        else -> "İyi geceler efendim."
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Typewriter(hello, Modifier.fillMaxWidth())
        Text(
            "Çekirdeğe ya da mikrofona dokunun ve konuşun; isterseniz yazın.",
            color = JarvisColors.TextDim, fontSize = 13.sp, textAlign = TextAlign.Center,
        )
    }
}

/** Jarvis's words appearing as they are spoken. */
@Composable
private fun Typewriter(text: String, modifier: Modifier = Modifier) {
    var shown by remember(text) { mutableStateOf(0) }
    LaunchedEffect(text) {
        while (shown < text.length) {
            delay(22)
            shown++
        }
    }
    Text(
        text.take(shown),
        modifier = modifier,
        color = JarvisColors.Text,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Light,
        textAlign = TextAlign.Center,
    )
}

/** Fades and slides a card in when it first appears. */
@Composable
private fun Appear(content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(450)) }
    Box(Modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 40f }) { content() }
}

/** The whole conversation, newest at the bottom. */
@Composable
private fun History(turns: List<Turn>) {
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.scrollToItem(turns.size - 1) }
    if (turns.isEmpty()) {
        Text("Henüz konuşma yok.", color = JarvisColors.TextDim, modifier = Modifier.padding(24.dp))
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        items(turns, key = { it.id }) { TurnView(it) }
    }
}

/**
 * Tap: starts listening; the recognizer stops by itself when you stop talking (tap again to finish early).
 * Hold: listens while held (push-to-talk) and finishes on release.
 */
@Composable
private fun MicButton(active: Boolean, level: Float, onTap: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: () -> Unit) {
    val holdMs = 600L
    val ring by animateFloatAsState(if (active) 1f + 0.35f * level else 1f, tween(120))
    val color = if (active) JarvisColors.Amber else JarvisColors.Cyan
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(58.dp)) {
        Box(
            Modifier
                .size(58.dp)
                .graphicsLayer { scaleX = ring; scaleY = ring }
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent)), CircleShape),
        )
        Box(
            Modifier
                .size(48.dp)
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f))), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var endedEarly = false
                        var lifted = false
                        withTimeoutOrNull(holdMs) {
                            lifted = waitForUpOrCancellation() != null
                            endedEarly = true
                        }
                        if (endedEarly) {
                            if (lifted) onTap() // a finger that slid away is neither a tap nor a hold
                        } else {
                            onHoldStart()
                            waitForUpOrCancellation()
                            onHoldEnd()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (active) "■" else "🎤", fontSize = 21.sp, color = JarvisColors.Background)
        }
    }
}

@Composable
private fun SpeechModelBar(offer: SpeechModelOffer, onDone: (Boolean) -> Unit) {
    val status by offer.installer.status.collectAsState()
    val busy by offer.installer.busy.collectAsState()
    val states = offer.items.map { status[it.id] }
    val done = states.count { it is ItemStatus.Done }
    val active = states.firstOrNull { it is ItemStatus.Downloading } as? ItemStatus.Downloading
    val failed = states.firstOrNull { it is ItemStatus.Failed } as? ItemStatus.Failed
    Column(
        Modifier.fillMaxWidth().background(JarvisColors.Surface).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            active != null -> Text(
                "Whisper indiriliyor (${done + 1}/${offer.items.size}): ${formatBytes(active.bytes)} / ${formatBytes(active.total)}",
                color = JarvisColors.Cyan, fontSize = 13.sp,
            )
            busy -> Text("Whisper hazırlanıyor…", color = JarvisColors.Cyan, fontSize = 13.sp)
            else -> {
                failed?.let { Text(it.message, color = JarvisColors.Amber, fontSize = 12.sp) }
                Button(onClick = {
                    offer.installer.installInBackground(
                        offer.items,
                        "Mikrofon: Jarvis'in kendi Türkçe ses tanıması (Whisper small). Kurulduktan sonra ses tanıma tamamen internetsiz çalışır.",
                        onDone,
                    )
                }) { Text("Jarvis'in ses tanımasını indir (${formatBytes(offer.items.sumOf { it.size })})") }
            }
        }
    }
}

@Composable
private fun TurnView(turn: Turn) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                turn.user,
                modifier = Modifier.background(JarvisColors.CyanDim.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(10.dp),
            )
        }
        if (turn.tools.isNotEmpty()) {
            Text("Araçlar: ${turn.tools.joinToString(", ")}", color = JarvisColors.TextDim, fontSize = 11.sp)
        }
        turn.scenes.forEach { SceneCard(it) }
        if (!turn.done && turn.scenes.isEmpty()) Text("…", color = JarvisColors.TextDim)
        turn.detay?.takeIf { it.isNotBlank() }?.let {
            Text(it.replace("**", ""), color = JarvisColors.TextDim, fontSize = 13.sp)
        }
        SourceChips(turn.kaynaklar)
        if (turn.interrupted) Text("yarıda kesildi", color = JarvisColors.TextDim, fontSize = 11.sp)
        turn.error?.let { Text("Hata: $it", color = JarvisColors.Red, fontWeight = FontWeight.SemiBold) }
    }
}

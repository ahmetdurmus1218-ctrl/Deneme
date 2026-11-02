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

/** Jarvis's own speech model, offered in the chat when the phone's recognizer cannot do Turkish. */
class SpeechModelOffer(val items: List<LockedItem>, val installer: ModelInstaller)

@Composable
fun ChatScreen(controller: JarvisController, engineLabel: String, micAvailable: Boolean, speechModel: SpeechModelOffer? = null) {
    val turns by controller.turns.collectAsState()
    val mode by controller.mode.collectAsState()
    val level by controller.level.collectAsState()
    val subtitle by controller.subtitle.collectAsState()
    val partial by controller.partialTranscript.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(turns.size, turns.lastOrNull()?.scenes?.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size)
    }

    Column(Modifier.fillMaxSize().background(JarvisColors.Background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    HudCore(mode, level)
                    Text(
                        when (mode) {
                            CoreMode.IDLE -> "Hazır"
                            CoreMode.LISTENING -> "Dinliyorum…"
                            CoreMode.THINKING -> "Düşünüyorum…"
                            CoreMode.SPEAKING -> "Konuşuyorum"
                        },
                        color = JarvisColors.TextDim, fontSize = 13.sp,
                    )
                    Text("Model: $engineLabel", color = JarvisColors.TextDim, fontSize = 11.sp)
                }
            }
            items(turns, key = { it.id }) { TurnView(it) }
        }
        val caption = partial.ifBlank { subtitle }
        if (caption.isNotBlank()) {
            Text(
                caption,
                textAlign = TextAlign.Center,
                color = JarvisColors.Text,
                modifier = Modifier.fillMaxWidth().background(JarvisColors.Surface).padding(10.dp),
            )
        }
        if (speechModel != null && partial.isNotBlank() && mode != CoreMode.LISTENING) {
            SpeechModelBar(speechModel) { ok ->
                if (ok) controller.showNotice("Ses tanıma hazır. Mikrofona dokunun ve konuşun; susunca Jarvis kendisi durur.")
            }
        }
        Row(
            Modifier.fillMaxWidth().background(JarvisColors.Surface).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Jarvis'e yazın…") },
                singleLine = true,
            )
            Button(onClick = { controller.send(input); input = "" }, enabled = input.isNotBlank()) { Text("Gönder") }
            if (micAvailable) MicButton(mode == CoreMode.LISTENING, onTap = controller::toggleListening, onHoldStart = controller::startListening, onHoldEnd = controller::stopListening)
            if (mode == CoreMode.SPEAKING) TextButton(onClick = controller::stopSpeaking) { Text("Sus") }
        }
    }
}

/**
 * Tap: starts listening; the recognizer stops by itself when you stop talking (tap again to finish early).
 * Hold: listens while held (push-to-talk) and finishes on release.
 */
@Composable
private fun MicButton(active: Boolean, onTap: () -> Unit, onHoldStart: () -> Unit, onHoldEnd: () -> Unit) {
    val holdMs = 600L
    Box(
        Modifier
            .size(52.dp)
            .background(if (active) JarvisColors.Amber else JarvisColors.CyanDim, CircleShape)
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
        Text(if (active) "■" else "🎤", fontSize = 22.sp)
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

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
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.JarvisController
import io.github.ahmetdurmus1218.jarvis.ui.Turn
import io.github.ahmetdurmus1218.jarvis.ui.components.HudCore
import io.github.ahmetdurmus1218.jarvis.ui.components.SceneCard
import io.github.ahmetdurmus1218.jarvis.ui.components.SourceChips
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors

@Composable
fun ChatScreen(controller: JarvisController, engineLabel: String, micAvailable: Boolean) {
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
            if (micAvailable) PushToTalk(mode == CoreMode.LISTENING, onPress = controller::startListening, onRelease = controller::stopListening)
            if (mode == CoreMode.SPEAKING) TextButton(onClick = controller::stopSpeaking) { Text("Sus") }
        }
    }
}

@Composable
private fun PushToTalk(active: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .background(if (active) JarvisColors.Amber else JarvisColors.CyanDim, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onPress()
                    waitForUpOrCancellation()
                    onRelease()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("🎤", fontSize = 22.sp)
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
        turn.error?.let { Text("Hata: $it", color = JarvisColors.Red, fontWeight = FontWeight.SemiBold) }
    }
}

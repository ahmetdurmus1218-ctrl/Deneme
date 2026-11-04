package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.ahmetdurmus1218.jarvis.desktop.llm.DesktopLlm
import io.github.ahmetdurmus1218.jarvis.ui.AppGraph
import io.github.ahmetdurmus1218.jarvis.ui.JarvisApp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.pointer.pointerInput
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun main() {
    val version = DesktopBridge::class.java.`package`?.implementationVersion ?: System.getProperty("jarvis.version") ?: "dev"
    val bridge = DesktopBridge(version)
    val llm = DesktopLlm(bridge.models, File(dataDir(), "bin"))
    // One conversation shared by the main window and the floating Rick.
    val graph = AppGraph(
        bridge, realEngine = { g -> llm.pick(g.registry, g.settings.state.value.llmId) },
        appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main),
    )
    // First start of the installed app: block Rick's programs in the Windows firewall (one UAC prompt).
    kotlin.concurrent.thread(isDaemon = true, name = "jarvis-firewall") { kotlinx.coroutines.runBlocking { runCatching { bridge.firstRunFirewall() } } }
    val fmt = SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.forLanguageTag("tr-TR"))
    application {
        val mainState = rememberWindowState(width = 480.dp, height = 860.dp)
        var mainWindow by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<java.awt.Window?>(null) }
        val floatingOn by DesktopFloating.running.collectAsState()
        if (floatingOn) {
            val controller = graph.sharedController
            if (controller != null) {
                Window(
                    onCloseRequest = { DesktopFloating.stop(); graph.settings.update { it.copy(bubble = false) } },
                    title = "Rick (ekranda)",
                    undecorated = true,
                    transparent = true,
                    alwaysOnTop = true,
                    resizable = false,
                    state = rememberWindowState(
                        width = 150.dp, height = 190.dp,
                        position = androidx.compose.ui.window.WindowPosition(androidx.compose.ui.Alignment.BottomEnd),
                    ),
                ) {
                    FloatingFace(controller) {
                        mainState.isMinimized = false
                        mainWindow?.toFront()
                    }
                }
            }
        }
        Window(
            onCloseRequest = {
                graph.bridge.speechOutput?.stop()
                llm.close()
                bridge.zim.close()
                exitApplication()
            },
            title = "Rick",
            state = mainState,
        ) {
            androidx.compose.runtime.SideEffect { mainWindow = window }
            JarvisApp(graph) { fmt.format(Date(it)) }
        }
    }
}

/** The face in the floating window: drag to move, click to talk, double-click for Rick's window. */
@androidx.compose.runtime.Composable
private fun androidx.compose.ui.window.FrameWindowScope.FloatingFace(controller: io.github.ahmetdurmus1218.jarvis.ui.JarvisController, openMain: () -> Unit) {
    val mode by controller.mode.collectAsState()
    val level by controller.level.collectAsState()
    val subtitle by controller.subtitle.collectAsState()
    androidx.compose.foundation.window.WindowDraggableArea {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            io.github.ahmetdurmus1218.jarvis.ui.components.RickFace(
                mode, level,
                androidx.compose.ui.Modifier.pointerInput(Unit) {
                    androidx.compose.foundation.gestures.detectTapGestures(
                        onTap = { controller.toggleListening() },
                        onDoubleTap = { openMain() },
                    )
                },
                size = 130.dp,
            )
            if (mode == io.github.ahmetdurmus1218.jarvis.ui.CoreMode.SPEAKING && subtitle.isNotBlank()) {
                androidx.compose.material3.Text(
                    subtitle, maxLines = 2, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    color = io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors.Text,
                    modifier = androidx.compose.ui.Modifier
                        .background(io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors.Surface.copy(alpha = 0.9f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(4.dp),
                )
            }
        }
    }
}

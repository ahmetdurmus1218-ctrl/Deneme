package io.github.ahmetdurmus1218.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant
import io.github.ahmetdurmus1218.jarvis.core.assistant.DefaultTools
import io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import io.github.ahmetdurmus1218.jarvis.core.memory.InMemoryMemoryStore
import io.github.ahmetdurmus1218.jarvis.core.memory.MemoryStore
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import io.github.ahmetdurmus1218.jarvis.ui.screens.ApprovalScreen
import io.github.ahmetdurmus1218.jarvis.ui.screens.ChatScreen
import io.github.ahmetdurmus1218.jarvis.ui.screens.MemoryScreen
import io.github.ahmetdurmus1218.jarvis.ui.screens.ModelsScreen
import io.github.ahmetdurmus1218.jarvis.ui.screens.NetworkLogScreen
import io.github.ahmetdurmus1218.jarvis.ui.screens.SettingsScreen
import io.github.ahmetdurmus1218.jarvis.ui.screens.WizardScreen
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisTheme

private enum class Tab(val label: String, val icon: String) {
    CHAT("Sohbet", "💬"),
    MODELS("Modeller", "📦"),
    MEMORY("Hafıza", "🧠"),
    NETLOG("Ağ", "🛡"),
    SETTINGS("Ayarlar", "⚙"),
}

/** Long-lived objects for one app process. */
class AppGraph(val bridge: PlatformBridge, engine: LlmEngine = MockLlmEngine()) {
    val registry: ModelRegistry = ModelRegistry.bundled
    val settings = SettingsStore(bridge.files)
    val netLog = NetworkLogStore(bridge.files)
    val memory: MemoryStore = InMemoryMemoryStore { NowProvider.System.now().epochMillis }
    val assistant = Assistant(engine, DefaultTools.create(memory), memory)
    val installer = ModelInstaller(bridge, registry, settings, netLog)
    val engineLabel: String = if (engine.id == "mock") "sahte model (v0.1)" else engine.id
}

@Composable
fun JarvisApp(graph: AppGraph, formatTime: (Long) -> String) {
    JarvisTheme {
        val scope = rememberCoroutineScope()
        val controller = remember { JarvisController(scope, graph.bridge, graph.assistant, graph.memory, graph.settings) }
        val settings by graph.settings.state.collectAsState()
        val approval by graph.installer.approval.collectAsState()
        val netEvents by graph.netLog.events.collectAsState()
        var tab by remember { mutableStateOf(Tab.CHAT) }
        var wizard by remember { mutableStateOf(!settings.wizardDone) }

        LaunchedEffect(settings.memoryEnabled) { graph.memory.setEnabled(settings.memoryEnabled) }
        LaunchedEffect(Unit) { graph.bridge.downloader.refresh() }

        // Edge-to-edge on Android: keep everything clear of the status bar, navigation bar and keyboard.
        Box(Modifier.fillMaxSize().background(JarvisColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (wizard) {
                WizardScreen(graph.bridge, graph.registry, graph.installer, controller) {
                    graph.settings.update { it.copy(wizardDone = true) }
                    wizard = false
                }
            } else {
                Scaffold(
                    containerColor = JarvisColors.Background,
                    bottomBar = {
                        NavigationBar(containerColor = JarvisColors.Surface) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Text(t.icon, fontSize = 18.sp) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding)) {
                        when (tab) {
                            Tab.CHAT -> ChatScreen(controller, graph.engineLabel, graph.bridge.speechInput?.isAvailable() == true)
                            Tab.MODELS -> ModelsScreen(graph.bridge, graph.registry, graph.installer)
                            Tab.MEMORY -> MemoryScreen(graph.memory, graph.settings)
                            Tab.NETLOG -> NetworkLogScreen(netEvents, formatTime)
                            Tab.SETTINGS -> SettingsScreen(graph.bridge, graph.settings, graph.registry) { wizard = true }
                        }
                    }
                }
            }
            approval?.let { req ->
                val authLabel = when (graph.bridge.platform) {
                    io.github.ahmetdurmus1218.jarvis.core.models.Platform.ANDROID -> "parmak izi / yüz ya da ekran kilidi PIN'i"
                    io.github.ahmetdurmus1218.jarvis.core.models.Platform.DESKTOP -> "ikinci bir onay"
                }
                ApprovalScreen(req, authLabel) { graph.installer.answerApproval(it) }
            }
        }
    }
}

package io.github.ahmetdurmus1218.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant
import io.github.ahmetdurmus1218.jarvis.core.assistant.DefaultTools
import io.github.ahmetdurmus1218.jarvis.core.knowledge.DocumentIndex
import io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore
import io.github.ahmetdurmus1218.jarvis.core.knowledge.Wikipedia
import io.github.ahmetdurmus1218.jarvis.core.llm.EngineSelector
import io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import io.github.ahmetdurmus1218.jarvis.core.memory.PersistentMemoryStore
import io.github.ahmetdurmus1218.jarvis.core.memory.MemoryStore
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import kotlinx.coroutines.launch
import io.github.ahmetdurmus1218.jarvis.ui.screens.ApprovalScreen
import io.github.ahmetdurmus1218.jarvis.ui.screens.CameraScreen
import io.github.ahmetdurmus1218.jarvis.ui.screens.HomePanel
import io.github.ahmetdurmus1218.jarvis.ui.screens.LibraryPanel
import io.github.ahmetdurmus1218.jarvis.ui.screens.SleepScreen
import io.github.ahmetdurmus1218.jarvis.ui.screens.WakeSequence
import io.github.ahmetdurmus1218.jarvis.core.stt.WakePhrase
import io.github.ahmetdurmus1218.jarvis.ui.screens.notice
import io.github.ahmetdurmus1218.jarvis.ui.screens.VisionActions
import io.github.ahmetdurmus1218.jarvis.ui.screens.SpeechModelOffer
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
/**
 * Long-lived objects for one app process.
 * [realEngine] returns the platform's real model when one is installed (LiteRT-LM on Android,
 * llama-server on Windows); until then the mock answers.
 */
class AppGraph(
    val bridge: PlatformBridge,
    fallback: LlmEngine = MockLlmEngine(),
    realEngine: ((AppGraph) -> LlmEngine?)? = null,
) {
    val registry: ModelRegistry = ModelRegistry.bundled
    /** Everything the app writes, sealed with the platform's key (plain only where no cipher exists, e.g. tests). */
    val files: AppFiles = bridge.cipher?.let { EncryptedFiles(bridge.files, it) } ?: bridge.files
    val settings = SettingsStore(files)
    val netLog = NetworkLogStore(files)
    val memory: MemoryStore = PersistentMemoryStore(
        object : TextStore {
            override fun read(name: String) = files.read(name)
            override fun write(name: String, text: String) = files.write(name, text)
        },
    ) { NowProvider.System.now().epochMillis }
    val engine = EngineSelector(fallback) { realEngine?.invoke(this) }
    private val textStore = object : TextStore {
        override fun read(name: String) = files.read(name)
        override fun write(name: String, text: String) = files.write(name, text)
    }
    /** The user's books (kitaplık), sealed like the rest of the app's data. */
    val library = io.github.ahmetdurmus1218.jarvis.core.library.Library(textStore) { NowProvider.System.now().epochMillis }
    /** The simulated home (lights, coffee machines) behind the home voice commands and the Ev panel. */
    val home = io.github.ahmetdurmus1218.jarvis.core.home.SmartHome()
    /** The user's own documents (PDF/TXT/MD), searched with BM25 by belge_ara. */
    val documents = DocumentIndex(
        object : TextStore {
            override fun read(name: String) = files.read(name)
            override fun write(name: String, text: String) = files.write(name, text)
        },
    ) { NowProvider.System.now().epochMillis }
    val assistant = Assistant(
        engine,
        DefaultTools.create(
            memory,
            wiki = bridge.zim?.let { Wikipedia(it) },
            documents = documents,
            library = library,
            // Device actions for the model; the PC has no alarms or app launching.
            extra = if (bridge.platform == io.github.ahmetdurmus1218.jarvis.core.models.Platform.ANDROID) {
                io.github.ahmetdurmus1218.jarvis.core.tools.ActionTools { bridge.execute(it) }.all()
            } else {
                emptyList()
            },
        ),
        memory,
        home = home,
        library = library,
    )
    val installer = ModelInstaller(bridge, registry, settings, netLog)
    val engineLabel: String get() = engine.id.let { if (it == "mock") "sahte model (gerçek model kurulu değil)" else it }
}

@Composable
fun JarvisApp(graph: AppGraph, formatTime: (Long) -> String) {
    JarvisTheme {
        val scope = rememberCoroutineScope()
        val controller = remember { JarvisController(scope, graph.bridge, graph.assistant, graph.memory, graph.settings) { graph.engine.current.takeIf { it.id != "mock" } } }
        val settings by graph.settings.state.collectAsState()
        val approval by graph.installer.approval.collectAsState()
        val netEvents by graph.netLog.events.collectAsState()
        var tab by remember { mutableStateOf(Tab.CHAT) }
        var wizard by remember { mutableStateOf(!settings.wizardDone) }
        var cameraOpen by remember { mutableStateOf(false) }
        // "Jarvis, el kontrolünü aç" (closing is handled by the camera screen itself).
        var homeOpen by remember { mutableStateOf(false) }
        var libraryOpen by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        var sleeping by remember { mutableStateOf(false) }
        var waking by remember { mutableStateOf(false) }
        val turnsNow by controller.turns.collectAsState()
        val modeNow by controller.mode.collectAsState()
        // Sleep after a while without use (any question, answer, tab or panel restarts the wait).
        LaunchedEffect(turnsNow.size, modeNow, tab, cameraOpen, homeOpen, sleeping, waking, wizard, settings.sleepAfterMin) {
            if (sleeping || waking || wizard || settings.sleepAfterMin <= 0) return@LaunchedEffect
            kotlinx.coroutines.delay(settings.sleepAfterMin * 60_000L)
            if (controller.mode.value == CoreMode.IDLE && !cameraOpen && approval == null) sleeping = true
        }
        LaunchedEffect(sleeping) { graph.bridge.keepScreenOn(sleeping) }
        LaunchedEffect(controller) {
            controller.appCommands.collect { c ->
                when (c) {
                    is io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.HandControl -> if (c.on) cameraOpen = true
                    is io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.Home -> c.action.notice()?.let { notice = it }
                    io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.Sleep -> scope.launch {
                        kotlinx.coroutines.delay(3_500) // let it finish saying so
                        sleeping = true
                    }
                    else -> {}
                }
            }
        }
        val learned by controller.learned.collectAsState()
        LaunchedEffect(learned) { if (learned.isNotEmpty()) notice = "🧠 HAFIZAYA EKLENDİ · " + learned.joinToString(" · ") }
        LaunchedEffect(notice) {
            if (notice != null) { kotlinx.coroutines.delay(3_500); notice = null }
        }

        LaunchedEffect(settings.memoryEnabled) { graph.memory.setEnabled(settings.memoryEnabled) }
        LaunchedEffect(Unit) { graph.bridge.downloader.refresh() }

        // Edge-to-edge on Android: keep everything clear of the status bar, navigation bar and keyboard.
        Box(Modifier.fillMaxSize().background(JarvisColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            val camera = graph.bridge.camera
            if (cameraOpen) {
                CameraScreen(
                    camera, controller, graph.installer,
                    trackingItems = camera?.let { c -> graph.registry.forPlatform(graph.bridge.platform).filter { it.group == c.trackingGroup } }.orEmpty(),
                    settingsStore = graph.settings,
                    onClose = { cameraOpen = false },
                )
            } else if (wizard) {
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
                            Tab.CHAT -> {
                                // Re-read after every install, so the offer disappears once Whisper is in place.
                                val installed by graph.installer.installedVersion.collectAsState()
                                val stt = graph.bridge.speechInput
                                val offer = remember(installed, stt) {
                                    stt?.ownModelGroup?.let { g ->
                                        graph.registry.forPlatform(graph.bridge.platform).filter { it.group == g }
                                            .takeIf { it.isNotEmpty() }?.let { SpeechModelOffer(it, graph.installer) }
                                    }
                                }
                                val vision = VisionActions(
                                    // The phone opens its camera; the PC opens hand control as a demo.
                                    openCamera = { cameraOpen = true },
                                    screenshot = { graph.bridge.screenshotJpeg() },
                                    pickImage = { graph.bridge.pickImageJpeg() },
                                    // The PC has no live camera: it shows the screen or an image instead.
                                    desktop = graph.bridge.platform == io.github.ahmetdurmus1218.jarvis.core.models.Platform.DESKTOP,
                                )
                                ChatScreen(controller, graph.engineLabel, stt != null, offer, vision, onHome = { homeOpen = true }, onLibrary = { libraryOpen = true })
                            }
                            Tab.MODELS -> ModelsScreen(graph.bridge, graph.registry, graph.installer)
                            Tab.MEMORY -> MemoryScreen(graph.memory, graph.settings, graph.documents, graph.bridge)
                            Tab.NETLOG -> NetworkLogScreen(netEvents, formatTime)
                            Tab.SETTINGS -> SettingsScreen(graph.bridge, graph.settings, graph.registry, graph.installer) { wizard = true }
                        }
                    }
                }
            }
            if (homeOpen) HomePanel(graph.home) { homeOpen = false }
            if (libraryOpen) LibraryPanel(graph.library, graph.assistant, pickPhoto = { graph.bridge.pickImageJpeg() }) { libraryOpen = false }
            if (sleeping) {
                SleepScreen(graph.bridge, settings) { sleeping = false; waking = true }
            } else if (waking) {
                val checks = remember {
                    listOf(
                        "Beyin" to graph.engineLabel,
                        "Ses tanıma" to (graph.bridge.speechInput?.engineName ?: "yok"),
                        "Ses" to (graph.bridge.speechOutput?.engineName ?: "yok"),
                        "Wikipedia" to (if (graph.bridge.zim?.isReady() == true) "hazır" else "kurulu değil"),
                        "Belgeler" to "${graph.documents.list().size} belge",
                        "Ev" to "simülasyon · 2 ışık, 2 kahve makinesi",
                        "Ağ" to "kapalı · her şey bu cihazda",
                    )
                }
                val hour = remember { NowProvider.System.now().hour }
                WakeSequence(checks, WakePhrase.greeting(hour), onGreet = { controller.speakTest(it) }) { waking = false }
            }
            notice?.let { n ->
                Text(
                    if (n.startsWith("🧠")) n else "🏠 $n", color = JarvisColors.Amber, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
                        .background(JarvisColors.Surface.copy(alpha = 0.92f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .border(1.dp, JarvisColors.Amber.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
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

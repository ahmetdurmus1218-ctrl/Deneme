package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.AssistantName
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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
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
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi

private enum class Tab(val label: String, val icon: io.github.ahmetdurmus1218.jarvis.ui.components.RickIcon) {
    CHAT("Sohbet", io.github.ahmetdurmus1218.jarvis.ui.components.RickIcon.CHAT),
    MODELS("Modeller", io.github.ahmetdurmus1218.jarvis.ui.components.RickIcon.CUBE),
    MEMORY("Hafıza", io.github.ahmetdurmus1218.jarvis.ui.components.RickIcon.MEMORY),
    NETLOG("Ağ", io.github.ahmetdurmus1218.jarvis.ui.components.RickIcon.SHIELD),
    SETTINGS("Ayarlar", io.github.ahmetdurmus1218.jarvis.ui.components.RickIcon.GEAR),
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
    /**
     * A scope that lives as long as the app process (Android): the conversation is then shared by
     * the window and the floating Rick. Null: the window owns it (desktop, tests).
     */
    private val appScope: kotlinx.coroutines.CoroutineScope? = null,
) {
    fun newController(scope: kotlinx.coroutines.CoroutineScope) = JarvisController(
        scope, bridge, assistant, memory, settings,
        factModel = { engine.current.takeIf { it.id != "mock" } },
        learning = learning,
        research = { recent -> researchOnce(recent) },
        netLog = netLog,
    )

    /** The one conversation of the app, when [appScope] is given. */
    val sharedController: JarvisController? by lazy { appScope?.let { newController(it) } }

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
    /** The user's to-do list ("not al: …", "yapılacaklarım neler"). */
    val todos = io.github.ahmetdurmus1218.jarvis.core.todo.TodoList(textStore) { NowProvider.System.now().epochMillis }
    /** Self-improvement: lessons from corrections / likes and the idle research notebook (sealed). */
    val learning = io.github.ahmetdurmus1218.jarvis.core.learning.Learning(textStore) { NowProvider.System.now().epochMillis }
    /** The offline Wikipedia, when its package is installed. */
    val wiki: Wikipedia? = bridge.zim?.let { Wikipedia(it) }
    private val researcher = wiki?.let { io.github.ahmetdurmus1218.jarvis.core.learning.Researcher(it, learning) }

    /**
     * One idle research step: the first new topic among the user's interests (memory, books, recent
     * questions) is looked up in the offline Wikipedia and its opening kept with the title.
     */
    suspend fun researchOnce(recentQuestions: List<String>): io.github.ahmetdurmus1218.jarvis.core.learning.ResearchNote? {
        val r = researcher ?: return null
        if (!settings.state.value.selfLearning || wiki?.isReady() != true) return null
        val facts = if (memory.enabled()) memory.list().map { it.text } else emptyList()
        val topic = r.topics(facts, library.books().map { it.title }, recentQuestions).firstOrNull() ?: return null
        return r.research(topic)
    }

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
            wiki = wiki,
            documents = documents,
            library = library,
            // Device actions for the model (alarm, timer, opening apps) on both platforms.
            extra = io.github.ahmetdurmus1218.jarvis.core.tools.ActionTools { bridge.execute(it) }.all(),
        ),
        memory,
        home = home,
        library = library,
        todos = todos,
        briefing = {
            val now = NowProvider.System.now()
            io.github.ahmetdurmus1218.jarvis.core.briefing.Briefing.Input(
                now = now,
                place = io.github.ahmetdurmus1218.jarvis.core.time.Places.byName(settings.state.value.city),
                todos = todos.items(),
                reading = library.books().filter { it.status == io.github.ahmetdurmus1218.jarvis.core.library.BookStatus.READING },
                home = home.state(now.epochMillis),
                facts = memory.list().size,
                userName = settings.state.value.userName,
                readTitles = learning.notesSince(now.epochMillis - 24 * 3_600_000L).map { it.title },
            )
        },
        learning = { learning.takeIf { settings.state.value.selfLearning } },
        // Offer a Wikipedia web search only when a gate exists here and the user has not turned it off.
        webOffer = { webReady() },
        canOffer = { cmd -> bridge.platform == io.github.ahmetdurmus1218.jarvis.core.models.Platform.ANDROID || !phoneOnly(cmd) },
        // Opening a search in the browser is the browser going online, not Rick; still not in full offline mode.
        browserOffer = { !settings.state.value.fullOffline },
    )

    /** A gate is there and the user allows Rick to offer online lookups (Wikipedia, the news). */
    fun webReady(): Boolean {
        val s = settings.state.value
        return s.webOffer && !s.fullOffline && bridge.web != null && bridge.downloader.state.value is DownloadState.Ready
    }

    /** Things only a phone can do (the PC would only answer that it cannot). */
    private fun phoneOnly(cmd: io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand): Boolean {
        val a = (cmd as? io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.Device)?.action ?: return false
        return a is io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.Torch ||
            a is io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.Call ||
            a is io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.Message ||
            a is io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.Navigate ||
            a == io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.Volume(io.github.ahmetdurmus1218.jarvis.core.device.VolumeChange.SILENT_PHONE)
    }
    val installer = ModelInstaller(bridge, registry, settings, netLog)
    val engineLabel: String get() = engine.id.let { if (it == "mock") "temel komutlar · dil modeli kurulu değil" else it }

    /** The recommended language model (and on the PC its llama.cpp) while none is installed; null once one is. */
    fun brainItems(): List<LockedItem>? {
        if (engine.current.id != "mock") return null
        val p = bridge.platform
        val llm = registry.recommendedBundle(p, bridge.deviceInfo().totalRamGb).filter { it.kind == "llm" }
        return registry.withRuntime(llm, p).filterNot { bridge.models.isInstalled(it) }.takeIf { it.isNotEmpty() }
    }
}

/** Mark 2 around the chat: real numbers refreshed every few seconds. */
@Composable
private fun Mark2(graph: AppGraph, city: String, content: @Composable () -> Unit) {
    var now by remember { mutableStateOf(NowProvider.System.now()) }
    var stats by remember { mutableStateOf<io.github.ahmetdurmus1218.jarvis.ui.screens.HudStats?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            now = NowProvider.System.now()
            val home = graph.home.state(now.epochMillis)
            stats = io.github.ahmetdurmus1218.jarvis.ui.screens.HudStats(
                brain = graph.engineLabel,
                voice = graph.bridge.speechOutput?.engineName ?: "yok",
                wiki = graph.bridge.zim?.isReady() == true,
                facts = graph.memory.list().size,
                documents = graph.documents.list().size,
                books = graph.library.books().size,
                lightsOn = home.lights.count { it.on },
                coffee = when {
                    home.turkish.state == io.github.ahmetdurmus1218.jarvis.core.home.Brew.BREWING -> "Türk kahvesi pişiyor"
                    home.turkish.state == io.github.ahmetdurmus1218.jarvis.core.home.Brew.READY -> "Türk kahvesi hazır"
                    home.filter.state == io.github.ahmetdurmus1218.jarvis.core.home.Brew.BREWING -> "filtre demleniyor"
                    home.filter.state == io.github.ahmetdurmus1218.jarvis.core.home.Brew.READY -> "filtre hazır"
                    else -> "boşta"
                },
                battery = graph.bridge.batteryPercent(),
                memoryMb = graph.bridge.memoryUsedMb(),
            )
            kotlinx.coroutines.delay(2_000)
        }
    }
    val s = stats
    if (s == null) content() else io.github.ahmetdurmus1218.jarvis.ui.screens.Mark2Frame(now, city, s, content)
}

@Composable
fun JarvisApp(graph: AppGraph, formatTime: (Long) -> String) {
    val themeSettings by graph.settings.state.collectAsState()
    JarvisTheme(themeSettings.theme, themeSettings.fontScale, themeSettings.reduceMotion) {
        val scope = rememberCoroutineScope()
        val controller = graph.sharedController ?: remember { graph.newController(scope) }
        val settings by graph.settings.state.collectAsState()
        val approval by graph.installer.approval.collectAsState()
        val netEvents by graph.netLog.events.collectAsState()
        var tab by remember { mutableStateOf(Tab.CHAT) }
        var wizard by remember { mutableStateOf(!settings.wizardDone) }
        var cameraOpen by remember { mutableStateOf(false) }
        // "Rick, el kontrolünü aç" (closing is handled by the camera screen itself).
        var homeOpen by remember { mutableStateOf(false) }
        var libraryOpen by remember { mutableStateOf(false) }
        var todoOpen by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        var sleeping by remember { mutableStateOf(false) }
        var waking by remember { mutableStateOf(false) }
        // Never stuck: one step back (voice "geri", ✌️ held, the phone's back button) …
        fun goBack(): Boolean = when {
            approval != null -> { graph.installer.answerApproval(false); true }
            cameraOpen -> { cameraOpen = false; true }
            homeOpen -> { homeOpen = false; true }
            libraryOpen -> { libraryOpen = false; true }
            todoOpen -> { todoOpen = false; true }
            sleeping -> { sleeping = false; true }
            controller.mode.value == CoreMode.LISTENING || controller.mode.value == CoreMode.SPEAKING -> { controller.stopEverything(); true }
            tab != Tab.CHAT -> { tab = Tab.CHAT; true }
            else -> false
        }
        // … or out of everything at once ("ana ekran", 👋 a wave, ✋✋).
        fun closeAll() {
            if (approval != null) graph.installer.answerApproval(false)
            cameraOpen = false
            homeOpen = false
            libraryOpen = false
            todoOpen = false
            sleeping = false
            tab = Tab.CHAT
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi.backHandler = { goBack() }
            onDispose { io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi.backHandler = null }
        }
        val turnsNow by controller.turns.collectAsState()
        val modeNow by controller.mode.collectAsState()
        // Sleep after a while without use (any question, answer, tab or panel restarts the wait).
        LaunchedEffect(turnsNow.size, modeNow, tab, cameraOpen, homeOpen, sleeping, waking, wizard, settings.sleepAfterMin) {
            if (sleeping || waking || wizard || settings.sleepAfterMin <= 0) return@LaunchedEffect
            kotlinx.coroutines.delay(settings.sleepAfterMin * 60_000L)
            if (controller.mode.value == CoreMode.IDLE && !cameraOpen && approval == null) sleeping = true
        }
        LaunchedEffect(sleeping) { graph.bridge.keepScreenOn(sleeping) }
        // Hands-free standby only in normal use: not asleep (the sleep screen listens itself), waking or in the wizard.
        val awake = !sleeping && !waking && !wizard
        androidx.compose.runtime.DisposableEffect(awake) {
            controller.setAppAwake(awake)
            onDispose {}
        }
        LaunchedEffect(controller) {
            controller.appCommands.collect { c ->
                when (c) {
                    is io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.HandControl -> if (c.on) cameraOpen = true
                    is io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.Home -> c.action.notice()?.let { notice = it }
                    io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.Back -> goBack()
                    io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.MainScreen -> closeAll()
                    is io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.Page -> io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi.pageScroll?.let { sc ->
                        scope.launch { sc.animateScrollBy(c.dir * sc.viewportSize * 0.7f) }
                    }
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
        // The floating Rick comes back with the app when the user left it on.
        LaunchedEffect(settings.bubble) {
            val f = graph.bridge.floating ?: return@LaunchedEffect
            if (settings.bubble && f.allowed() && !f.running.value) f.start(settings.bubbleHands)
        }
        LaunchedEffect(settings.face) { io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi.face = settings.face }
        LaunchedEffect(settings.look) { JarvisUi.look = settings.look }
        // The voice chosen in Settings (man / woman, Rick's own or the phone's).
        LaunchedEffect(settings.voiceId) { graph.bridge.speechOutput?.choose(settings.voiceId) }
        LaunchedEffect(Unit) { graph.bridge.downloader.refresh() }
        // Load the language model and the speech recognizer as soon as the app is open (and again
        // after the wizard installed them), so the first question is answered without a long wait.
        LaunchedEffect(wizard) {
            if (wizard) return@LaunchedEffect
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { graph.engine.warmUp() }
                runCatching { graph.bridge.speechInput?.warmUp() }
            }
        }

        // Edge-to-edge on Android: keep everything clear of the status bar, navigation bar and keyboard.
        // ☰ → El ile yönet: the hands drive the app (not over the effects screen, the wizard or sleep).
        val handCam = graph.bridge.camera
        val handNavOn = settings.handNav && handCam != null && !cameraOpen && !wizard && !sleeping && !waking
        val handFull = handNavOn && settings.handNavFull
        val handState = remember { io.github.ahmetdurmus1218.jarvis.ui.screens.HandNavState() }
        SideEffect { JarvisUi.seeThrough = handFull }
        Box(Modifier.fillMaxSize().background(JarvisColors.Background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (handFull && handCam != null) io.github.ahmetdurmus1218.jarvis.ui.screens.HandNavBackdrop(handCam, handState)
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
                    containerColor = JarvisUi.page,
                    bottomBar = {
                        // A tab's name is never broken over two lines ("Modell / er"): the phone's large-text setting
                        // makes the names bigger only as far as the longest one ("Modeller") still fits its tab.
                        androidx.compose.foundation.layout.BoxWithConstraints {
                        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                        val nameScale = minOf(fontScale, maxWidth / Tab.entries.size / 64.dp)
                        NavigationBar(containerColor = JarvisColors.Surface) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = {
                                        io.github.ahmetdurmus1218.jarvis.ui.components.RickIconView(
                                            t.icon, if (tab == t) JarvisColors.Cyan else JarvisColors.TextDim, size = 24.dp,
                                        )
                                    },
                                    label = {
                                        Text(
                                            t.label, fontSize = (12f * nameScale / fontScale).sp,
                                            maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                    },
                                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                        indicatorColor = JarvisColors.Cyan.copy(alpha = 0.14f),
                                        selectedTextColor = JarvisColors.Text,
                                        unselectedTextColor = JarvisColors.TextDim,
                                    ),
                                )
                            }
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
                                val tts = graph.bridge.speechOutput
                                val voiceOffer = remember(installed, tts) {
                                    tts?.ownModelGroup?.let { g ->
                                        graph.registry.forPlatform(graph.bridge.platform).filter { it.group == g }
                                            .takeIf { it.isNotEmpty() }?.let {
                                                SpeechModelOffer(
                                                    it, graph.installer, what = "Ses",
                                                    reason = "Ses: ${AssistantName.SHORT}'in kendi Türkçe sesi (Piper). Kurulduktan sonra cevaplar internetsiz, sesli okunur.",
                                                    button = "🔊 ${AssistantName.SHORT}'in sesini indir",
                                                )
                                            }
                                    }
                                }
                                val brainOffer = remember(installed) {
                                    graph.brainItems()?.let {
                                        SpeechModelOffer(
                                            it, graph.installer, what = "Dil modeli",
                                            reason = "Dil modeli: ${AssistantName.SHORT}'in düşünen beyni (${it.first().title}). Kurulduktan sonra her soruya internetsiz cevap verir.",
                                            button = "🧠 ${AssistantName.SHORT}'in beynini kur",
                                        )
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
                                val chat: @Composable () -> Unit = {
                                    ChatScreen(
                                        controller, graph.engineLabel, stt != null, offer, vision, voiceOffer, brainOffer,
                                        menu = listOf(
                                            "☀ Günlük brifing" to { controller.send("Rick, brifing ver") },
                                            "✅ Yapılacaklar" to { todoOpen = true },
                                            "🏠 Ev" to { homeOpen = true },
                                            "📚 Kitaplık" to { libraryOpen = true },
                                            (if (settings.handNav) "🖐 El ile yönetimi kapat" else "🖐 El ile yönet (kamera)") to {
                                                val cam = graph.bridge.camera
                                                when {
                                                    settings.handNav -> graph.settings.update { it.copy(handNav = false) }
                                                    cam == null -> controller.showNotice("Bu cihazda kamera yok.")
                                                    !cam.trackingReady() -> {
                                                        controller.showNotice("Önce el takibini indirin (8 MB); sonra ☰ → El ile yönet.")
                                                        cameraOpen = true
                                                    }
                                                    else -> graph.settings.update { it.copy(handNav = true) }
                                                }
                                            },
                                            "✋ El kontrolü (efektler)" to { cameraOpen = true },
                                            "🌙 Uyku modu" to { sleeping = true },
                                        ),
                                    )
                                }
                                if (settings.hudMark == 2) Mark2(graph, settings.city, chat) else chat()
                            }
                            Tab.MODELS -> ModelsScreen(graph.bridge, graph.registry, graph.installer)
                            Tab.MEMORY -> MemoryScreen(graph.memory, graph.settings, graph.documents, graph.bridge, graph.learning)
                            Tab.NETLOG -> NetworkLogScreen(netEvents, formatTime)
                            Tab.SETTINGS -> SettingsScreen(graph.bridge, graph.settings, graph.registry, graph.installer) { wizard = true }
                        }
                    }
                }
            }
            if (homeOpen) HomePanel(graph.home) { homeOpen = false }
            if (todoOpen) io.github.ahmetdurmus1218.jarvis.ui.screens.TodoPanel(graph.todos) { todoOpen = false }
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
                WakeSequence(checks, WakePhrase.greeting(hour), onGreet = { g -> if (graph.webReady()) controller.offerNews(g) else controller.speakTest(g) }) { waking = false }
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
        
            if (handNavOn && handCam != null) {
                io.github.ahmetdurmus1218.jarvis.ui.screens.HandNavOverlay(
                    handCam, controller, graph.bridge, handState, full = handFull,
                    onFull = { on -> graph.settings.update { it.copy(handNavFull = on) } },
                ) {
                    graph.settings.update { it.copy(handNav = false) }
                }
            }
        }
    }
}

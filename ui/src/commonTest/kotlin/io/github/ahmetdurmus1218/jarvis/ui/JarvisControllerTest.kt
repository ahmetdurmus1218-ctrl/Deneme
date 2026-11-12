package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant
import io.github.ahmetdurmus1218.jarvis.core.assistant.DefaultTools
import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.GenerationConfig
import io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.memory.InMemoryMemoryStore
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.time.Now
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JarvisControllerTest {
    private val now = NowProvider { Now(2026, 11, 2, 14, 5, 1, "Europe/Istanbul", 0L) }

    private class Files : AppFiles {
        val m = mutableMapOf<String, String>()
        override fun read(name: String) = m[name]
        override fun write(name: String, text: String) { m[name] = text }
        override fun append(name: String, line: String) { m[name] = m[name].orEmpty() + line + "\n" }
    }

    private class Tts : SpeechOutput {
        val spoken = mutableListOf<String>()
        var stops = 0
        override val engineName = "test"
        override suspend fun speak(text: String, rate: Float) { spoken += text }
        override fun stop() { stops++ }
    }

    /** Answers each start() with the next scripted utterance; null = "heard nothing". */
    private class ScriptedStt(vararg said: String?) : SpeechInput {
        private val script = ArrayDeque(said.toList())
        var starts = 0
        override val engineName = "script"
        override fun isAvailable() = true
        override fun start(listener: SpeechInput.Listener) {
            starts++
            val next = if (script.isEmpty()) null else script.removeFirst()
            if (next == null) listener.onError("Bir şey duyamadım.") else listener.onFinal(next)
        }
        override fun stop() {}
        override fun cancel() {}
    }

    /** A microphone the test speaks into: 16 kHz samples handed to whoever started it. */
    private class FakeMic : MicStream {
        private var sink: ((FloatArray, Int) -> Unit)? = null
        var starts = 0
        val open: Boolean get() = sink != null
        override suspend fun start(onSamples: (FloatArray, Int) -> Unit): Boolean {
            starts++
            sink = onSamples
            return true
        }
        override fun stop() { sink = null }

        fun hear(vararg parts: FloatArray) {
            for (part in parts) {
                var p = 0
                while (p < part.size) {
                    val n = minOf(1_600, part.size - p)
                    sink?.invoke(part.copyOfRange(p, p + n), n)
                    p += n
                }
            }
        }
    }

    private val rnd = kotlin.random.Random(11)
    private fun quiet(ms: Int) = FloatArray(ms * 16) { (rnd.nextFloat() - 0.5f) * 0.01f }
    private fun voice(ms: Int) = FloatArray(ms * 16) { i -> 0.3f * kotlin.math.sin(2 * kotlin.math.PI * 170 * i / 16_000).toFloat() }
    private fun clap() = FloatArray(70 * 16) { i -> (rnd.nextFloat() - 0.5f) * 1.2f * kotlin.math.exp(-i / (16_000 * 0.015f)) }

    /** Rick's own recognizer: standby hands it what was said ([heard]); the microphone button gets [said]. */
    private class OwnStt(val heard: ArrayDeque<String>, vararg said: String?) : SpeechInput {
        private val script = ArrayDeque(said.toList())
        var starts = 0
        var decoded = 0
        override val engineName = "own"
        override fun isAvailable() = true
        override val canTranscribe = true
        override suspend fun transcribe(samples: FloatArray): String? {
            decoded++
            return heard.removeFirstOrNull()
        }
        override fun start(listener: SpeechInput.Listener) {
            starts++
            val next = if (script.isEmpty()) null else script.removeFirst()
            if (next == null) listener.onError("Bir şey duyamadım. Mikrofona dokunup konuşun.") else listener.onFinal(next)
        }
        override fun stop() {}
        override fun cancel() {}
    }

    private class Bridge(val tts: Tts, val stt: SpeechInput? = null, val mic: MicStream? = null) : PlatformBridge {
        override val micStream: MicStream? get() = mic
        val executed = mutableListOf<QuickCommand>()
        override val platform = Platform.ANDROID
        override val appVersion = "test"
        override fun deviceInfo() = DeviceInfo("test", 8.0, 1L shl 40)
        override val files: AppFiles = Files()
        override val speechInput: SpeechInput? = stt
        override val speechOutput: SpeechOutput = tts
        override val approver = object : Approver {
            override suspend fun confirm(title: String, subtitle: String) = ApprovalResult.Approved(ApprovalMethod.DIALOG)
        }
        override val downloader = object : Downloader {
            override val state: StateFlow<DownloadState> = MutableStateFlow(DownloadState.Unavailable)
            override fun refresh() {}
            override suspend fun download(item: LockedItem, onProgress: (DownloadProgress) -> Unit) = DownloadOutcome.Failed("yok")
            override fun cancel() {}
        }
        override val models = object : ModelStorage {
            override fun isInstalled(item: LockedItem) = false
            override fun pathOf(item: LockedItem) = ""
            override fun commit(item: LockedItem, tempPath: String) {}
            override fun delete(item: LockedItem) {}
            override fun deleteTemp(tempPath: String) {}
            override fun importedUnverifiedFiles() = emptyList<String>()
            override fun keepUnverified(tempPath: String, fileName: String) {}
            override suspend fun pickAndImport(): ImportOutcome = ImportOutcome.Cancelled
        }
        override suspend fun execute(command: QuickCommand): String? {
            executed += command
            return if (command is QuickCommand.OpenApp) "\"${command.appName}\" adında bir uygulama bulamadım" else null
        }
    }

    /**
     * Foreground scope on the test scheduler (backgroundScope work is not run by advanceUntilIdle).
     * It has its own Job so the endless speech-queue consumer does not keep runTest waiting.
     */
    private fun TestScope.controllerScope(): CoroutineScope = CoroutineScope(coroutineContext + Job())

    private fun setup(engine: LlmEngine = MockLlmEngine(tokenDelayMs = 0), scope: CoroutineScope, stt: SpeechInput? = null, mic: MicStream? = null): Triple<JarvisController, Bridge, Tts> {
        val tts = Tts()
        val bridge = Bridge(tts, stt, mic)
        val memory = InMemoryMemoryStore()
        val assistant = Assistant(engine, DefaultTools.create(memory, now), memory, now)
        return Triple(JarvisController(scope, bridge, assistant, memory, SettingsStore(bridge.files)), bridge, tts)
    }

    private class MemText : io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore {
        val m = mutableMapOf<String, String>()
        override fun read(name: String) = m[name]
        override fun write(name: String, text: String) { m[name] = text }
    }

    private fun learningSetup(
        scope: CoroutineScope,
        research: (suspend (List<String>) -> io.github.ahmetdurmus1218.jarvis.core.learning.ResearchNote?)? = null,
    ): Pair<JarvisController, io.github.ahmetdurmus1218.jarvis.core.learning.Learning> {
        val bridge = Bridge(Tts(), null)
        val memory = InMemoryMemoryStore()
        val learning = io.github.ahmetdurmus1218.jarvis.core.learning.Learning(MemText())
        val assistant = Assistant(MockLlmEngine(tokenDelayMs = 0), DefaultTools.create(memory, now), memory, now, learning = { learning })
        return JarvisController(scope, bridge, assistant, memory, SettingsStore(bridge.files), learning = learning, research = research) to learning
    }

    @Test
    fun aCorrectionIsLearnedInsteadOfAskedAsANewQuestion() = runTest {
        val scope = controllerScope()
        val (c, learning) = learningSetup(scope)
        c.send("En sevdiğim renk ne?")
        advanceUntilIdle()
        c.send("Hayır, doğrusu lacivert")
        advanceUntilIdle()
        assertEquals(2, c.turns.value.size)
        assertTrue("öğrendim" in c.turns.value.last().scenes.single().sahne.ses)
        assertEquals("Lacivert", learning.lessons().single().correction)
        assertEquals(-1, c.turns.value.first().feedback)
        // Next time the same question is answered from what was taught, even without a model.
        c.send("en sevdiğim renk ne")
        advanceUntilIdle()
        assertEquals("Bana öğrettiğiniz gibi: Lacivert", c.turns.value.last().scenes.single().sahne.ses)
        scope.cancel()
    }

    @Test
    fun thumbsDownMakesTheNextMessageTheRightAnswer() = runTest {
        val scope = controllerScope()
        val (c, learning) = learningSetup(scope)
        c.send("Kızımın okulu hangisi?")
        advanceUntilIdle()
        c.feedback(c.turns.value.last().id, good = false)
        c.send("Atatürk İlkokulu")
        advanceUntilIdle()
        assertEquals("Atatürk İlkokulu", learning.lessons().single().correction)
        // 👍 keeps a liked example.
        c.send("saat kaç")
        advanceUntilIdle()
        c.feedback(c.turns.value.last().id, good = true)
        assertTrue(learning.lessons().any { it.liked && it.question == "saat kaç" })
        assertEquals(1, c.turns.value.last().feedback)
        scope.cancel()
    }

    @Test
    fun idleReadingStartsOnlyAfterAQuietWhile() = runTest {
        val scope = controllerScope()
        var steps = 0
        val (c, _) = learningSetup(scope) { steps++; io.github.ahmetdurmus1218.jarvis.core.learning.ResearchNote(1, "Manisa", "Manisa", "Manisa", "Manisa bir şehirdir.") }
        advanceTimeBy(JarvisController.IDLE_CHECK_MS * 2 + 1)
        assertEquals(0, steps, "not yet: Rick was just used")
        advanceTimeBy(JarvisController.IDLE_CHECK_MS * 2)
        assertTrue(steps >= 1)
        assertEquals("📖 Boştayken okudum: Manisa", c.partialTranscript.value)
        c.settings.update { it.copy(selfLearning = false) }
        val before = steps
        advanceTimeBy(JarvisController.IDLE_CHECK_MS * 5)
        assertEquals(before, steps, "switched off: no reading")
        scope.cancel()
    }

    @Test
    fun conversationModeListensAgainUntilSilence() = runTest {
        val scope = controllerScope()
        val stt = ScriptedStt("saat kaç", "bugün ayın kaçı", null)
        val (c, _, tts) = setup(scope = scope, stt = stt)
        c.settings.update { it.copy(conversationMode = true) }
        c.startListening()
        advanceUntilIdle()
        // Two spoken questions answered hands-free, then silence ends the conversation.
        assertEquals(3, stt.starts)
        assertEquals(2, c.turns.value.size)
        assertEquals(2, tts.spoken.size)
        assertEquals(CoreMode.IDLE, c.mode.value)
        scope.cancel()
    }

    @Test
    fun withoutConversationModeTheMicStaysOff() = runTest {
        val scope = controllerScope()
        val stt = ScriptedStt("saat kaç", "bugün ayın kaçı")
        val (c, _, _) = setup(scope = scope, stt = stt)
        c.startListening()
        advanceUntilIdle()
        assertEquals(1, stt.starts)
        assertEquals(1, c.turns.value.size)
        scope.cancel()
    }

    @Test
    fun susEndsTheConversation() = runTest {
        val scope = controllerScope()
        val stt = ScriptedStt("tamam sus", "saat kaç")
        val (c, _, _) = setup(scope = scope, stt = stt)
        c.settings.update { it.copy(conversationMode = true) }
        c.startListening()
        advanceUntilIdle()
        assertEquals(1, stt.starts)
        scope.cancel()
    }

    @Test
    fun typedQuestionsDoNotOpenTheMic() = runTest {
        val scope = controllerScope()
        val stt = ScriptedStt("saat kaç")
        val (c, _, _) = setup(scope = scope, stt = stt)
        c.settings.update { it.copy(conversationMode = true) }
        c.send("Saat kaç?")
        advanceUntilIdle()
        assertEquals(0, stt.starts)
        scope.cancel()
    }

    @Test
    fun quickCommandIsExecutedAndSpoken() = runTest {
        val scope = controllerScope()
        val (c, bridge, tts) = setup(scope = scope)
        c.send("Saat kaç?")
        advanceUntilIdle()
        val turn = c.turns.value.single()
        assertTrue(turn.done)
        assertEquals("Saat 14:05.", turn.scenes.single().sahne.ses)
        assertEquals(listOf<QuickCommand>(QuickCommand.TimeNow), bridge.executed)
        assertEquals(listOf("Saat 14:05."), tts.spoken)
        assertEquals(CoreMode.IDLE, c.mode.value)
        scope.cancel()
    }

    @Test
    fun platformErrorReplacesTheCannedAnswer() = runTest {
        val scope = controllerScope()
        val (c, _, tts) = setup(scope = scope)
        c.send("Spotifyı aç")
        advanceUntilIdle()
        val text = c.turns.value.single().scenes.single().sahne.ses
        assertTrue(text.contains("bulamadım"), text)
        assertEquals(listOf(text), tts.spoken)
        scope.cancel()
    }

    @Test
    fun llmReplyIsStreamedSpokenAndKeptAsJsonHistory() = runTest {
        val scope = controllerScope()
        var lastMessages: List<ChatMessage> = emptyList()
        val mock = MockLlmEngine(tokenDelayMs = 0)
        val spy = object : LlmEngine {
            override val id = "spy"
            override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> {
                lastMessages = messages
                return mock.stream(messages, config)
            }
        }
        val (c, _, tts) = setup(spy, scope)
        c.send("Merhaba Rick")
        advanceUntilIdle()
        assertEquals(listOf("Merhaba efendim, ben Rick. Her şey hazır."), tts.spoken)
        val first = c.turns.value.single()
        assertTrue(first.assistantText!!.contains("\"sahneler\""))
        c.send("Bana bir şey anlat")
        advanceUntilIdle()
        val history = lastMessages.filter { it.role == Role.ASSISTANT }
        assertEquals(1, history.size)
        assertTrue(history.single().content.startsWith("{"), "history must use the JSON reply format")
        scope.cancel()
    }

    @Test
    fun stopCommandSilencesSpeech() = runTest {
        val scope = controllerScope()
        val (c, _, tts) = setup(scope = scope)
        c.send("sus")
        advanceUntilIdle()
        assertEquals("Tamam, susuyorum.", c.turns.value.single().scenes.single().sahne.ses)
        assertTrue(tts.stops >= 1)
        assertTrue(tts.spoken.isEmpty())
        scope.cancel()
    }

    @Test
    fun newQuestionMarksUnfinishedTurnAsInterrupted() = runTest {
        val scope = controllerScope()
        val slow = object : LlmEngine {
            override val id = "slow"
            override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = flow {
                delay(10_000)
                emit("{\"sahneler\":[{\"ses\":\"Geç cevap.\"}]}")
            }
        }
        val (c, _, _) = setup(slow, scope)
        c.send("Birinci soru")
        runCurrent()
        c.send("İkinci soru")
        runCurrent()
        val turns = c.turns.value
        assertTrue(turns[0].done && turns[0].interrupted)
        assertTrue(!turns[1].done)
        advanceUntilIdle()
        assertTrue(c.turns.value[1].done)
        scope.cancel()
    }

    // ---- offers ----

    private class WebBridge(tts: Tts, val body: String, gateReady: Boolean = true) : PlatformBridgeDelegate(Bridge(tts)) {
        val fetched = mutableListOf<String>()
        var closed = 0
        override val downloader = object : Downloader by b.downloader {
            override val state: StateFlow<DownloadState> = MutableStateFlow(if (gateReady) DownloadState.Ready("Kapı 1.1") else DownloadState.Unavailable)
        }
        override val web = object : WebGate {
            override suspend fun fetch(url: String): WebFetch { fetched += url; return WebFetch.Ok(body, "tr.wikipedia.org", body.length.toLong()) }
            override suspend fun done() { closed++ }
        }
    }

    /** Forwards everything to [b]; tests override single members. */
    private open class PlatformBridgeDelegate(val b: Bridge) : PlatformBridge by b

    @Test
    fun aNeedIsOfferedAndDoneOnlyAfterYes() = runTest {
        val scope = controllerScope()
        val (c, bridge, tts) = setup(scope = scope)
        c.send("Burası çok karanlık")
        advanceUntilIdle()
        assertEquals("Feneri açayım mı?", c.turns.value.last().scenes.single().sahne.ses)
        assertTrue(bridge.executed.isEmpty(), "nothing happens before the answer")
        c.send("evet")
        advanceUntilIdle()
        assertEquals(1, bridge.executed.size)
        assertEquals(true, c.turns.value[0].offerAnswer)
        assertTrue(tts.spoken.last().contains("Fener"), tts.spoken.last())
        // A "no" does nothing, and a new question just moves on.
        c.send("Ben artık yatıyorum")
        advanceUntilIdle()
        c.send("hayır")
        advanceUntilIdle()
        assertEquals(1, bridge.executed.size)
        assertEquals("Peki, yapmıyorum.", c.turns.value.last().scenes.single().sahne.ses)
        scope.cancel()
    }

    @Test
    fun anApprovedWebSearchSendsOnlyTheQueryAndIsLogged() = runTest {
        val scope = controllerScope()
        val tts = Tts()
        val body = """{"query":{"pages":[{"title":"Atatürk","index":1,"extract":"Mustafa Kemal Atatürk, Türkiye Cumhuriyeti'nin kurucusudur. 1881'de doğdu."}]}}"""
        val bridge = WebBridge(tts, body)
        val memory = InMemoryMemoryStore()
        val assistant = Assistant(MockLlmEngine(tokenDelayMs = 0), DefaultTools.create(memory, now), memory, now, webOffer = { true })
        val files = Files()
        val log = NetworkLogStore(files)
        val c = JarvisController(scope, bridge, assistant, memory, SettingsStore(files), netLog = log)
        c.send("Atatürk kimdir?")
        advanceUntilIdle()
        val offerTurn = c.turns.value.last()
        assertTrue(offerTurn.offer is io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.WebLookup)
        assertTrue(bridge.fetched.isEmpty())
        c.answerOffer(offerTurn.id, true)
        advanceUntilIdle()
        assertEquals(1, bridge.fetched.size)
        assertEquals("Atatürk", io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.queryOf(bridge.fetched.single()))
        assertEquals(1, bridge.closed, "the gate is closed after the search")
        val answer = c.turns.value.last()
        assertTrue(answer.scenes.single().sahne.ses.startsWith("Mustafa Kemal Atatürk"), answer.scenes.single().sahne.ses)
        assertEquals("web", answer.kaynaklar.single().tur)
        assertEquals("tamam", log.events.value.single().result)
        scope.cancel()
    }

    @Test
    fun fullOfflineModeNeverGoesOut() = runTest {
        val scope = controllerScope()
        val tts = Tts()
        val bridge = WebBridge(tts, "{}")
        val memory = InMemoryMemoryStore()
        val assistant = Assistant(MockLlmEngine(tokenDelayMs = 0), DefaultTools.create(memory, now), memory, now, webOffer = { true })
        val files = Files()
        val settings = SettingsStore(files)
        settings.update { it.copy(fullOffline = true) }
        val c = JarvisController(scope, bridge, assistant, memory, settings)
        c.send("Atatürk kimdir?")
        advanceUntilIdle()
        c.send("evet bak")
        advanceUntilIdle()
        assertTrue(bridge.fetched.isEmpty())
        assertTrue(c.turns.value.last().scenes.single().sahne.ses.contains("çevrimdışı"))
        scope.cancel()
    }

    private fun webSetup(scope: CoroutineScope, body: String, gateReady: Boolean = true): Triple<JarvisController, WebBridge, NetworkLogStore> {
        val bridge = WebBridge(Tts(), body, gateReady)
        val memory = InMemoryMemoryStore()
        val assistant = Assistant(MockLlmEngine(tokenDelayMs = 0), DefaultTools.create(memory, now), memory, now, webOffer = { gateReady }, browserOffer = { true })
        val files = Files()
        val log = NetworkLogStore(files)
        return Triple(JarvisController(scope, bridge, assistant, memory, SettingsStore(files), netLog = log), bridge, log)
    }

    @Test
    fun goOnlineInPlainWordsIsAYes() = runTest {
        val scope = controllerScope()
        val body = """{"query":{"pages":[{"title":"Atatürk","index":1,"extract":"Mustafa Kemal Atatürk, Türkiye Cumhuriyeti'nin kurucusudur. 1881'de doğdu."}]}}"""
        val (c, bridge, _) = webSetup(scope, body)
        c.send("Atatürk kimdir?")
        advanceUntilIdle()
        c.send("Online ol, onay verdim, bak")
        advanceUntilIdle()
        assertEquals("Atatürk", io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.queryOf(bridge.fetched.single()))
        assertTrue(c.turns.value.last().scenes.single().sahne.ses.startsWith("Mustafa Kemal"))
        // Said straight away, without an offer first.
        c.send("internette Çanakkale Savaşı ara")
        advanceUntilIdle()
        assertEquals("Çanakkale Savaşı", io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.queryOf(bridge.fetched.last()))
        scope.cancel()
    }

    @Test
    fun withoutTheGateTheBrowserIsOfferedAndTheReasonSaid() = runTest {
        val scope = controllerScope()
        val (c, bridge, log) = webSetup(scope, "{}", gateReady = false)
        c.send("internette Çanakkale Savaşı ara")
        advanceUntilIdle()
        val turn = c.turns.value.last()
        assertTrue(turn.scenes.single().sahne.ses.contains("Rick Kapı"), turn.scenes.single().sahne.ses)
        assertTrue(bridge.fetched.isEmpty())
        c.send("evet")
        advanceUntilIdle()
        val google = QuickCommand.Device(io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.OpenIn(io.github.ahmetdurmus1218.jarvis.core.device.OutsideApp.GOOGLE, "Çanakkale Savaşı"))
        assertEquals(listOf<QuickCommand>(google), (bridge.b as Bridge).executed)
        assertEquals("google.com", log.events.value.single().host)
        scope.cancel()
    }

    @Test
    fun youTubeGetsTheSongAndAVagueAskGetsAQuestion() = runTest {
        val scope = controllerScope()
        val (c, bridge, log) = webSetup(scope, "{}")
        val executed = (bridge.b as Bridge).executed
        c.send("YouTube'dan Tarkan Şımarık aç")
        advanceUntilIdle()
        val yt = io.github.ahmetdurmus1218.jarvis.core.device.OutsideApp.YOUTUBE
        assertEquals(QuickCommand.Device(io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.OpenIn(yt, "Tarkan Şımarık")), executed.single())
        assertEquals("youtube.com", log.events.value.single().host)
        c.send("YouTube'dan şu müziği aç")
        advanceUntilIdle()
        assertTrue(c.turns.value.last().scenes.single().sahne.ses.contains("Hangisini"))
        assertEquals(1, executed.size)
        c.send("Sezen Aksu Gidiyorum")
        advanceUntilIdle()
        assertEquals(QuickCommand.Device(io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.OpenIn(yt, "Sezen Aksu Gidiyorum")), executed.last())
        assertTrue(c.turns.value.last().scenes.single().sahne.ses.contains("Sezen Aksu Gidiyorum"))
        scope.cancel()
    }

    @Test
    fun voiceGenderIsReadOnlyWhenTheEngineSaysIt() {
        assertEquals(VoiceGender.KADIN, VoiceGender.guess("tr-TR-language#female_1-local"))
        assertEquals(VoiceGender.ERKEK, VoiceGender.guess("tr-TR-language#male_2-local"))
        assertEquals(VoiceGender.KADIN, VoiceGender.guess("tur-tr-f01"))
        assertEquals(VoiceGender.ERKEK, VoiceGender.guess("tur-tr-m00 embedded"))
        assertEquals(null, VoiceGender.guess("tr-tr-x-cfs-local"))
    }

    @Test
    fun theNewsIsReadAfterTheApprovalAndLogged() = runTest {
        val scope = controllerScope()
        val rss = """<rss><channel><item><title>Meclis'te yeni bütçe görüşmeleri başladı</title><link>https://www.bbc.com/turkce/a1</link></item>
            <item><title>Deprem bölgesinde konut teslimleri sürüyor</title><link>https://www.bbc.com/turkce/a2</link></item></channel></rss>"""
        val (c, bridge, log) = webSetup(scope, rss)
        c.send("Günün haberleri")
        advanceUntilIdle()
        assertEquals(io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews.URL, bridge.fetched.single())
        val turn = c.turns.value.last()
        assertEquals("Meclis'te yeni bütçe görüşmeleri başladı.", turn.scenes[1].sahne.ses)
        assertEquals("Günün haberleri (BBC Türkçe)", log.events.value.single().what)
        // Offered on waking up: nothing goes out before the yes.
        c.offerNews("Günaydın.")
        advanceUntilIdle()
        assertEquals(1, bridge.fetched.size)
        assertTrue(c.turns.value.last().scenes.single().sahne.ses.endsWith("Günün haberlerini okuyayım mı?"))
        c.send("evet")
        advanceUntilIdle()
        assertEquals(2, bridge.fetched.size)
        scope.cancel()
    }

    private class MusicBridge(tts: Tts, var playing: Boolean) : PlatformBridgeDelegate(Bridge(tts)) {
        override fun mediaPlaying() = playing
    }

    @Test
    fun handsChangeTheSongWhileMusicPlaysAndTurnThePageOtherwise() = runTest {
        val scope = controllerScope()
        val bridge = MusicBridge(Tts(), playing = true)
        val memory = InMemoryMemoryStore()
        val c = JarvisController(scope, bridge, Assistant(MockLlmEngine(tokenDelayMs = 0), DefaultTools.create(memory, now), memory, now), memory, SettingsStore(Files()))
        val pages = mutableListOf<Int>()
        assertEquals("⏭ Sonraki şarkı", c.onHand(io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.NEXT) { pages += it })
        assertEquals("⏸ Durdurdum", c.onHand(io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.PAUSE_PLAY) { pages += it })
        advanceUntilIdle()
        val executed = (bridge.b as Bridge).executed.map { (it as QuickCommand.Device).action }
        assertEquals(
            listOf(io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.Media(io.github.ahmetdurmus1218.jarvis.core.device.MediaKey.NEXT), io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.Media(io.github.ahmetdurmus1218.jarvis.core.device.MediaKey.PAUSE)),
            executed,
        )
        // Paused by the hand: the next ✋ plays again. No music: swipes turn Rick's page.
        bridge.playing = false
        assertEquals("▶ Devam", c.onHand(io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.PAUSE_PLAY) { pages += it })
        assertEquals("⬇ Sonraki sayfa", c.onHand(io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.NEXT) { pages += it })
        assertEquals("⬆ Yukarı", c.onHand(io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.SCROLL_UP) { pages += it })
        assertEquals(listOf(1, -1), pages)
        // Over other apps there is no page: nothing to do.
        assertEquals(null, c.onHand(io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.NEXT, null))
        assertEquals("🔊 Ses +", c.onHand(io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand.VOLUME_UP, null))
        scope.cancel()
    }

    @Test
    fun goingBackDoesNotCoverTheAnswerAndNothingStaysStuck() = runTest {
        val scope = controllerScope()
        val stuck = object : LlmEngine {
            override val id = "stuck"
            override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = flow { delay(10 * 60_000L); emit("{}") }
        }
        val (c, _, _) = setup(engine = stuck, scope = scope)
        val commands = mutableListOf<QuickCommand>()
        val collector = scope.launch { c.appCommands.collect { commands += it } }
        c.send("Atatürk kimdir")
        advanceTimeBy(1_000)
        assertEquals(CoreMode.THINKING, c.mode.value)
        // Stuck for minutes: Rick stops by itself and says so.
        advanceTimeBy(JarvisController.STUCK_THINKING_MS + 1_000)
        assertEquals(CoreMode.IDLE, c.mode.value)
        assertTrue(c.partialTranscript.value.contains("uzun sürdü"), c.partialTranscript.value)
        // "geri" / "ana ekran" move around without a new turn over the answer.
        val turns = c.turns.value.size
        c.send("geri")
        c.send("ana ekran")
        advanceUntilIdle()
        assertEquals(turns, c.turns.value.size)
        assertEquals(listOf<QuickCommand>(QuickCommand.Back, QuickCommand.MainScreen), commands)
        collector.cancel()
        scope.cancel()
    }
    // ---- hands-free standby ----

    /** Long enough for standby to open the microphone after Rick fell idle. */
    private val OPENS = JarvisController.STANDBY_DELAY_MS + 700

    @Test
    fun aControllerStartsOnADispatcherThatRunsCoroutinesAtOnce() = runTest {
        // Compose's own scope runs a launched coroutine immediately: everything init touches must exist by then.
        val scope = CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler) + Job())
        val mic = FakeMic()
        val (c, _, _) = setup(scope = scope, stt = OwnStt(ArrayDeque()), mic = mic)
        c.setAppVisible(true)
        advanceTimeBy(OPENS)
        assertTrue(mic.open)
        c.setAppVisible(false)
        scope.cancel()
    }

    @Test
    fun sayingTheNameAsksWithoutTouchingAnything() = runTest {
        val scope = controllerScope()
        val mic = FakeMic()
        val stt = OwnStt(ArrayDeque(listOf("Rick, saat kaç")))
        val (c, _, tts) = setup(scope = scope, stt = stt, mic = mic)
        c.setAppVisible(true)
        advanceTimeBy(OPENS)
        assertTrue(mic.open, "standby opens the microphone by itself")
        assertTrue(c.standby.value)
        mic.hear(quiet(1_000), voice(1_200), quiet(1_500))
        advanceTimeBy(OPENS)
        assertEquals(listOf("Saat kaç"), c.turns.value.map { it.user })
        assertEquals(1, tts.spoken.size)
        assertEquals(0, stt.starts, "no follow-up microphone: the next request needs the name again")
        // Afterwards Rick waits for its name again.
        advanceTimeBy(3_000)
        assertTrue(mic.open)
        c.setAppVisible(false)
        scope.cancel()
    }

    @Test
    fun talkWithoutTheNameIsDroppedAndStandbyGoesOn() = runTest {
        val scope = controllerScope()
        val mic = FakeMic()
        val stt = OwnStt(ArrayDeque(listOf("bugün hava çok güzel", "İzlediğiniz için teşekkürler")))
        val (c, _, tts) = setup(scope = scope, stt = stt, mic = mic)
        c.setAppVisible(true)
        advanceTimeBy(OPENS)
        mic.hear(quiet(500), voice(1_000), quiet(1_500))
        advanceTimeBy(OPENS + JarvisController.STANDBY_BACKOFF_MS[1])
        assertTrue(mic.open)
        mic.hear(quiet(500), voice(1_000), quiet(1_500))
        // The more talk in a row that is not for Rick, the longer standby waits before it listens again.
        advanceTimeBy(OPENS + JarvisController.STANDBY_BACKOFF_MS[1])
        assertTrue(!mic.open, "a noisy room does not keep the recognizer busy")
        advanceTimeBy(JarvisController.STANDBY_BACKOFF_MS[2])
        assertEquals(2, stt.decoded)
        assertTrue(c.turns.value.isEmpty())
        assertTrue(tts.spoken.isEmpty())
        assertEquals("", c.partialTranscript.value)
        assertTrue(mic.open)
        c.setAppVisible(false)
        scope.cancel()
    }

    @Test
    fun theNameAloneIsAnsweredAndThenTheRequestIsHeard() = runTest {
        val scope = controllerScope()
        val mic = FakeMic()
        val stt = OwnStt(ArrayDeque(listOf("Rick")), "saat kaç")
        val (c, _, tts) = setup(scope = scope, stt = stt, mic = mic)
        c.setAppVisible(true)
        advanceTimeBy(OPENS)
        mic.hear(quiet(500), voice(500), quiet(1_500))
        advanceTimeBy(2_000)
        assertEquals(JarvisController.CALL_ANSWER, tts.spoken.first())
        assertEquals(1, stt.starts)
        assertEquals(listOf("saat kaç"), c.turns.value.map { it.user })
        c.setAppVisible(false)
        scope.cancel()
    }

    @Test
    fun aCallThatIsNotFollowedByAnythingEndsQuietly() = runTest {
        val scope = controllerScope()
        val mic = FakeMic()
        val stt = OwnStt(ArrayDeque(listOf("Hey Rick")))
        val (c, _, _) = setup(scope = scope, stt = stt, mic = mic)
        c.setAppVisible(true)
        advanceTimeBy(OPENS)
        mic.hear(quiet(500), voice(500), quiet(1_500))
        advanceTimeBy(2_000)
        assertEquals(1, stt.starts)
        assertEquals("", c.partialTranscript.value, "no 'Bir şey duyamadım' after a microphone Rick opened itself")
        assertEquals(CoreMode.IDLE, c.mode.value)
        c.setAppVisible(false)
        scope.cancel()
    }

    @Test
    fun twoClapsOpenTheMicrophoneWhileRickIsAwake() = runTest {
        val scope = controllerScope()
        val mic = FakeMic()
        val stt = OwnStt(ArrayDeque(), "saat kaç")
        val (c, _, _) = setup(scope = scope, stt = stt, mic = mic)
        c.setAppVisible(true)
        advanceTimeBy(OPENS)
        mic.hear(quiet(1_500), clap(), quiet(350), clap(), quiet(300))
        advanceTimeBy(OPENS)
        assertEquals(1, stt.starts)
        assertEquals(0, stt.decoded, "claps are not sent to the recognizer")
        assertEquals(listOf("saat kaç"), c.turns.value.map { it.user })
        c.setAppVisible(false)
        scope.cancel()
    }

    @Test
    fun standbyStepsAsideAndNeverRunsWhenSwitchedOffOrHidden() = runTest {
        val scope = controllerScope()
        val mic = FakeMic()
        val stt = OwnStt(ArrayDeque(listOf("Rick, saat kaç")))
        val (c, _, _) = setup(scope = scope, stt = stt, mic = mic)
        // Not on screen: nothing listens.
        advanceTimeBy(5_000)
        assertEquals(0, mic.starts)
        c.setAppVisible(true)
        advanceTimeBy(OPENS)
        assertTrue(mic.open)
        // A screen that needs the microphone itself (sleep, clap calibration) gets it at once.
        MicUse.take()
        assertTrue(!mic.open)
        advanceTimeBy(5_000)
        assertTrue(!mic.open)
        MicUse.release()
        advanceTimeBy(OPENS)
        assertTrue(mic.open)
        // The microphone button closes standby before the recognizer opens its own microphone.
        c.startListening()
        assertTrue(!mic.open)
        advanceTimeBy(3_000)
        assertTrue(mic.open)
        // Asleep or in the background: closed.
        c.setAppAwake(false)
        assertTrue(!mic.open)
        c.setAppAwake(true)
        advanceTimeBy(OPENS)
        assertTrue(mic.open)
        c.setAppVisible(false)
        assertTrue(!mic.open)
        // Switched off in Settings: never again.
        c.settings.update { it.copy(handsFree = false) }
        c.setAppVisible(true)
        advanceTimeBy(5_000)
        assertTrue(!mic.open)
        assertTrue(!c.standby.value)
        c.setAppVisible(false)
        scope.cancel()
    }

    @Test
    fun theFloatingRickListensInTheBackgroundAndMayTakeTheClaps() = runTest {
        val scope = controllerScope()
        val mic = FakeMic()
        val stt = OwnStt(ArrayDeque(), "saat kaç")
        val (c, _, _) = setup(scope = scope, stt = stt, mic = mic)
        var woke = 0
        c.onClaps = { woke++; true }
        c.setFloating(true)
        advanceTimeBy(OPENS)
        assertTrue(mic.open)
        mic.hear(quiet(1_500), clap(), quiet(350), clap(), quiet(300))
        advanceTimeBy(OPENS)
        assertEquals(1, woke)
        assertEquals(0, stt.starts, "the floating Rick woke up itself")
        c.setFloating(false)
        scope.cancel()
    }
}

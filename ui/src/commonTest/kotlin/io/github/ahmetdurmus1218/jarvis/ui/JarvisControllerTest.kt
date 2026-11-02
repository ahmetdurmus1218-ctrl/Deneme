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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
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

    private class Bridge(val tts: Tts, val stt: SpeechInput? = null) : PlatformBridge {
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

    private fun setup(engine: LlmEngine = MockLlmEngine(tokenDelayMs = 0), scope: CoroutineScope, stt: SpeechInput? = null): Triple<JarvisController, Bridge, Tts> {
        val tts = Tts()
        val bridge = Bridge(tts, stt)
        val memory = InMemoryMemoryStore()
        val assistant = Assistant(engine, DefaultTools.create(memory, now), memory, now)
        return Triple(JarvisController(scope, bridge, assistant, memory, SettingsStore(bridge.files)), bridge, tts)
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
        c.send("Uçak modunu aç")
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
        c.send("Merhaba Jarvis")
        advanceUntilIdle()
        assertEquals(listOf("Merhaba efendim, Jarvis hazır."), tts.spoken)
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
}

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
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

    private class Bridge(val tts: Tts) : PlatformBridge {
        val executed = mutableListOf<QuickCommand>()
        override val platform = Platform.ANDROID
        override val appVersion = "test"
        override fun deviceInfo() = DeviceInfo("test", 8.0, 1L shl 40)
        override val files: AppFiles = Files()
        override val speechInput: SpeechInput? = null
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

    private fun setup(engine: LlmEngine = MockLlmEngine(tokenDelayMs = 0), scope: kotlinx.coroutines.CoroutineScope): Triple<JarvisController, Bridge, Tts> {
        val tts = Tts()
        val bridge = Bridge(tts)
        val memory = InMemoryMemoryStore()
        val assistant = Assistant(engine, DefaultTools.create(memory, now), memory, now)
        return Triple(JarvisController(scope, bridge, assistant, memory, SettingsStore(bridge.files)), bridge, tts)
    }

    @Test
    fun quickCommandIsExecutedAndSpoken() = runTest {
        val (c, bridge, tts) = setup(scope = backgroundScope)
        c.send("Saat kaç?")
        advanceUntilIdle()
        val turn = c.turns.value.single()
        assertTrue(turn.done)
        assertEquals("Saat 14:05.", turn.scenes.single().sahne.ses)
        assertEquals(listOf<QuickCommand>(QuickCommand.TimeNow), bridge.executed)
        assertEquals(listOf("Saat 14:05."), tts.spoken)
        assertEquals(CoreMode.IDLE, c.mode.value)
    }

    @Test
    fun platformErrorReplacesTheCannedAnswer() = runTest {
        val (c, _, tts) = setup(scope = backgroundScope)
        c.send("Uçak modunu aç")
        advanceUntilIdle()
        val text = c.turns.value.single().scenes.single().sahne.ses
        assertTrue(text.contains("bulamadım"), text)
        assertEquals(listOf(text), tts.spoken)
    }

    @Test
    fun llmReplyIsStreamedSpokenAndKeptAsJsonHistory() = runTest {
        var lastMessages: List<ChatMessage> = emptyList()
        val mock = MockLlmEngine(tokenDelayMs = 0)
        val spy = object : LlmEngine {
            override val id = "spy"
            override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> {
                lastMessages = messages
                return mock.stream(messages, config)
            }
        }
        val (c, _, tts) = setup(spy, backgroundScope)
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
    }

    @Test
    fun stopCommandSilencesSpeech() = runTest {
        val (c, _, tts) = setup(scope = backgroundScope)
        c.send("sus")
        advanceUntilIdle()
        assertEquals("Tamam, susuyorum.", c.turns.value.single().scenes.single().sahne.ses)
        assertTrue(tts.stops >= 1)
        assertTrue(tts.spoken.isEmpty())
    }

    @Test
    fun newQuestionMarksUnfinishedTurnAsInterrupted() = runTest {
        val slow = object : LlmEngine {
            override val id = "slow"
            override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = flow {
                delay(10_000)
                emit("{\"sahneler\":[{\"ses\":\"Geç cevap.\"}]}")
            }
        }
        val (c, _, _) = setup(slow, backgroundScope)
        c.send("Birinci soru")
        runCurrent()
        c.send("İkinci soru")
        runCurrent()
        val turns = c.turns.value
        assertTrue(turns[0].done && turns[0].interrupted)
        assertTrue(!turns[1].done)
        advanceUntilIdle()
        assertTrue(c.turns.value[1].done)
    }
}

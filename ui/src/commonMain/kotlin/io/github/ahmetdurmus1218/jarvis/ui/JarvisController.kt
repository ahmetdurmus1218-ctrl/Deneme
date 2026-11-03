package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant
import io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent
import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.memory.MemoryStore
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.scene.Kaynak
import io.github.ahmetdurmus1218.jarvis.core.scene.Reply
import io.github.ahmetdurmus1218.jarvis.core.scene.SceneJson
import io.github.ahmetdurmus1218.jarvis.core.scene.Sahne
import io.github.ahmetdurmus1218.jarvis.core.scene.VerifiedScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Turn(
    val id: Long,
    val user: String,
    val scenes: List<VerifiedScene> = emptyList(),
    val detay: String? = null,
    val kaynaklar: List<Kaynak> = emptyList(),
    val tools: List<String> = emptyList(),
    val done: Boolean = false,
    val error: String? = null,
    /** Raw model reply (scene JSON), fed back as conversation history. */
    val assistantText: String? = null,
    /** A newer question arrived before this one finished. */
    val interrupted: Boolean = false,
)

enum class CoreMode { IDLE, LISTENING, THINKING, SPEAKING }

/** Chat state machine shared by Android and desktop. */
class JarvisController(
    private val scope: CoroutineScope,
    private val bridge: PlatformBridge,
    private val assistant: Assistant,
    val memory: MemoryStore,
    val settings: SettingsStore,
) {
    private val _appCommands = kotlinx.coroutines.flow.MutableSharedFlow<QuickCommand>(extraBufferCapacity = 8)
    /** Commands for the app itself (hand control, planets), handled by the screens. */
    val appCommands: kotlinx.coroutines.flow.SharedFlow<QuickCommand> = _appCommands

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    private val _mode = MutableStateFlow(CoreMode.IDLE)
    val mode: StateFlow<CoreMode> = _mode.asStateFlow()

    /** Text currently being spoken (subtitle). */
    private val _subtitle = MutableStateFlow("")
    val subtitle: StateFlow<String> = _subtitle.asStateFlow()

    private val _partial = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partial.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Microphone level 0..1 for the HUD animation. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private var nextId = 1L
    private var askJob: Job? = null
    private var currentTurnId: Long? = null
    private val speechQueue = Channel<String>(Channel.UNLIMITED)
    private var queued = 0

    init {
        scope.launch {
            for (text in speechQueue) {
                val tts = bridge.speechOutput
                if (tts != null && settings.state.value.voiceEnabled && text.isNotBlank()) {
                    _mode.value = CoreMode.SPEAKING
                    _subtitle.value = text
                    runCatching { tts.speak(text, settings.state.value.speechRate) }
                }
                queued--
                if (queued <= 0) {
                    queued = 0
                    _subtitle.value = ""
                    if (_mode.value == CoreMode.SPEAKING) _mode.value = CoreMode.IDLE
                    maybeContinueConversation()
                }
            }
        }
    }

    /** Turn started by voice whose answer should be followed by listening again (conversation mode). */
    private var continueAfter: Long? = null

    /** Conversation mode: once a spoken question is fully answered and spoken, listen again. */
    private fun maybeContinueConversation() {
        val id = continueAfter ?: return
        if (!settings.state.value.conversationMode) return
        val turn = _turns.value.firstOrNull { it.id == id } ?: return
        if (!turn.done || queued > 0 || _mode.value != CoreMode.IDLE) return
        continueAfter = null
        scope.launch {
            delay(CONTINUE_DELAY_MS)
            if (_mode.value == CoreMode.IDLE && currentTurnId == id) startListening()
        }
    }

    private fun say(text: String) {
        if (text.isBlank()) return
        queued++
        speechQueue.trySend(text)
    }

    fun stopSpeaking() {
        while (speechQueue.tryReceive().isSuccess) queued--
        queued = 0
        bridge.speechOutput?.stop()
        _subtitle.value = ""
        if (_mode.value == CoreMode.SPEAKING) _mode.value = CoreMode.IDLE
    }

    private fun updateTurn(id: Long, f: (Turn) -> Turn) {
        _turns.value = _turns.value.map { if (it.id == id) f(it) else it }
    }

    /** Last turns as chat history; assistant turns are given back in the same JSON format the model must produce. */
    private fun history(): List<ChatMessage> = _turns.value.filter { it.done && it.error == null && !it.interrupted }.takeLast(6).flatMap { t ->
        listOf(
            ChatMessage(Role.USER, t.user),
            ChatMessage(Role.ASSISTANT, t.assistantText ?: SceneJson.encodeToString(Reply.serializer(), Reply(t.scenes.map { it.sahne }))),
        )
    }

    /**
     * A question about a picture (camera snapshot, screenshot, image file). Shown as a normal turn;
     * the picture itself is not kept.
     */
    fun askAboutImage(question: String, jpeg: ByteArray) {
        stopSpeaking()
        askJob?.cancel()
        currentTurnId?.let { prev -> updateTurn(prev) { if (it.done) it else it.copy(done = true, interrupted = true) } }
        val id = nextId++
        currentTurnId = id
        _turns.value = _turns.value + Turn(id, "📷 $question")
        _mode.value = CoreMode.THINKING
        askJob = scope.launch {
            try {
                assistant.askAboutImage(question, jpeg).collect { ev -> handle(id, ev) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateTurn(id) { it.copy(done = true, error = e.message ?: "Beklenmeyen hata") }
            } finally {
                if (currentTurnId == id && _mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE
            }
        }
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        stopSpeaking()
        askJob?.cancel()
        currentTurnId?.let { prev -> updateTurn(prev) { if (it.done) it else it.copy(done = true, interrupted = true) } }
        val id = nextId++
        currentTurnId = id
        val history = history()
        _turns.value = _turns.value + Turn(id, clean)
        _mode.value = CoreMode.THINKING
        askJob = scope.launch {
            try {
                assistant.ask(clean, history).collect { ev -> handle(id, ev) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                updateTurn(id) { it.copy(done = true, error = e.message ?: "Beklenmeyen hata") }
            } finally {
                // A cancelled older question must not reset the state of the newer one.
                if (currentTurnId == id && _mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE
            }
        }
    }

    private suspend fun handle(id: Long, ev: AssistantEvent) {
        when (ev) {
            is AssistantEvent.Quick -> {
                if (ev.command == QuickCommand.StopSpeaking) {
                    continueAfter = null // "sus" also ends the hands-free conversation
                    stopSpeaking()
                    updateTurn(id) { it.copy(done = true, scenes = listOf(VerifiedScene(Sahne("Tamam, susuyorum.")))) }
                    return
                }
                val inApp = ev.command is QuickCommand.ShowPlanet || ev.command is QuickCommand.HandControl || ev.command is QuickCommand.Home || ev.command == QuickCommand.Sleep
                if (inApp) _appCommands.tryEmit(ev.command)
                val error = if (inApp) null else bridge.execute(ev.command)
                val scene = if (error == null) ev.scene else VerifiedScene(Sahne(error))
                updateTurn(id) { it.copy(scenes = listOf(scene), done = true) }
                say(scene.sahne.ses)
            }
            is AssistantEvent.ToolCall -> updateTurn(id) { it.copy(tools = it.tools + ev.name) }
            is AssistantEvent.SceneReady -> {
                updateTurn(id) { it.copy(scenes = it.scenes + ev.scene) }
                say(ev.scene.sahne.ses)
            }
            is AssistantEvent.Completed -> updateTurn(id) {
                it.copy(scenes = ev.reply.scenes, detay = ev.reply.detay, kaynaklar = ev.reply.kaynaklar, done = true, assistantText = ev.assistantText)
            }
            is AssistantEvent.Failed -> updateTurn(id) { it.copy(done = true, error = ev.message) }
        }
        if (ev is AssistantEvent.Completed || ev is AssistantEvent.Quick || ev is AssistantEvent.Failed) {
            if (queued == 0 && _mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE
            if (ev !is AssistantEvent.Failed) maybeContinueConversation() else continueAfter = null
        }
    }

    // ---- push-to-talk ----

    fun startListening() {
        val stt = bridge.speechInput ?: return
        if (!stt.isAvailable()) {
            _partial.value = if (stt.ownModelGroup != null) {
                "Jarvis'in kendi Türkçe ses tanıması (Whisper) henüz kurulu değil. Aşağıdaki düğmeyle bir kez indirin; sonra tamamen internetsiz çalışır."
            } else {
                "Bu cihazda ses tanıma yok. Yazarak kullanabilirsiniz."
            }
            return
        }
        stopSpeaking()
        _partial.value = ""
        _mode.value = CoreMode.LISTENING
        stt.start(object : SpeechInput.Listener {
            override fun onPartial(text: String) { _partial.value = text }
            override fun onFinal(text: String) {
                _partial.value = ""
                _level.value = 0f
                if (_mode.value == CoreMode.LISTENING) _mode.value = CoreMode.IDLE
                // Set before send(): a quick command can finish synchronously inside it.
                continueAfter = nextId
                send(text)
            }
            override fun onError(message: String) {
                _partial.value = message
                _level.value = 0f
                if (_mode.value == CoreMode.LISTENING) _mode.value = CoreMode.IDLE
            }
            override fun onLevel(rms: Float) { _level.value = rms.coerceIn(0f, 1f) }
        })
    }

    fun stopListening() {
        bridge.speechInput?.stop()
    }

    /** Mic button tapped: start listening, or finish if already listening (the recognizer also stops by itself on silence). */
    fun toggleListening() {
        if (_mode.value == CoreMode.LISTENING) stopListening() else startListening()
    }

    fun speakTest(text: String) = say(text)

    /** Replaces the caption above the input row (e.g. "Ses tanıma hazır" after Whisper is installed). */
    fun showNotice(text: String) {
        _partial.value = text
    }

    fun clearConversation() {
        askJob?.cancel()
        stopSpeaking()
        _turns.value = emptyList()
        _mode.value = CoreMode.IDLE
    }

    companion object {
        /** Pause between Jarvis finishing and listening again, so its own voice is not recorded. */
        const val CONTINUE_DELAY_MS = 350L
    }
}

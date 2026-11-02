package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant
import io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent
import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.memory.MemoryStore
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.scene.Kaynak
import io.github.ahmetdurmus1218.jarvis.core.scene.Sahne
import io.github.ahmetdurmus1218.jarvis.core.scene.VerifiedScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
                }
            }
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

    private fun history(): List<ChatMessage> = _turns.value.filter { it.done && it.error == null }.takeLast(6).flatMap { t ->
        listOf(
            ChatMessage(Role.USER, t.user),
            ChatMessage(Role.ASSISTANT, t.scenes.joinToString(" ") { it.sahne.ses }),
        )
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        stopSpeaking()
        askJob?.cancel()
        val id = nextId++
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
                if (_mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE
            }
        }
    }

    private suspend fun handle(id: Long, ev: AssistantEvent) {
        when (ev) {
            is AssistantEvent.Quick -> {
                if (ev.command == QuickCommand.StopSpeaking) {
                    stopSpeaking()
                    updateTurn(id) { it.copy(done = true, scenes = listOf(VerifiedScene(Sahne("Tamam, susuyorum.")))) }
                    return
                }
                val error = bridge.execute(ev.command)
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
                it.copy(scenes = ev.reply.scenes, detay = ev.reply.detay, kaynaklar = ev.reply.kaynaklar, done = true)
            }.also { if (queued == 0 && _mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE }
            is AssistantEvent.Failed -> updateTurn(id) { it.copy(done = true, error = ev.message) }
        }
        if (ev is AssistantEvent.Completed || ev is AssistantEvent.Quick) {
            if (queued == 0 && _mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE
        }
    }

    // ---- push-to-talk ----

    fun startListening() {
        val stt = bridge.speechInput ?: return
        if (!stt.isAvailable()) return
        stopSpeaking()
        _partial.value = ""
        _mode.value = CoreMode.LISTENING
        stt.start(object : SpeechInput.Listener {
            override fun onPartial(text: String) { _partial.value = text }
            override fun onFinal(text: String) {
                _partial.value = ""
                _level.value = 0f
                if (_mode.value == CoreMode.LISTENING) _mode.value = CoreMode.IDLE
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

    fun speakTest(text: String) = say(text)

    fun clearConversation() {
        askJob?.cancel()
        stopSpeaking()
        _turns.value = emptyList()
        _mode.value = CoreMode.IDLE
    }
}

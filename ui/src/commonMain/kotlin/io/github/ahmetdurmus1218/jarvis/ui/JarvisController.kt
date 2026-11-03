package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.AssistantName
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
    /** The user's verdict: 1 = 👍, -1 = 👎, 0 = none. */
    val feedback: Int = 0,
)

enum class CoreMode { IDLE, LISTENING, THINKING, SPEAKING }

/** Chat state machine shared by Android and desktop. */
class JarvisController(
    private val scope: CoroutineScope,
    private val bridge: PlatformBridge,
    private val assistant: Assistant,
    val memory: MemoryStore,
    val settings: SettingsStore,
    /** The real local model for background fact extraction; null = patterns only. */
    private val factModel: () -> io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine? = { null },
    /** Lessons from corrections and likes (null: self-improvement not available). */
    private val learning: io.github.ahmetdurmus1218.jarvis.core.learning.Learning? = null,
    /** One idle research step given the recent questions; null = no idle reading. */
    private val research: (suspend (List<String>) -> io.github.ahmetdurmus1218.jarvis.core.learning.ResearchNote?)? = null,
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

    private val _voiceMissing = MutableStateFlow<String?>(null)
    /** Set when an answer could not be heard (no offline Turkish voice): the chat says why and offers the voice. */
    val voiceMissing: StateFlow<String?> = _voiceMissing.asStateFlow()

    /** Call after the voice was installed. */
    fun voiceInstalled() {
        _voiceMissing.value = null
        _partial.value = ""
    }

    private var nextId = 1L
    private var askJob: Job? = null
    private var currentTurnId: Long? = null
    private val speechQueue = Channel<String>(Channel.UNLIMITED)
    private var queued = 0

    /** Last time the user did something; idle reading starts only after a quiet while. */
    private var lastActivity = 0L
    private var idleChecks = 0
    /** After 👎: the next message is the right answer for this turn. */
    private var correctionFor: Long? = null

    init {
        research?.let { step ->
            scope.launch {
                while (true) {
                    delay(IDLE_CHECK_MS)
                    idleChecks++
                    // Only when nothing happens: not listening, thinking or speaking, and quiet for a while.
                    if (_mode.value != CoreMode.IDLE || !settings.state.value.selfLearning) continue
                    if (idleChecks * IDLE_CHECK_MS - lastActivity < IDLE_BEFORE_RESEARCH_MS) continue
                    val note = runCatching { step(_turns.value.takeLast(10).map { it.user }) }.getOrNull() ?: continue
                    if (_mode.value == CoreMode.IDLE && _partial.value.isBlank()) _partial.value = "📖 Boştayken okudum: ${note.title}"
                }
            }
        }
        scope.launch {
            bridge.reminders.collect { text ->
                _partial.value = "⏰ $text"
                say(text)
            }
        }
        scope.launch {
            for (text in speechQueue) {
                val tts = bridge.speechOutput
                if (tts != null && settings.state.value.voiceEnabled && text.isNotBlank()) {
                    _mode.value = CoreMode.SPEAKING
                    _subtitle.value = text
                    runCatching { tts.speak(text, settings.state.value.speechRate) }
                    tts.silentReason?.let { why ->
                        if (_voiceMissing.value == null) {
                            _voiceMissing.value = why
                            _partial.value = "🔇 $why Sesli cevap için aşağıdaki düğmeyle ${AssistantName.SHORT}'in kendi Türkçe sesini indirin (internetsiz çalışır)."
                        }
                    }
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

    /** The user's verdict on an answer: 👍 keeps it as a good example, 👎 asks for the right answer. */
    fun feedback(turnId: Long, good: Boolean) {
        val turn = _turns.value.firstOrNull { it.id == turnId } ?: return
        noteActivity()
        updateTurn(turnId) { it.copy(feedback = if (good) 1 else -1) }
        if (good) {
            correctionFor = null
            learning?.like(turn.user, answerText(turn))
            _partial.value = "👍 Not ettim; benzer sorularda böyle cevaplayacağım."
        } else {
            correctionFor = turnId
            _partial.value = "👎 Doğrusu ne? Yazın ya da söyleyin, öğreneyim."
        }
    }

    private fun answerText(t: Turn): String =
        (t.scenes.map { it.sahne.ses }.filter { it.isNotBlank() } + listOfNotNull(t.detay)).joinToString(" ").trim()

    private fun noteActivity() {
        lastActivity = idleChecks * IDLE_CHECK_MS
    }

    /**
     * "Hayır, doğrusu …" (or any message right after 👎) teaches the previous answer's correction
     * instead of starting a new question. Returns true when handled.
     */
    private fun learnCorrection(text: String): Boolean {
        val l = learning ?: return false
        if (!settings.state.value.selfLearning) return false
        val pending = correctionFor
        correctionFor = null
        val said = io.github.ahmetdurmus1218.jarvis.core.learning.CorrectionDetector.parse(text) ?: if (pending != null) text else return false
        val target = _turns.value.lastOrNull { it.id == pending } ?: _turns.value.lastOrNull { it.done && it.error == null && it.scenes.isNotEmpty() }
            ?: return false
        l.correct(target.user, answerText(target), said)
        val id = nextId++
        val reply = io.github.ahmetdurmus1218.jarvis.core.scene.VerifiedScene(
            io.github.ahmetdurmus1218.jarvis.core.scene.Sahne(
                "Teşekkürler, öğrendim. Bundan sonra \"${target.user.take(60)}\" gibi sorularda bunu dikkate alacağım.",
                io.github.ahmetdurmus1218.jarvis.core.scene.Gorsel(io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip.ALINTI, metin = said, kaynak = "Sizin düzeltmeniz"),
            ),
        )
        updateTurn(target.id) { it.copy(feedback = -1) }
        _turns.value = _turns.value + Turn(id, text, scenes = listOf(reply), done = true)
        say(reply.sahne.ses)
        return true
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        noteActivity()
        if (learnCorrection(clean)) return
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
            is AssistantEvent.Completed -> {
                updateTurn(id) {
                    it.copy(scenes = ev.reply.scenes, detay = ev.reply.detay, kaynaklar = ev.reply.kaynaklar, done = true, assistantText = ev.assistantText)
                }
                _turns.value.firstOrNull { it.id == id }?.let { learnFrom(it.user) }
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
        noteActivity()
        val stt = bridge.speechInput ?: return
        if (!stt.isAvailable()) {
            _partial.value = if (stt.ownModelGroup != null) {
                "Rick'in kendi Türkçe ses tanıması (Whisper) henüz kurulu değil. Aşağıdaki düğmeyle bir kez indirin; sonra tamamen internetsiz çalışır."
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

    /**
     * Section 5.5: lasting facts from the user's own words, in the background after the answer
     * (patterns always, the local model when one is installed). Never from tool output.
     */
    private fun learnFrom(userText: String) {
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            if (!memory.enabled()) return@launch
            val facts = io.github.ahmetdurmus1218.jarvis.core.memory.FactExtractor.byRules(userText) +
                (factModel()?.let { io.github.ahmetdurmus1218.jarvis.core.memory.FactExtractor.byModel(it, userText) } ?: emptyList())
            val added = facts.filter { memory.add(it) }
            if (added.isNotEmpty()) _learned.value = added
        }
    }

    private val _learned = MutableStateFlow<List<String>>(emptyList())
    /** Facts just added to memory (shown briefly). */
    val learned: StateFlow<List<String>> = _learned.asStateFlow()

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
        /** How often the idle loop looks, and how long Rick must be left alone before reading. */
        const val IDLE_CHECK_MS = 60_000L
        const val IDLE_BEFORE_RESEARCH_MS = 3 * 60_000L

        /** Pause between Rick finishing and listening again, so its own voice is not recorded. */
        const val CONTINUE_DELAY_MS = 350L
    }
}

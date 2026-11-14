package io.github.ahmetdurmus1218.jarvis.ui

import io.github.ahmetdurmus1218.jarvis.core.AssistantName
import io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant
import io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent
import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.memory.MemoryStore
import io.github.ahmetdurmus1218.jarvis.core.device.MediaKey
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand
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
import kotlinx.coroutines.flow.collectLatest
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
    /** Something Rick offered to do in this turn; done only after a yes. */
    val offer: io.github.ahmetdurmus1218.jarvis.core.assistant.Offer? = null,
    /** The user's answer to [offer] (null: not answered yet). */
    val offerAnswer: Boolean? = null,
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
    /** Where approved web searches are written down (the "Ağ" screen). */
    private val netLog: NetworkLogStore? = null,
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

    private var lastSilentNotice: String? = null

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

    // Hands-free standby state. Declared before init: the coroutines started there may run at once
    // (an immediate dispatcher) and must find all of this initialized.
    private val _standby = MutableStateFlow(false)
    /** True while the microphone waits for Rick's name or for claps (the listening sign on the screen). */
    val standby: StateFlow<Boolean> = _standby.asStateFlow()
    private var appVisible = false
    private var appAwake = true
    private var floating = false
    private var standbyJob: Job? = null
    private var standbyWatch: Job? = null
    private var decoding = false
    /** Utterances in a row that were not meant for Rick (the next standby then waits longer). */
    private var standbyMisses = 0
    private var micRefused = false
    private var listenAfterSpeech = false
    private val ear = io.github.ahmetdurmus1218.jarvis.core.stt.StandbyEar()
    /** Whether the microphone question was already put this time the app runs. */
    private var micAsked = false

    // What Rick's eyes show for a moment after a turn (declared before init, like the state above).
    private val _emotion = MutableStateFlow(io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion.NONE)
    /** A smile, joy, sadness or surprise for a few seconds after a turn; NONE the rest of the time. */
    val emotion: StateFlow<io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion> = _emotion.asStateFlow()
    private var emotionJob: Job? = null

    /** Last time the user did something; idle reading starts only after a quiet while. */
    private var lastActivity = 0L
    private var idleChecks = 0
    /** After 👎: the next message is the right answer for this turn. */
    private var correctionFor: Long? = null

    init {
        // Never stuck: an answer that takes far too long, or a microphone left open, is ended by itself.
        scope.launch {
            _mode.collectLatest { m ->
                when (m) {
                    CoreMode.THINKING -> {
                        delay(STUCK_THINKING_MS)
                        if (_mode.value == CoreMode.THINKING) {
                            stopEverything()
                            _partial.value = "Bu çok uzun sürdü, durdurdum. İsterseniz tekrar sorun."
                        }
                    }
                    CoreMode.LISTENING -> {
                        delay(STUCK_LISTENING_MS)
                        if (_mode.value == CoreMode.LISTENING) {
                            stopListening()
                            _partial.value = "Uzun süre ses gelmedi; dinlemeyi kapattım."
                        }
                    }
                    else -> {}
                }
            }
        }
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
        // Hands-free: the moment Rick stops being idle the standby microphone closes; it comes back by itself.
        scope.launch { _mode.collect { refreshStandby() } }
        // Each finished turn may show on Rick's face (turns restored from before do not).
        scope.launch {
            var seen = _turns.value.lastOrNull()?.id ?: -1L
            _turns.collect { all ->
                val t = all.lastOrNull() ?: return@collect
                if (!t.done || t.id == seen) return@collect
                seen = t.id
                feel(
                    if (t.error != null) io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion.SAD
                    else io.github.ahmetdurmus1218.jarvis.core.assistant.EmotionCue.of(t.user, answerText(t)),
                )
            }
        }
        val unwatch = MicUse.watch { refreshStandby() }
        scope.coroutineContext[Job]?.invokeOnCompletion { unwatch() }
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
                    tts.takeNotice()?.let { _partial.value = it }
                    val why = tts.silentReason
                    if (why != null && lastSilentNotice != why) {
                        lastSilentNotice = why
                        if (tts.ownModelGroup != null) {
                            _voiceMissing.value = why
                            _partial.value = "🔇 $why Sesli cevap için aşağıdaki düğmeyle ${AssistantName.SHORT}'in kendi Türkçe sesini indirin (internetsiz çalışır)."
                        } else {
                            _partial.value = "🔇 $why"
                        }
                    }
                }
                queued--
                if (queued <= 0) {
                    queued = 0
                    _subtitle.value = ""
                    if (_mode.value == CoreMode.SPEAKING) _mode.value = CoreMode.IDLE
                    if (listenAfterSpeech) {
                        listenAfterSpeech = false
                        delay(CONTINUE_DELAY_MS)
                        if (_mode.value == CoreMode.IDLE) startListening(quiet = true)
                    } else {
                        maybeContinueConversation()
                    }
                }
            }
        }
    }

    // ---- hands-free standby ----

    /** Whether standby also understands the name (Rick's own recognizer is installed), not only claps. */
    val standbyHearsName: Boolean get() = namesWanted(settings.state.value)

    /** Hands-free is on but the microphone was never allowed yet: the screen asks for one tap on the microphone. */
    val standbyNeedsMicrophone: Boolean
        get() = settings.state.value.handsFree && bridge.micStream?.permitted == false && bridge.speechInput != null

    /** Two claps heard in standby: whoever shows Rick may take them (the floating Rick wakes up) by returning true. */
    var onClaps: (() -> Boolean)? = null

    /** The window is on the screen (false: in the background, where only the floating Rick may listen). */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        if (visible) micRefused = false
        askMicrophoneOnce()
        watchStandby()
        refreshStandby()
    }

    /**
     * While Rick is shown somewhere, looks every couple of seconds whether standby should be running:
     * an answer that ended, a setting that changed or a recognizer that was just installed bring it back.
     */
    private fun watchStandby() {
        val shown = appVisible || floating
        if (!shown) {
            standbyWatch?.cancel()
            standbyWatch = null
        } else if (standbyWatch == null) {
            standbyWatch = scope.launch {
                while (true) {
                    delay(STANDBY_CHECK_MS)
                    refreshStandby()
                }
            }
        }
    }

    /** The window is in normal use (false: asleep, waking up or in the setup wizard). */
    fun setAppAwake(awake: Boolean) {
        appAwake = awake
        askMicrophoneOnce()
        refreshStandby()
    }

    /**
     * Continuous listening is on but the microphone was never allowed: ask once, as soon as Rick is on screen
     * (not in the setup wizard, not asleep), so listening starts by itself instead of waiting for a tap.
     */
    private fun askMicrophoneOnce() {
        val mic = bridge.micStream ?: return
        if (micAsked || !appVisible || !appAwake || !settings.state.value.handsFree || mic.permitted) return
        micAsked = true
        scope.launch {
            if (mic.requestPermission()) {
                micRefused = false
                refreshStandby()
            }
        }
    }

    /** The on/off button of continuous listening: takes effect at once. */
    fun setHandsFree(on: Boolean) {
        settings.update { it.copy(handsFree = on) }
        if (on) {
            micRefused = false
            micAsked = false
            askMicrophoneOnce()
        }
        refreshStandby()
    }

    /** The floating Rick is running over other apps. */
    fun setFloating(on: Boolean) {
        floating = on
        if (on) micRefused = false
        watchStandby()
        refreshStandby()
    }

    private fun namesWanted(s: Settings) = s.handsFree && bridge.speechInput?.canTranscribe == true
    private fun clapsWanted(s: Settings) = if (appVisible) s.handsFree else s.bubbleClapWake

    private fun standbyWanted(): Boolean {
        val present = if (appVisible) appAwake else floating
        if (!present || _mode.value != CoreMode.IDLE || decoding || micRefused || MicUse.taken) return false
        if (askJob?.isActive == true || queued > 0 || bridge.micStream?.permitted != true) return false
        val s = settings.state.value
        return namesWanted(s) || clapsWanted(s)
    }

    private fun refreshStandby() {
        val want = standbyWanted()
        if (!want) {
            stopStandby()
            if (!decoding) _standby.value = false
            return
        }
        if (standbyJob != null) return
        val mic = bridge.micStream ?: return
        standbyJob = scope.launch {
            // Lets Rick's own voice ring out, and a follow-up question open the microphone first. After
            // talk that was not meant for Rick the pause grows: a noisy room must not keep the recognizer
            // (and the processor) busy all the time.
            delay(STANDBY_DELAY_MS + STANDBY_BACKOFF_MS[minOf(standbyMisses, STANDBY_BACKOFF_MS.lastIndex)])
            if (!standbyWanted()) { standbyJob = null; return@launch }
            val s = settings.state.value
            val names = namesWanted(s)
            ear.claps = if (clapsWanted(s)) {
                io.github.ahmetdurmus1218.jarvis.core.stt.ClapDetector(threshold = s.clapThreshold).apply { rhythm = s.clapRhythm }
            } else {
                null
            }
            ear.opened()
            val me = coroutineContext[Job]
            val ok = mic.start { samples, n ->
                val ev = ear.feed(samples, n) ?: return@start
                if (ev is io.github.ahmetdurmus1218.jarvis.core.stt.StandbyEar.Event.Claps || names) {
                    scope.launch { if (standbyJob === me) onStandby(ev) }
                }
            }
            if (!ok) {
                // No microphone or no permission: stay quiet about it; the microphone button explains.
                micRefused = true
                standbyJob = null
                _standby.value = false
                return@launch
            }
            _standby.value = true
        }
    }

    /** Closes the standby microphone (only if standby has it). */
    private fun stopStandby() {
        val job = standbyJob ?: return
        standbyJob = null
        job.cancel()
        bridge.micStream?.stop()
    }

    private suspend fun onStandby(ev: io.github.ahmetdurmus1218.jarvis.core.stt.StandbyEar.Event) {
        if (_mode.value != CoreMode.IDLE) return
        when (ev) {
            io.github.ahmetdurmus1218.jarvis.core.stt.StandbyEar.Event.Claps -> {
                stopStandby()
                if (onClaps?.invoke() == true) return
                _partial.value = "👏 Dinliyorum"
                startListening()
            }
            is io.github.ahmetdurmus1218.jarvis.core.stt.StandbyEar.Event.Utterance -> {
                // The recognizer needs the processor now; the microphone opens again right after.
                stopStandby()
                decoding = true
                val text = try {
                    bridge.speechInput?.transcribe(ev.samples)
                } finally {
                    decoding = false
                }
                if (_mode.value != CoreMode.IDLE || !settings.state.value.handsFree) return
                when (val heard = io.github.ahmetdurmus1218.jarvis.core.stt.WakeCall.parse(text.orEmpty())) {
                    // Not said to Rick: dropped here, never shown or kept.
                    io.github.ahmetdurmus1218.jarvis.core.stt.WakeCall.Heard.NotForMe -> {
                        standbyMisses++
                        refreshStandby()
                    }
                    io.github.ahmetdurmus1218.jarvis.core.stt.WakeCall.Heard.NameOnly -> answerCall()
                    is io.github.ahmetdurmus1218.jarvis.core.stt.WakeCall.Heard.Request -> {
                        standbyMisses = 0
                        continueAfter = nextId
                        send(heard.text)
                    }
                }
            }
        }
    }

    /** Only the name was said: "Efendim?", then the microphone opens for the request itself. */
    private fun answerCall() {
        noteActivity()
        if (bridge.speechOutput != null && settings.state.value.voiceEnabled) {
            listenAfterSpeech = true
            say(CALL_ANSWER)
        } else {
            _partial.value = CALL_ANSWER
            startListening(quiet = true)
        }
    }

    /** Turn started by voice whose answer should be followed by listening again (conversation mode). */
    private var continueAfter: Long? = null

    /** Conversation mode: once a spoken question is fully answered and spoken, listen again. */
    /** A turn after which Rick listens once even without conversation mode (the floating Rick's wake-up). */
    private var listenOnce: Long? = null

    private fun maybeContinueConversation() {
        val id = continueAfter ?: return
        // Hands-free: a question Rick asked ("Feneri açayım mı?") is answered without saying the name again.
        val asked = settings.state.value.handsFree && pendingOffer()?.id == id
        if (!settings.state.value.conversationMode && listenOnce != id && !asked) return
        val turn = _turns.value.firstOrNull { it.id == id } ?: return
        if (!turn.done || queued > 0 || _mode.value != CoreMode.IDLE) return
        continueAfter = null
        scope.launch {
            delay(CONTINUE_DELAY_MS)
            if (_mode.value == CoreMode.IDLE && currentTurnId == id) startListening(quiet = true)
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

    /**
     * Last turns as chat history. Assistant turns are given back in the JSON format the model must
     * produce, but only what was said (no cards, details or sources): every token here is read again
     * before each answer, so a short history means a faster answer.
     */
    private fun history(): List<ChatMessage> = _turns.value.filter { it.done && it.error == null && !it.interrupted }.takeLast(HISTORY_TURNS).flatMap { t ->
        val said = t.scenes.map { it.sahne.ses }.filter { it.isNotBlank() }
        listOf(
            ChatMessage(Role.USER, t.user),
            ChatMessage(Role.ASSISTANT, SceneJson.encodeToString(Reply.serializer(), Reply(said.map { Sahne(it) }))),
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

    /** Shows a feeling on Rick's face for a few seconds (NONE: nothing changes). */
    fun feel(e: io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion, ms: Long = EMOTION_MS) {
        if (e == io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion.NONE) return
        _emotion.value = e
        emotionJob?.cancel()
        emotionJob = scope.launch {
            delay(ms)
            _emotion.value = io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion.NONE
        }
    }

    /** The user's verdict on an answer: 👍 keeps it as a good example, 👎 asks for the right answer. */
    fun feedback(turnId: Long, good: Boolean) {
        val turn = _turns.value.firstOrNull { it.id == turnId } ?: return
        noteActivity()
        feel(if (good) io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion.JOY else io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion.SAD)
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
        standbyMisses = 0
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
        // An open offer ("Feneri açayım mı?") is answered by "evet" / "hayır"; anything else moves on.
        pendingOffer()?.let { t ->
            val offer = t.offer
            // "Hangisini açayım?" — the answer is the song / video itself.
            if (offer is io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.AskWhat) {
                if (io.github.ahmetdurmus1218.jarvis.core.assistant.OfferReply.parse(clean) == false) { answerOffer(t.id, false, clean); return }
                if (io.github.ahmetdurmus1218.jarvis.core.router.Router.route(clean) !is io.github.ahmetdurmus1218.jarvis.core.router.Route.Quick) {
                    answerWhat(t.id, offer, clean)
                    return
                }
            }
            // "online ol, bak" after "internette bakayım mı?" is a yes.
            if (offer is io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.WebLookup && io.github.ahmetdurmus1218.jarvis.core.assistant.WebCommand.parse(clean) == "") { answerOffer(t.id, true, clean); return }
            when (io.github.ahmetdurmus1218.jarvis.core.assistant.OfferReply.parse(clean)) {
                true -> { answerOffer(t.id, true, clean); return }
                false -> { answerOffer(t.id, false, clean); return }
                null -> updateTurn(t.id) { it.copy(offerAnswer = false) }
            }
        }
        if (learnCorrection(clean)) return
        // "geri", "ana ekran", "aşağı kaydır": move around without a new answer covering the screen.
        when (val nav = io.github.ahmetdurmus1218.jarvis.core.router.QuickCommandRouter.route(clean)) {
            QuickCommand.Back, QuickCommand.MainScreen, is QuickCommand.Page -> {
                if (nav == QuickCommand.MainScreen) stopEverything()
                _appCommands.tryEmit(nav)
                _partial.value = when (nav) { QuickCommand.Back -> "↩ Geri"; QuickCommand.MainScreen -> "🏠 Ana ekran"; else -> "" }
                return
            }
            else -> {}
        }
        stopSpeaking()
        askJob?.cancel()
        factJob?.cancel()
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
                val inApp = ev.command is QuickCommand.ShowPlanet || ev.command is QuickCommand.HandControl || ev.command is QuickCommand.Home ||
                    ev.command == QuickCommand.Sleep || ev.command == QuickCommand.Back || ev.command == QuickCommand.MainScreen || ev.command is QuickCommand.Page
                if (inApp) _appCommands.tryEmit(ev.command)
                val error = if (inApp) null else bridge.execute(ev.command)
                if (error == null) logHandoff(ev.command)
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
            is AssistantEvent.Offered -> {
                updateTurn(id) { it.copy(scenes = listOf(ev.scene), offer = ev.offer, done = true) }
                say(ev.scene.sahne.ses)
            }
            is AssistantEvent.NewsRequested -> {
                updateTurn(id) { it.copy(scenes = listOf(ev.scene)) }
                runNews(id)
            }
            is AssistantEvent.WebRequested -> {
                updateTurn(id) { it.copy(scenes = listOf(ev.scene)) }
                runLookup(id, ev.lookup)
            }
        }
        if (ev is AssistantEvent.Completed || ev is AssistantEvent.Quick || ev is AssistantEvent.Failed || ev is AssistantEvent.Offered) {
            if (queued == 0 && _mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE
            if (ev !is AssistantEvent.Failed) maybeContinueConversation() else continueAfter = null
        }
    }

    // ---- offers ("… ayım mı?") ----

    /** The newest turn, if it holds an offer nobody has answered yet. */
    private fun pendingOffer(): Turn? = _turns.value.lastOrNull()?.takeIf { it.offer != null && it.offerAnswer == null && it.done }

    /** The user's answer to the offer in [turnId], by button or by voice ([said]). */
    fun answerOffer(turnId: Long, yes: Boolean, said: String? = null) {
        val turn = _turns.value.firstOrNull { it.id == turnId } ?: return
        val offer = turn.offer ?: return
        if (turn.offerAnswer != null) return
        noteActivity()
        stopSpeaking()
        askJob?.cancel()
        updateTurn(turnId) { it.copy(offerAnswer = yes) }
        val id = nextId++
        currentTurnId = id
        // Hands-free: after the result is spoken, listen again if the offer itself was answered by voice.
        if (said != null) continueAfter = id
        _turns.value = _turns.value + Turn(id, said ?: if (yes) "Evet" else "Hayır")
        if (!yes) {
            finishWith(id, "Peki, yapmıyorum.")
            return
        }
        when (offer) {
            is io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.Action -> askJob = scope.launch {
                val error = bridge.execute(offer.command)
                if (error == null) logHandoff(offer.command)
                val scene = if (error == null) {
                    io.github.ahmetdurmus1218.jarvis.core.assistant.QuickResponder.respond(offer.command, io.github.ahmetdurmus1218.jarvis.core.time.NowProvider.System.now())
                } else {
                    Sahne(error)
                }
                finishWith(id, scene)
            }
            is io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.WebLookup -> lookUpOnline(id, offer)
            is io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.AskWhat -> finishWith(id, "Peki.")
            io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.News -> {
                _mode.value = CoreMode.THINKING
                askJob = scope.launch { runNews(id) }
            }
        }
    }

    /** The answer to "Hangisini açayım?": open [said] in the app the user named before. */
    private fun answerWhat(turnId: Long, offer: io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.AskWhat, said: String) {
        noteActivity()
        stopSpeaking()
        askJob?.cancel()
        updateTurn(turnId) { it.copy(offerAnswer = true) }
        val id = nextId++
        currentTurnId = id
        continueAfter = null
        _turns.value = _turns.value + Turn(id, said)
        val n = io.github.ahmetdurmus1218.jarvis.core.text.Turkish.normalize(said).replace(Regex("[?.!]+$"), "").trim()
            .replace(Regex("\\s+(?:ac|cal|oynat|dinle|dinlet|izle|izlet|koy|baslat)\\w*(?: misin| musun| lutfen)?$"), "")
        val query = io.github.ahmetdurmus1218.jarvis.core.device.DeviceCommands.searchWords(said, n)
        if (query.isEmpty()) {
            finishWith(id, "Adını anlayamadım; tekrar söyler misiniz? Örneğin: YouTube'dan Tarkan Şımarık aç.")
            return
        }
        val cmd = QuickCommand.Device(io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.OpenIn(offer.app, query))
        askJob = scope.launch {
            val error = bridge.execute(cmd)
            if (error == null) logHandoff(cmd)
            finishWith(id, error ?: io.github.ahmetdurmus1218.jarvis.core.device.DeviceCommands.describe(cmd.action))
        }
    }

    /**
     * A search handed to YouTube / Spotify / the browser goes online from that app, not from Rick;
     * it is still written to the network log so the user sees what left with their command.
     */
    private fun logHandoff(cmd: QuickCommand) {
        val a = (cmd as? QuickCommand.Device)?.action as? io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.OpenIn ?: return
        val now = io.github.ahmetdurmus1218.jarvis.core.time.NowProvider.System.now().epochMillis
        netLog?.record(io.github.ahmetdurmus1218.jarvis.core.netlog.NetworkEvent(now, a.app.host, 0, "${a.app.label} uygulamasına aktarıldı: \"${a.query}\"", "sizin komutunuz", "uygulamaya aktarıldı"))
    }

    /** Rick cannot look it up itself ([reason]); the browser can, if the user says yes. */
    private fun offerBrowser(id: Long, reason: String, query: String) {
        val cmd = QuickCommand.Device(io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.OpenIn(io.github.ahmetdurmus1218.jarvis.core.device.OutsideApp.GOOGLE, query))
        val scene = VerifiedScene(Sahne("$reason İsterseniz tarayıcıda Google'da \"$query\" aramasını açayım."))
        updateTurn(id) { it.copy(scenes = listOf(scene), offer = io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.Action(cmd), done = true) }
        say(scene.sahne.ses)
        if (queued == 0 && _mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE
        maybeContinueConversation()
    }

    private fun finishWith(id: Long, text: String) = finishWith(id, Sahne(text))

    private fun finishWith(id: Long, scene: Sahne) {
        updateTurn(id) { it.copy(scenes = listOf(VerifiedScene(scene)), done = true) }
        say(scene.ses)
        if (queued == 0 && _mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE
        maybeContinueConversation()
    }

    /**
     * The approved web search: the exact words that will leave the device are shown first, then the
     * platform approval (fingerprint / PIN on the phone, UAC on the PC) is asked for this one search.
     * The answer is built from the pages like any library answer and the event is logged.
     */
    private fun lookUpOnline(id: Long, offer: io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.WebLookup) {
        _mode.value = CoreMode.THINKING
        askJob = scope.launch { runLookup(id, offer) }
    }

    private suspend fun runLookup(id: Long, offer: io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.WebLookup) {
        val host = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.HOST
        approvedFetch(
            id,
            url = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.searchUrl(offer.query),
            host = host,
            what = "Wikipedia araması: \"${offer.query}\"",
            chip = "🌐 $host ← \"${offer.query}\"",
            waiting = "🌐 Yalnızca \"${offer.query}\" araması $host adresine gidecek. Onayınızı bekliyorum…",
            title = "İnternette ara · $host",
            subtitle = "Gidecek metin yalnızca: \"${offer.query}\". Başka hiçbir şey gönderilmez.",
            busy = "🌐 Wikipedia'da bakıyorum…",
            browserQuery = offer.query,
        ) { body ->
            val pages = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.parse(body, offer.query)
            assistant.answerFromWeb(offer.question, pages, history()).collect { ev -> handle(id, ev) }
        }
    }

    /** "Günün haberleri": the fixed headlines feed (no text of the user's goes out), after the approval. */
    private suspend fun runNews(id: Long) {
        val host = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews.HOST
        approvedFetch(
            id,
            url = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews.URL,
            host = host,
            what = "Günün haberleri (${io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews.SOURCE})",
            chip = "📰 $host",
            waiting = "📰 ${io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews.SOURCE} başlıkları okunacak; sizden hiçbir şey gönderilmez. Onayınızı bekliyorum…",
            title = "Günün haberleri · $host",
            subtitle = "Yalnızca ${io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews.SOURCE}'nin herkese açık haber listesi alınır. Sizinle ilgili hiçbir şey gönderilmez.",
            busy = "📰 Haberlere bakıyorum…",
            browserQuery = "son dakika haberler",
        ) { body ->
            assistant.newsReply(io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews.parse(body)).collect { ev -> handle(id, ev) }
        }
    }

    /** Rick offers the news (for example right after waking up in the morning); nothing goes out before a yes. */
    fun offerNews(greeting: String, listen: Boolean = false) {
        val id = nextId++
        currentTurnId = id
        if (listen) { continueAfter = id; listenOnce = id }
        val scene = VerifiedScene(Sahne("$greeting Günün haberlerini okuyayım mı?"))
        _turns.value = _turns.value + Turn(id, "👏 Uyandırdınız", scenes = listOf(scene), done = true, offer = io.github.ahmetdurmus1218.jarvis.core.assistant.Offer.News)
        say(scene.sahne.ses)
        maybeContinueConversation()
    }

    /**
     * One approved request through the gate: full offline mode and a missing gate are said plainly
     * (with the browser offered instead), the platform approval is asked for this request only, and
     * the result is written to the network log before [onBody] reads it.
     */
    private suspend fun approvedFetch(
        id: Long,
        url: String,
        host: String,
        what: String,
        chip: String,
        waiting: String,
        title: String,
        subtitle: String,
        busy: String,
        browserQuery: String,
        onBody: suspend (String) -> Unit,
    ) {
        _mode.value = CoreMode.THINKING
        try {
            val gate = bridge.web
            if (settings.state.value.fullOffline) {
                finishWith(id, "Tam çevrimdışı mod açık, o yüzden internete çıkmadım. İsterseniz Ayarlar'dan kapatabilirsiniz.")
                return
            }
            if (gate == null || bridge.downloader.state.value !is DownloadState.Ready) {
                val reason = if (bridge.platform == io.github.ahmetdurmus1218.jarvis.core.models.Platform.ANDROID) {
                    "Ben internete yalnızca Rick Kapı uygulamasıyla çıkabiliyorum ve o bu telefonda kurulu değil (Releases sayfasındaki rick-kapi.apk)."
                } else {
                    "İnternet kapım (rick-kapi) şu an hazır değil."
                }
                offerBrowser(id, reason, browserQuery)
                return
            }
            updateTurn(id) { it.copy(tools = listOf(chip)) }
            _partial.value = waiting
            val approval = bridge.approver.confirm(title, subtitle)
            val now = io.github.ahmetdurmus1218.jarvis.core.time.NowProvider.System.now().epochMillis
            if (approval !is ApprovalResult.Approved) {
                netLog?.record(io.github.ahmetdurmus1218.jarvis.core.netlog.NetworkEvent(now, host, 0, what, "-", "reddedildi"))
                _partial.value = ""
                finishWith(id, "Peki, internete çıkmadım.")
                return
            }
            _partial.value = busy
            val result = try {
                gate.fetch(url)
            } finally {
                runCatching { gate.done() }
            }
            _partial.value = ""
            when (result) {
                is WebFetch.Failed -> {
                    netLog?.record(io.github.ahmetdurmus1218.jarvis.core.netlog.NetworkEvent(now, host, 0, what, approval.method.label, "hata: ${result.message}"))
                    offerBrowser(id, "İnternete ulaşamadım: ${result.message.trimEnd('.')}.", browserQuery)
                }
                is WebFetch.Ok -> {
                    netLog?.record(io.github.ahmetdurmus1218.jarvis.core.netlog.NetworkEvent(now, result.host, result.bytes, what, approval.method.label, "tamam"))
                    onBody(result.body)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            updateTurn(id) { it.copy(done = true, error = e.message ?: "Beklenmeyen hata") }
        } finally {
            _partial.value = ""
            if (currentTurnId == id && _mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE
        }
    }

    // ---- push-to-talk ----

    /** @param quiet opened by Rick itself (a follow-up): when nothing is said it simply closes again. */
    fun startListening(quiet: Boolean = false) {
        noteActivity()
        // The standby microphone must be closed before the recognizer opens its own.
        stopStandby()
        listenAfterSpeech = false
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
                _partial.value = if (quiet && message.startsWith(NOTHING_HEARD)) "" else message
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

    /** Rick woken up (two claps at the floating Rick): says [text], then listens once. */
    fun greet(text: String, listen: Boolean) {
        val id = nextId++
        currentTurnId = id
        if (listen) { continueAfter = id; listenOnce = id }
        _turns.value = _turns.value + Turn(id, "👏 Uyandırdınız", scenes = listOf(VerifiedScene(Sahne(text))), done = true)
        say(text)
        maybeContinueConversation()
    }

    /**
     * Section 5.5: lasting facts from the user's own words, in the background after the answer
     * (patterns always, the local model when one is installed). Never from tool output.
     */
    private fun learnFrom(userText: String) {
        val extractor = io.github.ahmetdurmus1218.jarvis.core.memory.FactExtractor
        factJob?.cancel()
        factJob = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            if (!memory.enabled()) return@launch
            val byRules = extractor.byRules(userText)
            addFacts(byRules)
            // The model is asked only about sentences that talk about the user, and only once Rick has
            // finished speaking: the model is shared, so this must never hold up the next answer.
            val model = factModel()?.takeIf { extractor.worthAsking(userText) } ?: return@launch
            while (_mode.value != CoreMode.IDLE || queued > 0) delay(300)
            addFacts(extractor.byModel(model, userText))
        }
    }

    private suspend fun addFacts(facts: List<String>) {
        val added = facts.filter { memory.add(it) }
        if (added.isNotEmpty()) _learned.value = added
    }

    /** Background fact extraction of the last answer; a new question cancels it. */
    private var factJob: Job? = null

    private val _learned = MutableStateFlow<List<String>>(emptyList())
    /** Facts just added to memory (shown briefly). */
    val learned: StateFlow<List<String>> = _learned.asStateFlow()

    /** Replaces the caption above the input row (e.g. "Ses tanıma hazır" after Whisper is installed). */
    /** Set when a hand ✋ paused the music, so the next ✋ plays it again. */
    private var pausedByHand = false

    /**
     * A hand command, by what is going on: while music plays, swipes change the song, ✋ pauses and
     * plays, and the wrist turns the volume; otherwise swipes turn Rick's page ([page]: +1 forward,
     * -1 back; null where there is no page, e.g. over other apps). 👍 listens, ✊ quiets Rick.
     * Returns what was done, for a short note on the screen (null: nothing to do).
     */
    fun onHand(cmd: HandCommand, page: ((Int) -> Unit)?): String? {
        noteActivity()
        val music = bridge.mediaPlaying() && _mode.value != CoreMode.SPEAKING
        fun device(a: io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction) {
            scope.launch { bridge.execute(QuickCommand.Device(a)) }
        }
        fun media(k: MediaKey) = device(io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.Media(k))
        fun volume(v: io.github.ahmetdurmus1218.jarvis.core.device.VolumeChange) = device(io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.Volume(v))
        return when (cmd) {
            HandCommand.NEXT -> if (music) { media(MediaKey.NEXT); "⏭ Sonraki şarkı" } else page?.let { it(1); "⬇ Sonraki sayfa" }
            HandCommand.PREVIOUS -> if (music) { media(MediaKey.PREVIOUS); "⏮ Önceki şarkı" } else page?.let { it(-1); "⬆ Önceki sayfa" }
            HandCommand.SCROLL_DOWN -> page?.let { it(1); "⬇ Aşağı" }
            HandCommand.SCROLL_UP -> page?.let { it(-1); "⬆ Yukarı" }
            HandCommand.PAUSE_PLAY -> when {
                music -> { media(MediaKey.PAUSE); pausedByHand = true; "⏸ Durdurdum" }
                _mode.value == CoreMode.SPEAKING -> { stopSpeaking(); "🤫 Sustum" }
                pausedByHand -> { media(MediaKey.PLAY); pausedByHand = false; "▶ Devam" }
                else -> null
            }
            HandCommand.LISTEN -> { startListening(); "🎤 Dinliyorum" }
            HandCommand.QUIET -> { stopSpeaking(); if (music) media(MediaKey.PAUSE); "🤫 Sustum" }
            HandCommand.VOLUME_UP -> { volume(io.github.ahmetdurmus1218.jarvis.core.device.VolumeChange.UP); "🔊 Ses +" }
            HandCommand.VOLUME_DOWN -> { volume(io.github.ahmetdurmus1218.jarvis.core.device.VolumeChange.DOWN); "🔉 Ses −" }
            HandCommand.BACK -> { _appCommands.tryEmit(QuickCommand.Back); "↩ Geri" }
            HandCommand.HOME -> { _appCommands.tryEmit(QuickCommand.MainScreen); "🏠 Ana ekran" }
            HandCommand.EXIT -> { stopEverything(); _appCommands.tryEmit(QuickCommand.MainScreen); "👋 Hepsini kapattım" }
        }
    }

    /** Never stuck: stops speaking, listening and any answer in progress. */
    fun stopEverything() {
        continueAfter = null
        listenAfterSpeech = false
        if (_mode.value == CoreMode.LISTENING) stopListening()
        stopSpeaking()
        askJob?.cancel()
        currentTurnId?.let { id -> updateTurn(id) { if (it.done) it else it.copy(done = true, interrupted = true) } }
        if (_mode.value == CoreMode.THINKING) _mode.value = CoreMode.IDLE
    }

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
        /** How long a feeling stays on Rick's face. */
        const val EMOTION_MS = 6_000L
        /** How often the idle loop looks, and how long Rick must be left alone before reading. */
        const val IDLE_CHECK_MS = 60_000L
        const val IDLE_BEFORE_RESEARCH_MS = 3 * 60_000L
        /** Never stuck: the longest Rick may "think" (a slow first model load included) or keep the microphone open. */
        const val STUCK_THINKING_MS = 240_000L
        const val STUCK_LISTENING_MS = 60_000L

        /** Pause between Rick finishing and listening again, so its own voice is not recorded. */
        const val CONTINUE_DELAY_MS = 350L

        /**
         * Hands-free: how long after Rick falls idle the standby microphone opens (long enough that it
         * never opens and closes in the short pauses of an answer), and how often that is re-checked.
         */
        const val STANDBY_DELAY_MS = 1_500L
        /** Extra pause before standby listens again, by how many utterances in a row were not for Rick. */
        val STANDBY_BACKOFF_MS = longArrayOf(0L, 1_500L, 4_000L, 8_000L)
        const val STANDBY_CHECK_MS = 2_000L
        /** What Rick says when only its name was called. */
        const val CALL_ANSWER = "Efendim?"

        /** Earlier exchanges given to the model with each question. */
        const val HISTORY_TURNS = 4
    }
}

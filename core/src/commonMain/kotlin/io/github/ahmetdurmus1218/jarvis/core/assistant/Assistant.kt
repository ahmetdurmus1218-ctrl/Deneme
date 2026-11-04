package io.github.ahmetdurmus1218.jarvis.core.assistant

import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.GenerationConfig
import io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.llm.ThinkStripper
import io.github.ahmetdurmus1218.jarvis.core.memory.MemoryStore
import io.github.ahmetdurmus1218.jarvis.core.prompt.SystemPrompt
import io.github.ahmetdurmus1218.jarvis.core.router.Budget
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.router.Route
import io.github.ahmetdurmus1218.jarvis.core.router.Router
import io.github.ahmetdurmus1218.jarvis.core.safety.InjectionScanner
import io.github.ahmetdurmus1218.jarvis.core.scene.Gorsel
import io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip
import io.github.ahmetdurmus1218.jarvis.core.scene.Kaynak
import io.github.ahmetdurmus1218.jarvis.core.scene.Reply
import io.github.ahmetdurmus1218.jarvis.core.scene.Sahne
import io.github.ahmetdurmus1218.jarvis.core.scene.SceneJson
import io.github.ahmetdurmus1218.jarvis.core.scene.SceneStreamParser
import io.github.ahmetdurmus1218.jarvis.core.scene.VerifiedReply
import io.github.ahmetdurmus1218.jarvis.core.scene.VerifiedScene
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import io.github.ahmetdurmus1218.jarvis.core.tools.ToolOutput
import io.github.ahmetdurmus1218.jarvis.core.tools.ToolRegistry
import io.github.ahmetdurmus1218.jarvis.core.tools.Trust
import io.github.ahmetdurmus1218.jarvis.core.verify.Verifier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface AssistantEvent {
    /** A ready-made command. The platform executes it (alarm, timer, app...) and shows [scene]. */
    data class Quick(val command: QuickCommand, val scene: VerifiedScene) : AssistantEvent
    data class ToolCall(val name: String, val input: String, val output: String, val ok: Boolean) : AssistantEvent
    /** A scene is complete: show it and start speaking it right away. */
    data class SceneReady(val index: Int, val scene: VerifiedScene) : AssistantEvent
    data class Completed(val reply: VerifiedReply, val assistantText: String) : AssistantEvent
    data class Failed(val message: String) : AssistantEvent
    /** Rick proposes something ([scene] asks); it is done only if the user says yes. */
    data class Offered(val offer: Offer, val scene: VerifiedScene) : AssistantEvent
    /** The user asked for an online lookup in so many words: go straight to the approval. */
    data class WebRequested(val lookup: Offer.WebLookup, val scene: VerifiedScene) : AssistantEvent
}

/**
 * The assistant brain shared by Android and desktop:
 * router → (knowledge tools) → LLM with function calling (max 5 rounds, parallel tools) →
 * streaming scene parser → verification (section 5.4).
 */
class Assistant(
    private val engine: LlmEngine,
    private val tools: ToolRegistry,
    private val memory: MemoryStore,
    private val now: NowProvider = NowProvider.System,
    /** The simulated home answered by home quick commands (null: not available). */
    private val home: io.github.ahmetdurmus1218.jarvis.core.home.SmartHome? = null,
    /** The user's books, for the library commands (null: not available). */
    private val library: io.github.ahmetdurmus1218.jarvis.core.library.Library? = null,
    private val todos: io.github.ahmetdurmus1218.jarvis.core.todo.TodoList? = null,
    /** The offline daily briefing's facts (null: no briefing). */
    private val briefing: (suspend () -> io.github.ahmetdurmus1218.jarvis.core.briefing.Briefing.Input)? = null,
    /** Lessons from the user's corrections / likes and the idle research notebook (null: off). */
    private val learning: (() -> io.github.ahmetdurmus1218.jarvis.core.learning.Learning?)? = null,
    /** True when a Wikipedia web search may be offered (a gate exists and full offline mode is off). */
    private val webOffer: () -> Boolean = { false },
    /** Device actions this platform can really do (the PC has no torch and cannot call). */
    private val canOffer: (QuickCommand) -> Boolean = { true },
    /** True when Rick may offer to open a search in the browser (not in full offline mode). */
    private val browserOffer: () -> Boolean = { false },
) {
    companion object {
        const val MAX_TOOL_ROUNDS = 5
        const val NO_SOURCE_TEXT = "Bu bilgi kütüphanemde yok."
        fun webOfferText(query: String) =
            "Bunu kütüphanemde bulamadım. İsterseniz internette, Wikipedia'da bakayım; yalnızca \"$query\" araması gider."
        fun browserOfferText(query: String) =
            "Bunu kütüphanemde bulamadım. İsterseniz tarayıcıda Google'da \"$query\" aramasını açayım."
        const val ASK_WHAT_TEXT = "Tabii. Hangisini açayım? Şarkının ya da sanatçının adını söyleyin."

        /** The user's last real question in [history] (not a yes / no or a "go online"). */
        fun lastQuestion(history: List<ChatMessage>): String? = history.lastOrNull {
            it.role == Role.USER && WebCommand.parse(it.content) == null && OfferReply.parse(it.content) == null && it.content.isNotBlank()
        }?.content
        const val NO_VISION_TEXT = "Görmek için gerçek bir model gerekiyor. Telefonda Gemma 4'ü, bilgisayarda modelin görme eklentisini Modeller ekranından indirin."
        private const val REPAIR_PROMPT =
            "Önceki cevabın geçerli JSON değildi. Aynı cevabı yalnızca belirtilen JSON biçiminde, başka hiçbir metin olmadan yeniden yaz."
    }

    fun ask(userText: String, history: List<ChatMessage> = emptyList()): Flow<AssistantEvent> = flow {
        // "internette … ara", "online ol, bak": the user asked to go online; only the approval is left.
        WebCommand.parse(userText)?.let { said ->
            val question = said.ifEmpty { lastQuestion(history).orEmpty() }
            if (question.isEmpty()) {
                val scene = VerifiedScene(Sahne("Neye bakayım? Örneğin: internette Çanakkale Savaşı ara."))
                emit(AssistantEvent.SceneReady(0, scene))
                emit(AssistantEvent.Completed(VerifiedReply(listOf(scene), null, emptyList()), ""))
                return@flow
            }
            // Today's weather or rates are not in an encyclopedia: the browser's search is the way.
            if (LiveInfo.matches(question) && canOffer(googleSearch(question))) {
                val cmd = googleSearch(question.trim().trimEnd('?', '.', '!').take(OnlineWikipedia.MAX_QUERY))
                emit(AssistantEvent.Quick(cmd, VerifiedScene(QuickResponder.respond(cmd, now.now()))))
                return@flow
            }
            val query = io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiQuery.clean(question).take(OnlineWikipedia.MAX_QUERY)
            emit(AssistantEvent.WebRequested(Offer.WebLookup(query, question), VerifiedScene(Sahne("Tamam, internette \"$query\" için bakıyorum."))))
            return@flow
        }
        briefing?.let { input ->
            if (io.github.ahmetdurmus1218.jarvis.core.briefing.Briefing.matches(userText)) {
                val scenes = io.github.ahmetdurmus1218.jarvis.core.briefing.Briefing.scenes(input()).map { VerifiedScene(it) }
                scenes.forEachIndexed { i, s -> emit(AssistantEvent.SceneReady(i, s)) }
                val text = SceneJson.encodeToString(Reply.serializer(), Reply(scenes.map { it.sahne }))
                emit(AssistantEvent.Completed(VerifiedReply(scenes, null, emptyList()), text))
                return@flow
            }
        }
        todos?.let { list ->
            io.github.ahmetdurmus1218.jarvis.core.todo.TodoCommands.parse(userText, list)?.let { action ->
                val said = io.github.ahmetdurmus1218.jarvis.core.todo.TodoCommands.apply(list, action)
                emit(AssistantEvent.Quick(QuickCommand.Todo(action), VerifiedScene(Sahne(said))))
                return@flow
            }
        }
        library?.let { lib ->
            io.github.ahmetdurmus1218.jarvis.core.library.LibraryCommands.parse(userText, lib)?.let { action ->
                val said = io.github.ahmetdurmus1218.jarvis.core.library.LibraryCommands.apply(lib, action)
                emit(AssistantEvent.Quick(QuickCommand.Library(action), VerifiedScene(Sahne(said))))
                return@flow
            }
        }
        val route = Router.route(userText)
        if (route is Route.Quick) {
            val cmd = route.command
            // "YouTube'dan bir şarkı aç": which one? The next sentence answers.
            val openIn = (cmd as? QuickCommand.Device)?.action as? io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.OpenIn
            if (openIn != null && openIn.query.isEmpty()) {
                emit(AssistantEvent.Offered(Offer.AskWhat(openIn.app), VerifiedScene(Sahne(ASK_WHAT_TEXT))))
                return@flow
            }
            val scene = if (cmd is QuickCommand.Home) {
                Sahne(home?.apply(cmd.action, now.now().epochMillis) ?: "Ev cihazları bu cihazda yok.")
            } else {
                QuickResponder.respond(cmd, now.now())
            }
            emit(AssistantEvent.Quick(cmd, VerifiedScene(scene)))
            return@flow
        }
        // A need said in passing ("yarın 7'de kalkmam lazım"): offer to help, at once, without the model.
        NeedDetector.detect(userText)?.takeIf { canOffer(it.offer.command) }?.let { need ->
            emit(AssistantEvent.Offered(need.offer, VerifiedScene(Sahne(need.say))))
            return@flow
        }
        // Weather, rates, news, scores: no offline source knows them; offer the browser instead of guessing.
        if (LiveInfo.matches(userText)) {
            val q = userText.trim().trimEnd('?', '.', '!').take(OnlineWikipedia.MAX_QUERY)
            val google = googleSearch(q)
            if (browserOffer() && canOffer(google)) {
                emit(AssistantEvent.Offered(Offer.Action(google), VerifiedScene(Sahne(LiveInfo.say(q), Gorsel(GorselTip.BASLIK, baslik = "Anlık bilgi")))))
                return@flow
            }
        }
        val budget: Budget
        val knowledge: Boolean
        when (route) {
            is Route.Knowledge -> { budget = route.budget; knowledge = true }
            is Route.Chat -> { budget = route.budget; knowledge = false }
            is Route.Quick -> error("unreachable")
        }
        val facts = if (memory.enabled()) memory.list() else emptyList()
        val learned = learning?.invoke()
        // Without a language model, a question the user already corrected is answered from that correction.
        if (engine.id == "mock") {
            learned?.exactCorrection(userText)?.let { l ->
                val scene = VerifiedScene(Sahne("Bana öğrettiğiniz gibi: ${l.correction}", Gorsel(GorselTip.BASLIK, baslik = "Sizden öğrendim")))
                emit(AssistantEvent.SceneReady(0, scene))
                val kaynak = listOf(Kaynak("Sizin düzeltmeniz", "arac", "öğrendiklerim"))
                emit(AssistantEvent.Completed(VerifiedReply(listOf(scene), null, kaynak), SceneJson.encodeToString(Reply.serializer(), Reply(listOf(scene.sahne)))))
                return@flow
            }
        }
        val lessons = learned?.relevantLessons(userText).orEmpty()
        val notes = learned?.relevantNotes(userText).orEmpty()
        val messages = mutableListOf(ChatMessage(Role.SYSTEM, SystemPrompt.build(tools, now.now(), facts, budget, lessons, notes)))
        messages += history.filter { it.role != Role.SYSTEM }
        messages += ChatMessage(Role.USER, userText)
        // The user's own corrections and the verbatim research notes may be quoted (section 5.4).
        val evidence = (lessons.mapNotNull { it.correction } + notes.map { "${it.title} ${it.text}" }).toMutableList()
        val turn = TurnState(messages, evidence)
        val config = GenerationConfig(maxTokens = budget.maxTokens, thinking = budget.thinking)

        // Knowledge questions: search local sources first (section 5.1.3).
        if (knowledge) {
            val calls = listOf("wiki_ara", "belge_ara").mapNotNull { name -> tools[name]?.let { name to JsonObject(mapOf("sorgu" to kotlinx.serialization.json.JsonPrimitive(userText))) } }
            val results = runTools(calls, turn)
            results.forEach { emit(it) }
            // Nothing in the offline library: rather than only saying so, offer to look it up online.
            if (results.none { it is AssistantEvent.ToolCall && it.ok } && lessons.none { !it.liked } && notes.isEmpty() && webOffer()) {
                val query = io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiQuery.clean(userText).take(OnlineWikipedia.MAX_QUERY)
                val scene = VerifiedScene(Sahne(webOfferText(query), Gorsel(GorselTip.BASLIK, baslik = "İnternette bakayım mı?")))
                emit(AssistantEvent.Offered(Offer.WebLookup(query, userText), scene))
                return@flow
            }
            // No gate to read the web with: the browser can still look it up, if the user wants.
            if (results.none { it is AssistantEvent.ToolCall && it.ok } && lessons.none { !it.liked } && notes.isEmpty() && browserOffer() && canOffer(googleSearch(""))) {
                val query = io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiQuery.clean(userText).take(OnlineWikipedia.MAX_QUERY)
                val scene = VerifiedScene(Sahne(browserOfferText(query), Gorsel(GorselTip.BASLIK, baslik = "Tarayıcıda bakayım mı?")))
                emit(AssistantEvent.Offered(Offer.Action(googleSearch(query)), scene))
                return@flow
            }
        }
        generate(turn, config, knowledge)
    }

    private fun googleSearch(q: String) = QuickCommand.Device(
        io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.OpenIn(io.github.ahmetdurmus1218.jarvis.core.device.OutsideApp.GOOGLE, q),
    )

    /**
     * Answers [question] from pages read on the Wikipedia website after the user's approval. The
     * pages are untrusted data like any article; numbers and quotes must appear in them verbatim.
     */
    fun answerFromWeb(question: String, pages: List<WebPage>, history: List<ChatMessage> = emptyList()): Flow<AssistantEvent> = flow {
        val kaynaklar = pages.map { Kaynak(it.title, "web", it.url) }
        if (pages.isEmpty()) {
            val scene = VerifiedScene(Sahne("İnternette de bununla ilgili bir sayfa bulamadım.", Gorsel(GorselTip.BASLIK, baslik = "Sonuç yok")))
            emit(AssistantEvent.SceneReady(0, scene))
            emit(AssistantEvent.Completed(VerifiedReply(listOf(scene), null, emptyList()), ""))
            return@flow
        }
        val real = (engine as? io.github.ahmetdurmus1218.jarvis.core.llm.EngineSelector)?.current ?: engine
        if (real.id == "mock") {
            // No language model: read the opening of the best page, word for word, with its source.
            val top = pages.first()
            val lead = io.github.ahmetdurmus1218.jarvis.core.learning.Researcher.leadSentences(top.text, 260)
            val scene = Verifier.verifyScene(Sahne(lead, Gorsel(GorselTip.KAYNAK_KARTI, baslik = top.title, kaynak = "Wikipedia (internet)", konum = top.url)), listOf(top.text), true)
            emit(AssistantEvent.SceneReady(0, scene))
            emit(AssistantEvent.Completed(VerifiedReply(listOf(scene), top.text.takeIf { it.length > lead.length }, kaynaklar), ""))
            return@flow
        }
        val budget = io.github.ahmetdurmus1218.jarvis.core.router.ComplexityScorer.budget(question)
        val facts = if (memory.enabled()) memory.list() else emptyList()
        val messages = mutableListOf(ChatMessage(Role.SYSTEM, SystemPrompt.build(tools, now.now(), facts, budget)))
        messages += history.filter { it.role != Role.SYSTEM }
        messages += ChatMessage(Role.USER, question)
        val turn = TurnState(messages, mutableListOf())
        turn.tainted = true // web text: no side-effect tools after this
        val body = kotlinx.serialization.json.buildJsonObject {
            put("kaynak", kotlinx.serialization.json.JsonPrimitive("tr.wikipedia.org (kullanıcının onayıyla internetten)"))
            put("sonuclar", kotlinx.serialization.json.buildJsonArray {
                pages.forEach { p ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("baslik", kotlinx.serialization.json.JsonPrimitive(p.title))
                        put("adres", kotlinx.serialization.json.JsonPrimitive(p.url))
                        put("metin", kotlinx.serialization.json.JsonPrimitive(p.text))
                    })
                }
            })
        }.toString()
        messages += ChatMessage(Role.TOOL, InjectionScanner.wrap("web_wikipedia", body, untrusted = true), toolName = "web_wikipedia")
        turn.evidence += pages.map { "${it.title} ${it.text}" }
        emit(AssistantEvent.ToolCall("web_wikipedia", pages.first().title, body, true))
        generate(turn, GenerationConfig(maxTokens = budget.maxTokens, thinking = budget.thinking), knowledge = true, extraSources = kaynaklar)
    }

    /** The model's answer for [turn]: tool rounds, streaming scenes, one JSON repair (section 5.3). */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<AssistantEvent>.generate(
        turn: TurnState,
        config: GenerationConfig,
        knowledge: Boolean,
        extraSources: List<Kaynak> = emptyList(),
    ) {
        val messages = turn.messages
        val evidence = turn.evidence
        var repaired = false
        var round = 0
        while (true) {
            val pending = mutableListOf<Sahne>()
            val emittedScenes = mutableListOf<VerifiedScene>()
            val parser = SceneStreamParser { pending += it }
            val stripper = ThinkStripper()
            engine.stream(messages, config).collect { token ->
                parser.feed(stripper.feed(token))
                while (pending.isNotEmpty()) {
                    val verified = Verifier.verifyScene(pending.removeAt(0), evidence, knowledge)
                    emittedScenes += verified
                    emit(AssistantEvent.SceneReady(emittedScenes.size - 1, verified))
                }
            }
            val raw = parser.text
            val toolCalls = parseToolCalls(raw)
            if (toolCalls != null && emittedScenes.isEmpty()) {
                round++
                messages += ChatMessage(Role.ASSISTANT, raw)
                if (round > MAX_TOOL_ROUNDS) {
                    emit(finish(listOf(Sahne("Araç sınırına ulaştım, bu soruyu şimdilik cevaplayamıyorum.")), null, emptyList(), evidence, knowledge, raw))
                    return
                }
                runTools(toolCalls, turn).forEach { emit(it) }
                continue
            }
            when (val outcome = parser.finish()) {
                is SceneStreamParser.Outcome.Parsed -> {
                    val reply = outcome.reply
                    // Scenes not emitted during streaming (e.g. whole reply in one chunk) are emitted now.
                    val remaining = reply.sahneler.drop(emittedScenes.size)
                    remaining.forEach { s ->
                        val v = Verifier.verifyScene(s, evidence, knowledge)
                        emittedScenes += v
                        emit(AssistantEvent.SceneReady(emittedScenes.size - 1, v))
                    }
                    emit(AssistantEvent.Completed(VerifiedReply(emittedScenes, reply.detay, (reply.kaynaklar + extraSources).distinctBy { it.baslik }), raw))
                    return
                }
                is SceneStreamParser.Outcome.Broken -> {
                    if (emittedScenes.isNotEmpty()) {
                        emit(AssistantEvent.Completed(VerifiedReply(emittedScenes, null, emptyList()), raw))
                        return
                    }
                    if (!repaired) {
                        repaired = true
                        messages += ChatMessage(Role.ASSISTANT, raw)
                        messages += ChatMessage(Role.USER, REPAIR_PROMPT)
                        continue
                    }
                    emit(finish(listOf(SceneStreamParser.plainTextScene(raw)), null, emptyList(), evidence, knowledge, raw))
                    return
                }
            }
        }
    }

    /**
     * "Bu ne?" / "Bunu oku": a question about a camera picture or screenshot. No tools; the answer
     * is a scene reply like any other. Numbers read from a picture cannot be checked against a
     * source, so number cards are turned into titles by the verifier (section 5.4).
     */
    /** Plain text from the vision model for [prompt] about [jpeg] (shelf reading); null without a seeing model. */
    suspend fun visionText(prompt: String, jpeg: ByteArray, maxTokens: Int = 1_200): String? {
        val vision = (engine as? io.github.ahmetdurmus1218.jarvis.core.llm.EngineSelector)?.vision
            ?: (engine as? io.github.ahmetdurmus1218.jarvis.core.llm.VisionLlm)?.takeIf { it.canSee() }
            ?: return null
        val sb = StringBuilder()
        val stripper = ThinkStripper()
        vision.streamWithImage(listOf(ChatMessage(Role.USER, prompt)), jpeg, GenerationConfig(maxTokens = maxTokens, temperature = 0.1f)).collect { sb.append(stripper.feed(it)) }
        return sb.toString()
    }

    /** Plain text from the local model (book notebook); null when only the mock is installed. */
    suspend fun plainText(system: String, user: String, maxTokens: Int = 700): String? {
        val real = (engine as? io.github.ahmetdurmus1218.jarvis.core.llm.EngineSelector)?.current ?: engine
        if (real.id == "mock") return null
        val sb = StringBuilder()
        val stripper = ThinkStripper()
        real.stream(listOf(ChatMessage(Role.SYSTEM, system), ChatMessage(Role.USER, user)), GenerationConfig(maxTokens = maxTokens, temperature = 0.3f)).collect { sb.append(stripper.feed(it)) }
        return sb.toString()
    }

    fun askAboutImage(question: String, jpeg: ByteArray): Flow<AssistantEvent> = flow {
        val vision = (engine as? io.github.ahmetdurmus1218.jarvis.core.llm.EngineSelector)?.vision
            ?: (engine as? io.github.ahmetdurmus1218.jarvis.core.llm.VisionLlm)?.takeIf { it.canSee() }
        if (vision == null) {
            emit(finish(listOf(Sahne(NO_VISION_TEXT, Gorsel(GorselTip.BASLIK, baslik = "Görme kurulu değil"))), null, emptyList(), emptyList(), false, ""))
            return@flow
        }
        val messages = listOf(
            ChatMessage(Role.SYSTEM, SystemPrompt.vision(now.now())),
            ChatMessage(Role.USER, question),
        )
        val pending = mutableListOf<Sahne>()
        val emitted = mutableListOf<VerifiedScene>()
        val parser = SceneStreamParser { pending += it }
        val stripper = ThinkStripper()
        vision.streamWithImage(messages, jpeg, GenerationConfig(maxTokens = 600)).collect { token ->
            parser.feed(stripper.feed(token))
            while (pending.isNotEmpty()) {
                val v = Verifier.verifyScene(pending.removeAt(0), emptyList(), false)
                emitted += v
                emit(AssistantEvent.SceneReady(emitted.size - 1, v))
            }
        }
        when (val outcome = parser.finish()) {
            is SceneStreamParser.Outcome.Parsed -> {
                outcome.reply.sahneler.drop(emitted.size).forEach { s ->
                    val v = Verifier.verifyScene(s, emptyList(), false)
                    emitted += v
                    emit(AssistantEvent.SceneReady(emitted.size - 1, v))
                }
                emit(AssistantEvent.Completed(VerifiedReply(emitted, outcome.reply.detay, emptyList()), parser.text))
            }
            is SceneStreamParser.Outcome.Broken ->
                if (emitted.isNotEmpty()) {
                    emit(AssistantEvent.Completed(VerifiedReply(emitted, null, emptyList()), parser.text))
                } else {
                    emit(finish(listOf(SceneStreamParser.plainTextScene(parser.text)), null, emptyList(), emptyList(), false, parser.text))
                }
        }
    }

    private fun finish(
        scenes: List<Sahne>,
        detay: String?,
        kaynaklar: List<Kaynak>,
        evidence: List<String>,
        knowledge: Boolean,
        raw: String,
    ): AssistantEvent.Completed {
        val verified = scenes.map { Verifier.verifyScene(it, evidence, knowledge) }
        return AssistantEvent.Completed(VerifiedReply(verified, detay, kaynaklar), raw)
    }

    /** Per-question state shared by the tool rounds. */
    private class TurnState(val messages: MutableList<ChatMessage>, val evidence: MutableList<String>) {
        /** Set once untrusted data (Wikipedia, documents, web) is in the conversation. */
        var tainted = false
    }

    /**
     * Runs independent tool calls in parallel and appends their (wrapped) output to the conversation.
     * Tools with side effects are refused after untrusted data has been read in this question, so a
     * prompt injected into an article can never change memory or trigger device actions.
     */
    private suspend fun runTools(calls: List<Pair<String, JsonObject>>, turn: TurnState): List<AssistantEvent> = coroutineScope {
        val taintedBefore = turn.tainted
        calls.map { (name, input) ->
            async {
                val tool = tools[name]
                val out = when {
                    tool == null -> errorOutput("bilinmeyen araç: $name")
                    tool.sideEffects && taintedBefore ->
                        errorOutput("güvenlik: güvenilmeyen içerik okunduktan sonra bu araç kullanılamaz")
                    else -> try {
                        tool.run(input)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errorOutput("araç hatası: ${e.message ?: e::class.simpleName}")
                    }
                }
                Triple(name, input, out)
            }
        }.awaitAll().map { (name, input, out) ->
            val untrusted = tools[name]?.trust != Trust.COMPUTED
            if (untrusted && out.ok) turn.tainted = true
            turn.messages += ChatMessage(Role.TOOL, InjectionScanner.wrap(name, out.text, untrusted), toolName = name)
            if (out.ok) turn.evidence += out.text
            AssistantEvent.ToolCall(name, input.toString(), out.text, out.ok)
        }
    }

    private fun errorOutput(message: String) =
        ToolOutput(kotlinx.serialization.json.buildJsonObject { put("hata", kotlinx.serialization.json.JsonPrimitive(message)) }.toString(), ok = false)

    /** Returns the tool calls if [raw] is a tool-call message, otherwise null. */
    internal fun parseToolCalls(raw: String): List<Pair<String, JsonObject>>? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = try {
            SceneJson.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
        } catch (_: Exception) {
            return null
        }
        val arr = obj["arac_cagrilari"] as? JsonArray ?: return null
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["ad"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            name to ((o["girdi"] as? JsonObject) ?: JsonObject(emptyMap()))
        }.takeIf { it.isNotEmpty() }
    }
}

/** Fixed Turkish answers for quick commands. */
object QuickResponder {
    fun respond(cmd: QuickCommand, now: io.github.ahmetdurmus1218.jarvis.core.time.Now): Sahne = when (cmd) {
        QuickCommand.TimeNow -> Sahne("Saat ${now.hhmm()}.", Gorsel(GorselTip.SAYI, deger = now.hhmm(), etiket = "Saat"))
        QuickCommand.DateToday -> {
            val d = "${now.day} ${io.github.ahmetdurmus1218.jarvis.core.text.Turkish.monthName(now.month)} ${now.year}"
            val day = io.github.ahmetdurmus1218.jarvis.core.text.Turkish.dayName(now.isoDayOfWeek)
            Sahne("Bugün $d, $day.", Gorsel(GorselTip.BASLIK, baslik = "$d · $day"))
        }
        is QuickCommand.SetAlarm -> {
            val t = "${cmd.hour.toString().padStart(2, '0')}:${cmd.minute.toString().padStart(2, '0')}"
            Sahne("Alarmı $t için kuruyorum.", Gorsel(GorselTip.SAYI, deger = t, etiket = "Alarm"))
        }
        is QuickCommand.StartTimer -> {
            val text = durationText(cmd.seconds)
            Sahne("$text zamanlayıcı başlatıyorum.", Gorsel(GorselTip.SAYI, deger = text, etiket = "Zamanlayıcı"))
        }
        is QuickCommand.OpenApp -> Sahne("${cmd.appName.replaceFirstChar { it.uppercase() }} açılıyor.", Gorsel(GorselTip.BASLIK, baslik = cmd.appName))
        QuickCommand.StopSpeaking -> Sahne("", Gorsel(GorselTip.YOK))
        is QuickCommand.ShowPlanet -> Sahne("${cmd.kind.label} geliyor efendim.", Gorsel(GorselTip.BASLIK, baslik = cmd.kind.label))
        is QuickCommand.Home -> Sahne("")
        is QuickCommand.Library -> Sahne("")
        is QuickCommand.Todo -> Sahne("")
        QuickCommand.Sleep -> Sahne("Uyku moduna geçiyorum. İki alkışla uyandırabilirsiniz.")
        is QuickCommand.HandControl -> Sahne(if (cmd.on) "El kontrolü açılıyor." else "El kontrolü kapatıldı.")
        is QuickCommand.Device -> Sahne(io.github.ahmetdurmus1218.jarvis.core.device.DeviceCommands.describe(cmd.action))
        is QuickCommand.Calculate -> try {
            val r = io.github.ahmetdurmus1218.jarvis.core.tools.Calculator.format(
                io.github.ahmetdurmus1218.jarvis.core.tools.Calculator.evaluate(cmd.expression),
            )
            Sahne("Sonuç $r.", Gorsel(GorselTip.SAYI, deger = r, etiket = io.github.ahmetdurmus1218.jarvis.core.text.MathText.pretty(cmd.expression)))
        } catch (e: io.github.ahmetdurmus1218.jarvis.core.tools.Calculator.CalcException) {
            Sahne("Bunu hesaplayamadım: ${e.message}.")
        }
        is QuickCommand.ConvertUnit -> try {
            val r = io.github.ahmetdurmus1218.jarvis.core.tools.UnitConverter.convert(cmd.value, cmd.from, cmd.to)
            val f = io.github.ahmetdurmus1218.jarvis.core.tools.Calculator
            Sahne(
                "${f.format(r.value)} ${r.from.id}, ${f.format(r.converted)} ${r.to.id} eder.",
                Gorsel(GorselTip.SAYI, deger = f.format(r.converted), birim = r.to.id, etiket = "${f.format(r.value)} ${r.from.id}"),
            )
        } catch (e: io.github.ahmetdurmus1218.jarvis.core.tools.UnitConverter.ConversionException) {
            Sahne("Bu birimleri çeviremedim: ${e.message}.")
        }
    }

    fun durationText(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return listOfNotNull(
            if (h > 0) "$h saat" else null,
            if (m > 0) "$m dakika" else null,
            if (s > 0) "$s saniye" else null,
        ).joinToString(" ").ifEmpty { "0 saniye" }
    }
}

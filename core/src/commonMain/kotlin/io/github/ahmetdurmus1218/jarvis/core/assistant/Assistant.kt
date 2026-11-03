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
) {
    companion object {
        const val MAX_TOOL_ROUNDS = 5
        const val NO_SOURCE_TEXT = "Bu bilgi kütüphanemde yok."
        const val NO_VISION_TEXT = "Görmek için gerçek bir model gerekiyor. Telefonda Gemma 4'ü, bilgisayarda modelin görme eklentisini Modeller ekranından indirin."
        private const val REPAIR_PROMPT =
            "Önceki cevabın geçerli JSON değildi. Aynı cevabı yalnızca belirtilen JSON biçiminde, başka hiçbir metin olmadan yeniden yaz."
    }

    fun ask(userText: String, history: List<ChatMessage> = emptyList()): Flow<AssistantEvent> = flow {
        val route = Router.route(userText)
        if (route is Route.Quick) {
            emit(AssistantEvent.Quick(route.command, VerifiedScene(QuickResponder.respond(route.command, now.now()))))
            return@flow
        }
        val budget: Budget
        val knowledge: Boolean
        when (route) {
            is Route.Knowledge -> { budget = route.budget; knowledge = true }
            is Route.Chat -> { budget = route.budget; knowledge = false }
            is Route.Quick -> error("unreachable")
        }
        val facts = if (memory.enabled()) memory.list() else emptyList()
        val messages = mutableListOf(ChatMessage(Role.SYSTEM, SystemPrompt.build(tools, now.now(), facts, budget)))
        messages += history.filter { it.role != Role.SYSTEM }
        messages += ChatMessage(Role.USER, userText)
        val evidence = mutableListOf<String>()
        val turn = TurnState(messages, evidence)
        val config = GenerationConfig(maxTokens = budget.maxTokens, thinking = budget.thinking)

        // Knowledge questions: search local sources first (section 5.1.3).
        if (knowledge) {
            val calls = listOf("wiki_ara", "belge_ara").mapNotNull { name -> tools[name]?.let { name to JsonObject(mapOf("sorgu" to kotlinx.serialization.json.JsonPrimitive(userText))) } }
            runTools(calls, turn).forEach { emit(it) }
        }

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
                    return@flow
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
                    emit(AssistantEvent.Completed(VerifiedReply(emittedScenes, reply.detay, reply.kaynaklar), raw))
                    return@flow
                }
                is SceneStreamParser.Outcome.Broken -> {
                    if (emittedScenes.isNotEmpty()) {
                        emit(AssistantEvent.Completed(VerifiedReply(emittedScenes, null, emptyList()), raw))
                        return@flow
                    }
                    if (!repaired) {
                        repaired = true
                        messages += ChatMessage(Role.ASSISTANT, raw)
                        messages += ChatMessage(Role.USER, REPAIR_PROMPT)
                        continue
                    }
                    emit(finish(listOf(SceneStreamParser.plainTextScene(raw)), null, emptyList(), evidence, knowledge, raw))
                    return@flow
                }
            }
        }
    }

    /**
     * "Bu ne?" / "Bunu oku": a question about a camera picture or screenshot. No tools; the answer
     * is a scene reply like any other. Numbers read from a picture cannot be checked against a
     * source, so number cards are turned into titles by the verifier (section 5.4).
     */
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
        is QuickCommand.HandControl -> Sahne(if (cmd.on) "El kontrolü açılıyor." else "El kontrolü kapatıldı.")
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

package io.github.ahmetdurmus1218.jarvis.core.tools

import io.github.ahmetdurmus1218.jarvis.core.memory.MemoryStore
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Where a tool's output comes from; data from UNTRUSTED tools is scanned for prompt injection. */
enum class Trust { COMPUTED, UNTRUSTED }

interface Tool {
    val name: String
    val description: String
    /** Short parameter description for the system prompt, e.g. `{"ifade": "metin"}`. */
    val parameters: String
    val trust: Trust
    suspend fun run(input: JsonObject): ToolOutput
}

data class ToolOutput(val text: String, val ok: Boolean = true)

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }
    val all: List<Tool> = tools

    operator fun get(name: String): Tool? = byName[name]

    fun describe(): String = all.joinToString("\n") { "- ${it.name}: ${it.description} Girdi: ${it.parameters}" }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

class HesaplaTool : Tool {
    override val name = "hesapla"
    override val description = "Güvenli matematik ifadesi hesaplar (+ - * / ^ %, sqrt, sin, cos, log, ln, pi)."
    override val parameters = "{\"ifade\": \"metin\"}"
    override val trust = Trust.COMPUTED
    override suspend fun run(input: JsonObject): ToolOutput {
        val expr = input.str("ifade") ?: return ToolOutput("{\"hata\":\"ifade eksik\"}", ok = false)
        return try {
            val v = Calculator.evaluate(Calculator.fromSpeech(expr).ifEmpty { expr })
            ToolOutput(buildJsonObject { put("ifade", expr); put("sonuc", Calculator.format(v)) }.toString())
        } catch (e: Calculator.CalcException) {
            ToolOutput(buildJsonObject { put("ifade", expr); put("hata", e.message ?: "hata") }.toString(), ok = false)
        }
    }
}

class BirimCevirTool : Tool {
    override val name = "birim_cevir"
    override val description = "Birim çevirir (uzunluk, ağırlık, hacim, alan, hız, süre, veri, sıcaklık)."
    override val parameters = "{\"deger\": sayı, \"kaynak\": \"birim\", \"hedef\": \"birim\"}"
    override val trust = Trust.COMPUTED
    override suspend fun run(input: JsonObject): ToolOutput {
        val v = input["deger"]?.jsonPrimitive?.let { it.doubleOrNull ?: Calculator.parseLocalizedNumber(it.content) }
        val from = input.str("kaynak")
        val to = input.str("hedef")
        if (v == null || from == null || to == null) return ToolOutput("{\"hata\":\"deger, kaynak ve hedef gerekli\"}", ok = false)
        return try {
            val r = UnitConverter.convert(v, from, to)
            ToolOutput(
                buildJsonObject {
                    put("deger", Calculator.format(v)); put("kaynak", r.from.id)
                    put("sonuc", Calculator.format(r.converted)); put("hedef", r.to.id)
                }.toString(),
            )
        } catch (e: UnitConverter.ConversionException) {
            ToolOutput(buildJsonObject { put("hata", e.message ?: "hata") }.toString(), ok = false)
        }
    }
}

class TarihSaatTool(private val now: NowProvider) : Tool {
    override val name = "tarih_saat"
    override val description = "Cihazın şu anki tarihini, saatini ve gününü verir."
    override val parameters = "{}"
    override val trust = Trust.COMPUTED
    override suspend fun run(input: JsonObject): ToolOutput {
        val t = now.now()
        return ToolOutput(
            buildJsonObject {
                put("tarih", "${t.day} ${Turkish.monthName(t.month)} ${t.year}")
                put("gun", Turkish.dayName(t.isoDayOfWeek))
                put("saat", t.hhmm())
                put("saat_dilimi", t.zone)
            }.toString(),
        )
    }
}

class HafizaEkleTool(private val store: MemoryStore) : Tool {
    override val name = "hafiza_ekle"
    override val description = "Kullanıcı hakkında kalıcı bir bilgiyi hafızaya ekler (yalnızca kullanıcı isterse)."
    override val parameters = "{\"bilgi\": \"metin\"}"
    override val trust = Trust.COMPUTED
    override suspend fun run(input: JsonObject): ToolOutput {
        val fact = input.str("bilgi")?.trim().orEmpty()
        if (fact.isEmpty()) return ToolOutput("{\"hata\":\"bilgi eksik\"}", ok = false)
        if (!store.enabled()) return ToolOutput("{\"hata\":\"hafıza kapalı\"}", ok = false)
        val added = store.add(fact)
        return ToolOutput(buildJsonObject { put("eklendi", added); put("bilgi", fact) }.toString())
    }
}

class HafizaSilTool(private val store: MemoryStore) : Tool {
    override val name = "hafiza_sil"
    override val description = "Hafızadan, verilen metni içeren kayıtları siler."
    override val parameters = "{\"bilgi\": \"metin\"}"
    override val trust = Trust.COMPUTED
    override suspend fun run(input: JsonObject): ToolOutput {
        val q = input.str("bilgi")?.trim().orEmpty()
        val removed = if (q.isEmpty()) 0 else store.removeMatching(q)
        return ToolOutput(buildJsonObject { put("silinen", removed) }.toString())
    }
}

class HafizaListeleTool(private val store: MemoryStore) : Tool {
    override val name = "hafiza_listele"
    override val description = "Hafızadaki kayıtları listeler."
    override val parameters = "{}"
    override val trust = Trust.COMPUTED
    override suspend fun run(input: JsonObject): ToolOutput {
        val facts = store.list()
        return ToolOutput(buildJsonObject { put("kayitlar", buildJsonArray { facts.forEach { add(JsonPrimitive(it.text)) } }) }.toString())
    }
}

/** Placeholder for knowledge tools that are not installed yet (Wikipedia ZIM / document index). */
class NotInstalledTool(
    override val name: String,
    override val description: String,
    private val what: String,
) : Tool {
    override val parameters = "{\"sorgu\": \"metin\"}"
    override val trust = Trust.UNTRUSTED
    override suspend fun run(input: JsonObject): ToolOutput =
        ToolOutput(buildJsonObject { put("sonuclar", buildJsonArray { }); put("not", "$what henüz kurulu değil") }.toString(), ok = false)
}

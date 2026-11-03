package io.github.ahmetdurmus1218.jarvis.core.llm

import io.github.ahmetdurmus1218.jarvis.core.scene.Gorsel
import io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip
import io.github.ahmetdurmus1218.jarvis.core.scene.Kaynak
import io.github.ahmetdurmus1218.jarvis.core.scene.Reply
import io.github.ahmetdurmus1218.jarvis.core.scene.Sahne
import io.github.ahmetdurmus1218.jarvis.core.scene.SceneJson
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stand-in LLM for v0.1: deterministic, offline, streams scene JSON token by token so the whole
 * pipeline (tool calls, streaming parser, TTS, verification) runs end to end before real models land.
 */
class MockLlmEngine(private val tokenDelayMs: Long = 12, private val chunkSize: Int = 6) : LlmEngine {
    override val id: String = "mock"

    override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = flow {
        val out = respond(messages)
        var i = 0
        while (i < out.length) {
            val end = (i + chunkSize).coerceAtMost(out.length)
            emit(out.substring(i, end))
            i = end
            if (tokenDelayMs > 0) delay(tokenDelayMs)
        }
    }

    internal fun respond(messages: List<ChatMessage>): String {
        val last = messages.lastOrNull() ?: return reply(Sahne("Merhaba efendim."))
        if (last.role == Role.TOOL) return afterTools(messages)
        if (last.role == Role.USER && last.content.startsWith("Önceki cevabın geçerli JSON değildi")) {
            return reply(Sahne("Özür dilerim efendim, cevabı yeniden düzenledim."))
        }
        val text = last.content
        val n = Turkish.normalize(text)
        return when {
            Regex("^(merhaba|selam|gunaydin|iyi aksamlar|hey (?:rick|rik|jarvis))").containsMatchIn(n) ->
                reply(Sahne("Merhaba efendim, ben Rick. Her şey hazır.", Gorsel(GorselTip.BASLIK, baslik = "Merhaba")))
            Regex("^(nasilsin|naber)").containsMatchIn(n) ->
                reply(Sahne("İyiyim, sorduğunuz için sağ olun. Siz nasılsınız, bugün ne yapalım?"))
            Regex("\\b(tesekkur|sagol|eyvallah)").containsMatchIn(n) ->
                reply(Sahne("Ne demek, her zaman."))
            Regex("\\b(hatirla|unutma|aklinda tut)\\b").containsMatchIn(n) -> {
                val fact = text.replace(Regex("(?i)(bunu |şunu )?(hatırla|unutma|aklında tut)[:,]?"), "").trim().trimEnd('.')
                toolCall("hafiza_ekle", "{\"bilgi\":${quote(fact)}}")
            }
            Regex("\\b(ne hatirliyorsun|hafizanda ne var|hafizani goster)\\b").containsMatchIn(n) ->
                toolCall("hafiza_listele", "{}")
            Regex("\\d").containsMatchIn(n) && Regex("\\b(arti|eksi|carpi|bolu|kere|ussu|karekok)\\b|[+*/^]").containsMatchIn(n) -> {
                val expr = Regex("[\\d.,+\\-*/^()\\s]+|arti|eksi|carpi|bolu|kere|ussu|karekok").findAll(n)
                    .joinToString(" ") { it.value.trim() }.replace(Regex("\\s+"), " ").trim()
                toolCall("hesapla", "{\"ifade\":${quote(expr)}}")
            }
            messages.any { it.role == Role.TOOL && it.toolName == "wiki_ara" } || Regex("\\b(kimdir|nedir|ne zaman|nerede|nufusu|baskenti|kac yilinda)\\b").containsMatchIn(n) ->
                fromWikipedia(messages) ?: reply(
                    Sahne("Bu bilgi kütüphanemde yok.", Gorsel(GorselTip.BASLIK, baslik = "Kaynak bulunamadı")),
                    Sahne("Wikipedia paketi kurulduğunda bu soruyu kaynağıyla cevaplayabilirim."),
                )
            else -> reply(
                Sahne("Bu konuda sohbet edebilmem için dil modelimi kurmamız lazım.", Gorsel(GorselTip.BASLIK, baslik = "Dil modeli gerekli")),
                Sahne("Aşağıdaki düğmeyle tek dokunuşta kurulur; sonra internetsiz konuşuruz."),
                detay = "Dil modeli kurulmadan da çalışanlar: saat, tarih, alarm, zamanlayıcı, hesap, birim çevirme, fener, ses, müzik, " +
                    "arama ve mesaj hazırlama, ayar panelleri, yapılacaklar, kitaplık, ev, brifing ve kaynaklı Wikipedia / belge cevapları.",
            )
        }
    }

    private fun afterTools(messages: List<ChatMessage>): String {
        val toolMsgs = messages.takeLastWhile { it.role == Role.TOOL }
        val last = toolMsgs.lastOrNull() ?: return reply(Sahne("Tamam efendim."))
        val body = last.content.substringAfter('>').substringBeforeLast("</arac_ciktisi").trim()
        val json = runCatching { SceneJson.parseToJsonElement(body.substring(body.indexOf('{'))).jsonObject }.getOrNull()
        fun field(o: JsonObject?, k: String) = o?.get(k)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        return when (last.toolName) {
            "hesapla" -> {
                val r = field(json, "sonuc")
                if (r != null) {
                    reply(
                        Sahne("Sonuç $r efendim.", Gorsel(GorselTip.SAYI, deger = r, etiket = field(json, "ifade")?.let(io.github.ahmetdurmus1218.jarvis.core.text.MathText::pretty))),
                        kaynaklar = listOf(Kaynak("Hesap makinesi", "arac", "hesapla")),
                    )
                } else {
                    reply(Sahne("Bunu hesaplayamadım efendim."))
                }
            }
            "hafiza_ekle" -> reply(Sahne(if (field(json, "eklendi") == "true") "Tamam efendim, aklımda tutacağım." else "Bunu zaten biliyorum ya da hafıza kapalı."))
            "hafiza_listele" -> {
                val items = (json?.get("kayitlar") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
                if (items.isEmpty()) reply(Sahne("Hafızamda henüz bir şey yok efendim."))
                else reply(Sahne("Hafızamda ${items.size} kayıt var.", Gorsel(GorselTip.LISTE, ogeler = items.take(10))))
            }
            else -> fromWikipedia(messages) ?: reply(
                Sahne("Bu bilgi kütüphanemde yok.", Gorsel(GorselTip.BASLIK, baslik = "Kaynak bulunamadı")),
                Sahne("Wikipedia paketi kurulduğunda bu soruyu kaynağıyla cevaplayabilirim."),
            )
        }
    }

    /** Demo answer from the user's documents (belge_ara) or Wikipedia (wiki_ara), with the source. */
    private fun fromWikipedia(messages: List<ChatMessage>): String? = fromDocuments(messages) ?: fromWiki(messages)

    private fun fromDocuments(messages: List<ChatMessage>): String? {
        val msg = messages.lastOrNull { it.role == Role.TOOL && it.toolName == "belge_ara" } ?: return null
        val body = msg.content.substringAfter('>').substringBeforeLast("</arac_ciktisi").trim()
        val json = runCatching { SceneJson.parseToJsonElement(body.substring(body.indexOf('{'))).jsonObject }.getOrNull() ?: return null
        val first = (json["sonuclar"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val doc = first["belge"]?.jsonPrimitive?.contentOrNull ?: return null
        val text = first["metin"]?.jsonPrimitive?.contentOrNull ?: return null
        // The sentence of the piece that shares the most words with the question.
        val question = io.github.ahmetdurmus1218.jarvis.core.knowledge.Terms.of(messages.lastOrNull { it.role == Role.USER }?.content.orEmpty()).toSet()
        val best = text.split(Regex("(?<=[.!?])\\s+")).filter { it.length >= 10 }
            .maxByOrNull { sentence -> io.github.ahmetdurmus1218.jarvis.core.knowledge.Terms.of(sentence).count { it in question } } ?: text
        return reply(
            Sahne(best.take(220).trim(), Gorsel(GorselTip.KAYNAK_KARTI, baslik = doc, kaynak = "Belgem", konum = "parça ${first["parca"]?.jsonPrimitive?.contentOrNull ?: "1"}")),
            kaynaklar = listOf(Kaynak(doc, "belge", "parça ${first["parca"]?.jsonPrimitive?.contentOrNull ?: "1"}")),
        )
    }

    private fun fromWiki(messages: List<ChatMessage>): String? {
        val msg = messages.lastOrNull { it.role == Role.TOOL && it.toolName == "wiki_ara" } ?: return null
        val body = msg.content.substringAfter('>').substringBeforeLast("</arac_ciktisi").trim()
        val json = runCatching { SceneJson.parseToJsonElement(body.substring(body.indexOf('{'))).jsonObject }.getOrNull() ?: return null
        val article = json["ilk_makale"] as? JsonObject ?: return null
        val title = article["baslik"]?.jsonPrimitive?.contentOrNull ?: return null
        val path = article["yol"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val textBody = article["metin"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val para = textBody.lines().firstOrNull { !it.startsWith("## ") && it.trim() != title && it.length >= 40 } ?: return null
        val cut = Regex("[.!?](\\s|$)").findAll(para).map { it.range.first + 1 }.firstOrNull { it >= 30 } ?: para.length
        val sentence = para.take(minOf(cut, 220)).trim()
        return reply(
            Sahne(sentence, Gorsel(GorselTip.KAYNAK_KARTI, baslik = title, kaynak = "Wikipedia", konum = path)),
            kaynaklar = listOf(Kaynak(title, "wikipedia", path)),
        )
    }

    private fun reply(vararg scenes: Sahne, detay: String? = null, kaynaklar: List<Kaynak> = emptyList()): String =
        SceneJson.encodeToString(Reply.serializer(), Reply(scenes.toList(), detay, kaynaklar))

    private fun toolCall(name: String, inputJson: String) = "{\"arac_cagrilari\":[{\"ad\":\"$name\",\"girdi\":$inputJson}]}"

    private fun quote(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()
}

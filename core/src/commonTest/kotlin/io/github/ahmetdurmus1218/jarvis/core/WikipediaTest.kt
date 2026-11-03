package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiAraTool
import io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiHit
import io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiOkuTool
import io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiQuery
import io.github.ahmetdurmus1218.jarvis.core.knowledge.Wikipedia
import io.github.ahmetdurmus1218.jarvis.core.knowledge.ZimBackend
import io.github.ahmetdurmus1218.jarvis.core.text.HtmlText
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikipediaTest {
    private val ankara = """
        <!DOCTYPE html><html><head><title>Ankara</title><script>var x = "<p>gizli</p>";</script>
        <style>p { color: red }</style></head><body>
        <h1>Ankara</h1>
        <table class="infobox"><tr><th>Nüfus</th><td>5.910.320</td></tr><tr><th>Rakım</th><td>938&nbsp;m</td></tr></table>
        <p><b>Ankara</b>, <a href="T%C3%BCrkiye">Türkiye</a>'nin başkenti ve en kalabalık ikinci şehridir.<sup class="reference"><a href="#cite_note-1">[1]</a></sup>
        Nüfusu 5.910.320'dir.[2]</p>
        <!-- yorum -->
        <h2>Tarih</h2><ul><li>Ankara Savaşı &ndash; 1402</li><li>Başkent oluşu &#8211; 1923</li></ul>
        <p>Şehir &quot;Angora&quot; olarak da bilinir &amp; tiftik keçisiyle ünlüdür. &#x1F410;</p>
        <h2>Kaynakça</h2><ol><li>TÜİK 2023</li></ol><p>Bu satır okunmamalı.</p>
        </body></html>
    """.trimIndent()

    @Test
    fun articleHtmlBecomesReadableText() {
        assertEquals("Ankara", HtmlText.title(ankara))
        val t = HtmlText.toText(ankara)
        assertFalse("gizli" in t || "color" in t || "yorum" in t, t)
        assertTrue("Nüfus | 5.910.320" in t, t)
        assertTrue("938 m" in t, t)
        assertTrue("Ankara, Türkiye'nin başkenti ve en kalabalık ikinci şehridir." in t, t)
        assertTrue("Nüfusu 5.910.320'dir." in t, t)
        assertFalse("[1]" in t || "[2]" in t, "citation markers removed: $t")
        assertTrue("## Tarih" in t, t)
        assertTrue("• Ankara Savaşı – 1402" in t, t)
        assertTrue("• Başkent oluşu – 1923" in t, t)
        assertTrue("\"Angora\" olarak da bilinir & tiftik" in t, t)
        assertTrue("🐐" in t, "astral code point decoded")
        assertFalse("Kaynakça" in t || "okunmamalı" in t || "TÜİK" in t, "stops at the references: $t")
    }

    @Test
    fun questionsBecomeQueries() {
        assertEquals("Atatürk", WikiQuery.clean("Atatürk kimdir?"))
        assertEquals("Türkiye başkenti", WikiQuery.clean("Türkiye'nin başkenti neresidir?"))
        assertEquals("Everest yüksekliği", WikiQuery.clean("Rick, Everest'in yüksekliği nedir lütfen"))
        assertEquals("zaman yolculuğu", WikiQuery.clean("zaman yolculuğu hakkında bilgi ver"))
        assertEquals("kim", WikiQuery.clean("kim"), "never empty")
    }

    /** In-memory ZIM: title index on titles, "full text" on bodies. */
    private class FakeZim(val ready: Boolean = true) : ZimBackend {
        val pages = mapOf(
            "Mustafa_Kemal_Atatürk" to ("Mustafa Kemal Atatürk" to "<p>Mustafa Kemal Atatürk, Türkiye Cumhuriyeti'nin kurucusudur. 1881'de doğdu.</p>"),
            "Atatürk_Orman_Çiftliği" to ("Atatürk Orman Çiftliği" to "<p>Ankara'da bir çiftlik.</p>"),
            "Anıtkabir" to ("Anıtkabir" to "<p>Anıtkabir, Atatürk'ün mozolesidir.</p>"),
            "Uzun" to ("Uzun" to "<p>" + "a".repeat(13_000) + "</p>"),
        )
        override fun isReady() = ready
        override suspend fun titleSearch(query: String, limit: Int) =
            pages.filter { Turkish.normalize(it.value.first).contains(Turkish.normalize(query)) }
                .map { WikiHit(it.value.first, it.key) }.take(limit)
        override suspend fun fullTextSearch(query: String, limit: Int) =
            pages.filter { Turkish.normalize(it.value.second).contains(Turkish.normalize(query)) }
                .map { WikiHit(it.value.first, it.key, "…${query}…") }.take(limit)
        override suspend fun article(path: String) = pages[path]?.let { "<html><body>${it.second}</body></html>" to it.first }
    }

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun input(vararg kv: Pair<String, Any>) = JsonObject(kv.associate { (k, v) -> k to (if (v is Int) JsonPrimitive(v) else JsonPrimitive(v.toString())) })

    @Test
    fun searchPutsTitlesFirstAndReadsTheBestArticle() = runTest {
        val out = WikiAraTool(Wikipedia(FakeZim())).run(input("sorgu" to "Atatürk kimdir?"))
        assertTrue(out.ok)
        val o = json(out.text)
        val titles = o["sonuclar"]!!.jsonArray.map { it.jsonObject["baslik"]!!.jsonPrimitive.content }
        assertEquals(listOf("Mustafa Kemal Atatürk", "Atatürk Orman Çiftliği", "Anıtkabir"), titles, "title hits first, then full text, no duplicates")
        val first = o["ilk_makale"]!!.jsonObject
        assertEquals("Mustafa Kemal Atatürk", first["baslik"]!!.jsonPrimitive.content)
        assertTrue("1881'de doğdu" in first["metin"]!!.jsonPrimitive.content)
    }

    @Test
    fun exactTitleWins() = runTest {
        val hits = Wikipedia(FakeZim()).search("Anıtkabir nedir?")
        assertEquals("Anıtkabir", hits.first().title)
    }

    @Test
    fun longArticlesAreReadInParts() = runTest {
        val tool = WikiOkuTool(Wikipedia(FakeZim()))
        val p1 = json(tool.run(input("yol" to "Uzun")).text)
        assertEquals(Wikipedia.PART_CHARS, p1["metin"]!!.jsonPrimitive.content.length)
        assertTrue(p1["devami_var"]!!.jsonPrimitive.boolean)
        val p3 = json(tool.run(input("yol" to "Uzun", "parca" to 3)).text)
        assertEquals(13_000 - 2 * Wikipedia.PART_CHARS, p3["metin"]!!.jsonPrimitive.content.length)
        assertFalse(p3["devami_var"]!!.jsonPrimitive.boolean)
        assertFalse(tool.run(input("yol" to "Yok")).ok)
    }

    @Test
    fun notInstalledIsSaidPlainly() = runTest {
        val out = WikiAraTool(Wikipedia(FakeZim(ready = false))).run(input("sorgu" to "Ankara"))
        assertFalse(out.ok)
        assertTrue("kurulu değil" in out.text)
        assertFalse(WikiAraTool(null).run(input("sorgu" to "Ankara")).ok)
        assertNull(json(WikiAraTool(null).run(input("sorgu" to "x")).text)["ilk_makale"])
    }

    @Test
    fun knowledgeQuestionIsAnsweredFromWikipediaWithItsSource() = runTest {
        val memory = io.github.ahmetdurmus1218.jarvis.core.memory.InMemoryMemoryStore()
        val tools = io.github.ahmetdurmus1218.jarvis.core.assistant.DefaultTools.create(memory, wiki = Wikipedia(FakeZim()))
        val assistant = io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant(
            io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine(tokenDelayMs = 0), tools, memory,
        )
        val events = mutableListOf<io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent>()
        assistant.ask("Atatürk kimdir?").collect { events += it }
        val done = events.filterIsInstance<io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent.Completed>().single()
        val scene = done.reply.scenes.first()
        assertEquals("Mustafa Kemal Atatürk, Türkiye Cumhuriyeti'nin kurucusudur.", scene.sahne.ses)
        assertFalse(scene.kaynakYok, "the sentence is in the article, so it is backed by a source")
        assertEquals("Mustafa Kemal Atatürk", done.reply.kaynaklar.single().baslik)
        assertTrue(events.any { it is io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent.ToolCall && it.name == "wiki_ara" && it.ok })
    }
}

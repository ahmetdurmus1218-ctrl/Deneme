package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.knowledge.Wikipedia
import io.github.ahmetdurmus1218.jarvis.desktop.knowledge.KiwixServe
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KiwixServeTest {
    @Test
    fun zimNamesFollowKiwixServeRules() {
        assertEquals("wikipedia_tr_all_nopic_2026-09", KiwixServe.zimNameOf("wikipedia_tr_all_nopic_2026-09.zim"))
        // lowercase, diacritics removed, spaces → "_", "+" → "plus"
        assertEquals("turkce_vikipedi_cplusplus", KiwixServe.zimNameOf("Türkçe Vikipedi C++.zim"))
    }

    @Test
    fun linksBecomeZimPaths() {
        val n = "wikipedia_tr_all"
        assertEquals("Ankara", KiwixServe.pathFromLink("/content/$n/Ankara", n))
        assertEquals("A/Greenhouse_gas", KiwixServe.pathFromLink("http://127.0.0.1:8080/content/$n/A/Greenhouse_gas", n))
        assertEquals("Mustafa_Kemal_Atatürk", KiwixServe.pathFromLink("/content/$n/Mustafa_Kemal_Atat%C3%BCrk", n))
        assertEquals("C++", KiwixServe.pathFromLink("/content/$n/C++", n))
        assertEquals("Ankara", KiwixServe.pathFromLink("/raw/$n/content/Ankara?x=1#tarih", n))
        assertEquals("A&B", KiwixServe.pathFromLink("/content/$n/A&amp;B", n))
    }

    @Test
    fun searchFeedAndSuggestionsAreParsed() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel><title>Search: ankara</title>
            <item><title>Ankara</title><link>/content/wiki/Ankara</link>
              <description>&lt;b&gt;Ankara&lt;/b&gt;, Türkiye&amp;apos;nin başkenti…</description><wordCount>3000</wordCount></item>
            <item><title>Ankara Savaşı</title><link>/content/wiki/Ankara_Sava%C5%9F%C4%B1</link><description>1402</description></item>
            </channel></rss>"""
        val hits = KiwixServe.parseSearchXml(xml, "wiki")
        assertEquals(listOf("Ankara", "Ankara Savaşı"), hits.map { it.title })
        assertEquals(listOf("Ankara", "Ankara_Savaşı"), hits.map { it.path })
        assertEquals("Ankara, Türkiye'nin başkenti…", hits[0].snippet)

        val json = """[{"value":"Ankara","label":"<b>Ankara</b>","kind":"path","path":"/content/wiki/Ankara"},
            {"value":"Ankara Savaşı","label":"Ankara Savaşı","kind":"path","path":"Ankara_Sava%C5%9F%C4%B1"},
            {"value":"ankara ","label":"containing 'ankara'...","kind":"pattern"}]"""
        val sug = KiwixServe.parseSuggest(json, "wiki")
        assertEquals(listOf("Ankara" to "Ankara", "Ankara Savaşı" to "Ankara_Savaşı"), sug.map { it.title to it.path })
        assertEquals(emptyList(), KiwixServe.parseSuggest("not json", "wiki"))
    }

    /**
     * The real kiwix-serve (the hash-verified kiwix-tools zip from models.lock.json) with a real
     * Wikipedia ZIM. CI runs it on Windows; skipped when JARVIS_KIWIX_ZIP / JARVIS_TEST_ZIM are unset.
     */
    @Test
    fun realKiwixServeSearchesAndReads() = runBlocking {
        val zip = System.getenv("JARVIS_KIWIX_ZIP")?.let(::File)
        val zim = System.getenv("JARVIS_TEST_ZIM")?.let(::File)
        assumeTrue("JARVIS_KIWIX_ZIP / JARVIS_TEST_ZIM not set", zip?.isFile == true && zim?.isFile == true)
        val bin = Files.createTempDirectory("kiwix").toFile()
        val server = KiwixServe({ zim!!.absolutePath }, { zip }, bin)
        try {
            val wiki = Wikipedia(server)
            assertTrue(wiki.isReady())
            val hits = wiki.search("Greenhouse gas emissions nedir?")
            println("kiwix-serve hits: ${hits.map { "${it.title} [${it.path}]" }}")
            assertTrue(hits.any { it.title == "Greenhouse gas emissions" }, hits.toString())
            val titleHit = wiki.search("global warming").first()
            println("kiwix-serve title hit: ${titleHit.title} [${titleHit.path}]")
            assertTrue(titleHit.title.contains("global warming", ignoreCase = true))
            val article = wiki.read(hits.first { it.title == "Greenhouse gas emissions" }.path)!!
            println("kiwix-serve article: ${article.title}: ${article.text.take(160)}")
            assertEquals("Greenhouse gas emissions", article.title)
            assertTrue("carbon dioxide" in article.text.lowercase())
            assertTrue("<" !in article.text.take(500), "plain text, no HTML")
        } finally {
            server.close()
            bin.deleteRecursively()
        }
    }
}

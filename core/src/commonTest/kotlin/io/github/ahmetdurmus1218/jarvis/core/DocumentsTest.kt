package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.knowledge.BelgeAraTool
import io.github.ahmetdurmus1218.jarvis.core.knowledge.Chunker
import io.github.ahmetdurmus1218.jarvis.core.knowledge.DocText
import io.github.ahmetdurmus1218.jarvis.core.knowledge.DocumentIndex
import io.github.ahmetdurmus1218.jarvis.core.knowledge.Terms
import io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentsTest {
    private class MemStore : TextStore {
        val files = mutableMapOf<String, String>()
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) { files[name] = text }
    }

    private val kira = """
        KİRA SÖZLEŞMESİ

        Kiracı Ahmet Durmuş, Ankara'nın Çankaya ilçesindeki daireyi 1 Ocak 2026 tarihinden itibaren kiralamıştır.

        Aylık kira bedeli 18.500 TL'dir ve her ayın 5'ine kadar ev sahibinin hesabına yatırılır. Depozito iki aylık kira tutarındadır.

        Sözleşme süresi bir yıldır. Taraflardan biri ayrılmak isterse en az 30 gün önceden yazılı bildirim yapmalıdır.
    """.trimIndent()

    private val tarif = """
        # Mercimek çorbası

        - 1 su bardağı kırmızı mercimek
        - 1 adet soğan ve 1 havuç
        - 6 su bardağı sıcak su

        Mercimekleri yıkayın, soğan ve havuçla birlikte **20 dakika** haşlayın, sonra blenderdan geçirin.
    """.trimIndent()

    @Test
    fun turkishSuffixesDoNotHideTheMatch() = runTest {
        val idx = DocumentIndex(MemStore()) { 0L }
        idx.add("Kira sözleşmesi", kira)
        idx.add("Tarif", DocText.markdown(tarif))
        val hits = idx.search("kiranın bedeli ne kadar")
        assertEquals("Kira sözleşmesi", hits.first().doc.title)
        assertTrue("18.500 TL" in hits.first().chunk.text)
        assertEquals("Tarif", idx.search("mercimekler kaç dakika haşlanır").first().doc.title)
        assertTrue(idx.search("uzay mekiği").isEmpty())
    }

    @Test
    fun termsAreStemmedAndNormalized() {
        assertEquals(listOf("ankar", "canka", "ilces"), Terms.of("Ankara'nın Çankaya ilçesindeki"))
        assertEquals(listOf("2026", "kira"), Terms.of("2026 ve kira"))
    }

    @Test
    fun longTextIsCutIntoReadablePieces() {
        val sentence = "Bu cümle belgenin uzun bir bölümünden alınmıştır ve tekrar eder. "
        val text = (1..60).joinToString("") { sentence } + "\n\n" + "Kısa son paragraf."
        val pieces = Chunker.split(text)
        assertTrue(pieces.size >= 5, "${pieces.size}")
        assertTrue(pieces.all { it.length <= 700 }, pieces.map { it.length }.toString())
        assertTrue(pieces.dropLast(1).all { it.endsWith(".") }, "cut at sentence ends")
        assertEquals(text.replace(Regex("\\s+"), " ").trim(), pieces.joinToString(" "), "nothing lost or repeated")
        assertTrue(Chunker.split("x".repeat(2000)).all { it.length <= 700 }, "even without spaces")
    }

    @Test
    fun indexSurvivesARestartAndDeletesCleanly() = runTest {
        val store = MemStore()
        val a = DocumentIndex(store) { 42L }
        val info = a.add("Kira sözleşmesi", kira)
        assertEquals(42L, info.addedAtMillis)
        val b = DocumentIndex(store) { 0L } // a fresh start reads the saved index
        assertEquals(listOf("Kira sözleşmesi"), b.list().map { it.title })
        assertTrue(b.search("depozito").isNotEmpty())
        assertTrue(b.remove(info.id))
        assertTrue(b.search("depozito").isEmpty())
        assertTrue(DocumentIndex(store) { 0L }.isEmpty())
        assertFailsWith<IllegalArgumentException> { b.add("Boş", "   ") }
    }

    @Test
    fun toolReturnsTheMatchingPieces() = runTest {
        val idx = DocumentIndex(MemStore()) { 0L }
        val tool = BelgeAraTool(idx)
        val none = tool.run(JsonObject(mapOf("sorgu" to JsonPrimitive("kira"))))
        assertFalse(none.ok)
        assertTrue("Henüz belge eklenmedi" in none.text)
        idx.add("Kira sözleşmesi", kira)
        val out = tool.run(JsonObject(mapOf("sorgu" to JsonPrimitive("Kira bedeli nedir?"))))
        assertTrue(out.ok)
        val first = Json.parseToJsonElement(out.text).jsonObject["sonuclar"]!!.jsonArray.first().jsonObject
        assertEquals("Kira sözleşmesi", first["belge"]!!.jsonPrimitive.content)
        assertTrue("18.500 TL" in first["metin"]!!.jsonPrimitive.content)
    }

    @Test
    fun filesAreDecodedLikeTheyWereWritten() {
        assertEquals("Şişli’de ılık", DocText.decode("﻿Şişli’de ılık".encodeToByteArray()))
        // "Iğdır'da şöyle" in Windows-1254
        val cp1254 = byteArrayOf(0x49, 0xF0.toByte(), 0x64, 0xFD.toByte(), 0x72, 0x27, 0x64, 0x61, 0x20, 0xFE.toByte(), 0xF6.toByte(), 0x79, 0x6C, 0x65)
        assertEquals("Iğdır'da şöyle", DocText.decode(cp1254))
        val md = DocText.forFile("tarif.md", tarif.encodeToByteArray())
        assertTrue("Mercimek çorbası" in md && "#" !in md && "**" !in md, md)
        assertTrue("• 1 su bardağı kırmızı mercimek" in md, md)
        assertEquals("[Bağlantı](x)", DocText.forFile("a.txt", "[Bağlantı](x)".encodeToByteArray()), "TXT stays as is")
        assertEquals("Bağlantı ve resim", DocText.markdown("[Bağlantı](http://x) ve ![resim](a.png)"))
    }

    @Test
    fun questionAboutAnImportedDocumentIsAnsweredWithItsSource() = runTest {
        val memory = io.github.ahmetdurmus1218.jarvis.core.memory.InMemoryMemoryStore()
        val docs = DocumentIndex(MemStore()) { 0L }
        docs.add("Kira sözleşmesi", kira)
        val tools = io.github.ahmetdurmus1218.jarvis.core.assistant.DefaultTools.create(memory, documents = docs)
        val assistant = io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant(
            io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine(tokenDelayMs = 0), tools, memory,
        )
        val events = mutableListOf<io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent>()
        assistant.ask("Kira bedeli nedir?").collect { events += it }
        val done = events.filterIsInstance<io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent.Completed>().single()
        assertTrue("18.500 TL" in done.reply.scenes.first().sahne.ses, done.reply.scenes.first().sahne.ses)
        assertEquals("Kira sözleşmesi", done.reply.kaynaklar.single().baslik)
        assertFalse(done.reply.scenes.first().kaynakYok)
    }
}

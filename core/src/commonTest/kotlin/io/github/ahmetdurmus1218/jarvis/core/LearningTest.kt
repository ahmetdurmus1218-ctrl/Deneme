package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant
import io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent
import io.github.ahmetdurmus1218.jarvis.core.assistant.DefaultTools
import io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore
import io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiHit
import io.github.ahmetdurmus1218.jarvis.core.knowledge.Wikipedia
import io.github.ahmetdurmus1218.jarvis.core.knowledge.ZimBackend
import io.github.ahmetdurmus1218.jarvis.core.learning.CorrectionDetector
import io.github.ahmetdurmus1218.jarvis.core.learning.Learning
import io.github.ahmetdurmus1218.jarvis.core.learning.Researcher
import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.GenerationConfig
import io.github.ahmetdurmus1218.jarvis.core.llm.LlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.memory.InMemoryMemoryStore
import io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningTest {
    private class MemStore : TextStore {
        val m = mutableMapOf<String, String>()
        override fun read(name: String) = m[name]
        override fun write(name: String, text: String) { m[name] = text }
    }

    @Test
    fun correctionsAreRecognized() {
        assertEquals("Ankara", CorrectionDetector.parse("Hayır, doğrusu Ankara."))
        assertEquals("Başkent Ankara", CorrectionDetector.parse("yanlış, başkent Ankara"))
        assertEquals("1923'te kuruldu", CorrectionDetector.parse("Öyle değil, 1923'te kuruldu"))
        assertEquals("Mars", CorrectionDetector.parse("Doğrusu: Mars"))
        assertNull(CorrectionDetector.parse("Ankara'nın nüfusu kaç?"))
        assertNull(CorrectionDetector.parse("hayır"))
    }

    @Test
    fun lessonsAreKeptMatchedAndReplaced() {
        val store = MemStore()
        val l = Learning(store) { 42 }
        l.correct("Türkiye'nin başkenti neresi?", "İstanbul.", "Ankara")
        l.like("Bana kısa bir fıkra anlat", "Temel ile Dursun…")
        // Survives a restart (sealed file on the device).
        val again = Learning(store)
        assertEquals(2, again.lessons().size)
        assertEquals("Ankara", again.relevantLessons("türkiyenin başkenti hangi şehir")[0].correction)
        assertTrue(again.relevantLessons("hava nasıl").isEmpty(), "unrelated question gets nothing")
        // A newer correction of the same question replaces the old one.
        again.correct("Türkiye'nin başkenti neresi?", "Ankara.", "Ankara, 1923'ten beri")
        assertEquals(2, again.lessons().size)
        assertEquals("Ankara, 1923'ten beri", again.exactCorrection("Türkiyenin başkenti neresi")?.correction)
        again.removeLesson(again.lessons().first().id)
        assertEquals(1, again.lessons().size)
    }

    private object Zim : ZimBackend {
        val pages = mapOf(
            "Manisa" to ("Manisa" to "<p>Manisa, Türkiye'nin Ege Bölgesi'nde yer alan bir şehirdir. Nüfusu 1.475.716'dır. Spil Dağı şehrin güneyindedir.</p>"),
            "Davranış_(kitap)" to ("Davranış (kitap)" to "<p>Davranış, Robert Sapolsky'nin 2017'de yayımlanan kitabıdır. İnsan davranışının biyolojisini anlatır.</p>"),
        )
        override fun isReady() = true
        override suspend fun titleSearch(query: String, limit: Int) =
            pages.filter { (_, v) -> v.first.startsWith(query, ignoreCase = true) }.map { WikiHit(it.value.first, it.key) }
        override suspend fun fullTextSearch(query: String, limit: Int) = emptyList<WikiHit>()
        override suspend fun article(path: String) = pages[path]?.let { it.second to it.first }
    }

    @Test
    fun idleResearchReadsWhatTheUserCaresAboutVerbatim() = runTest {
        val l = Learning(MemStore()) { 1000 }
        val r = Researcher(Wikipedia(Zim), l)
        val topics = r.topics(facts = listOf("Manisa'da yaşıyorum"), books = listOf("Davranış"), recentQuestions = emptyList())
        assertEquals(listOf("Davranış", "Manisa"), topics)
        val note = assertNotNull(r.research("Manisa"))
        assertEquals("Manisa", note.title)
        assertTrue(note.text.startsWith("Manisa, Türkiye'nin Ege Bölgesi'nde"), note.text)
        assertTrue(l.researched("Manisa"))
        assertFalse("Manisa" in r.topics(listOf("Manisa'da yaşıyorum"), emptyList(), emptyList()), "done topics are not read twice")
        assertNull(r.research("Manisa"), "the same article is not noted twice")
        assertEquals(listOf("Manisa"), l.notesSince(0).map { it.title })
        assertEquals("Manisa", l.relevantNotes("Manisa'nın nüfusu kaç")[0].title)
    }

    @Test
    fun properNounsComeOnlyFromNames() {
        assertEquals(listOf("Manisa"), Researcher.properNouns("Ben Manisa'da yaşıyorum"))
        assertEquals(listOf("Ayşe", "Bursa"), Researcher.properNouns("Kızımın adı Ayşe, Bursa'da okuyor"))
    }

    /** The prompt carries the user's correction, and a number from it passes verification. */
    @Test
    fun aCorrectionReachesThePromptAndCountsAsEvidence() = runTest {
        val l = Learning(MemStore())
        l.correct("Manisa'nın nüfusu kaç?", "1 milyon.", "1.475.716")
        var prompt = ""
        val engine = object : LlmEngine {
            override val id = "real"
            override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> {
                prompt = messages.first { it.role == Role.SYSTEM }.content
                return flowOf("""{"sahneler":[{"ses":"Manisa'nın nüfusu 1.475.716.","gorsel":{"tip":"sayi","deger":"1.475.716","etiket":"Nüfus"}}]}""")
            }
        }
        val memory = InMemoryMemoryStore()
        val events = Assistant(engine, DefaultTools.create(memory), memory, learning = { l }).ask("Manisa'nın nüfusu kaç?").toList()
        assertTrue("Kullanıcının düzeltmesi: 1.475.716" in prompt, prompt)
        val done = events.filterIsInstance<AssistantEvent.Completed>().single()
        val scene = done.reply.scenes.single()
        assertEquals(GorselTip.SAYI, scene.sahne.gorsel?.tip, "the number is backed by the user's correction")
        assertFalse(scene.downgraded)
    }

    @Test
    fun withoutAModelACorrectedQuestionIsAnsweredFromTheCorrection() = runTest {
        val l = Learning(MemStore())
        l.correct("En sevdiğim renk ne?", "Bilmiyorum.", "Lacivert")
        val memory = InMemoryMemoryStore()
        val events = Assistant(MockLlmEngine(tokenDelayMs = 0), DefaultTools.create(memory), memory, learning = { l }).ask("en sevdiğim renk ne").toList()
        val done = events.filterIsInstance<AssistantEvent.Completed>().single()
        assertEquals("Bana öğrettiğiniz gibi: Lacivert", done.reply.scenes.single().sahne.ses)
    }
}

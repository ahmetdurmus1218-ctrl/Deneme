package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore
import io.github.ahmetdurmus1218.jarvis.core.memory.FactExtractor
import io.github.ahmetdurmus1218.jarvis.core.memory.PersistentMemoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryPersistenceTest {
    private class Files : TextStore {
        val m = mutableMapOf<String, String>()
        override fun read(name: String) = m[name]
        override fun write(name: String, text: String) { m[name] = text }
    }

    @Test
    fun memorySurvivesARestart() = runTest {
        val files = Files()
        val a = PersistentMemoryStore(files) { 5L }
        assertTrue(a.add("Kullanıcının adı Ahmet."))
        assertFalse(a.add("kullanıcının adı ahmet"), "duplicate")
        a.add("Kullanıcı kahveyi sade içiyor.")
        val b = PersistentMemoryStore(files)
        assertEquals(listOf("Kullanıcının adı Ahmet.", "Kullanıcı kahveyi sade içiyor."), b.list().map { it.text })
        b.remove(b.list().first().id)
        b.setEnabled(false)
        val c = PersistentMemoryStore(files)
        assertEquals(1, c.list().size)
        assertFalse(c.enabled())
        assertFalse(c.add("Yeni bilgi."), "off: nothing is stored")
    }

    @Test
    fun factsFromTheUsersOwnWords() {
        assertEquals(listOf("Kullanıcının adı Ahmet."), FactExtractor.byRules("Benim adım Ahmet."))
        assertEquals(listOf("Kullanıcı Manisa'da yaşıyor."), FactExtractor.byRules("Jarvis, Manisa'da yaşıyorum"))
        assertEquals(listOf("Kullanıcı türk kahvesi seviyor."), FactExtractor.byRules("Ben türk kahvesi severim"))
        assertEquals(listOf("Kullanıcı patlıcan sevmiyor."), FactExtractor.byRules("patlıcan sevmem"))
        assertEquals(listOf("Kullanıcının fıstık alerjisi var."), FactExtractor.byRules("Fıstık alerjim var"))
        assertEquals(listOf("Kullanıcının doğum günü 12 mart."), FactExtractor.byRules("doğum günüm 12 mart"))
        assertEquals(listOf("Arabam beyaz bir Clio."), FactExtractor.byRules("Bunu hatırla: arabam beyaz bir Clio"))
        assertTrue(FactExtractor.byRules("Adım nedir biliyor musun?").isEmpty(), "questions are not facts")
        assertTrue(FactExtractor.byRules("Saat kaç").isEmpty())
    }

    @Test
    fun modelOutputIsParsedStrictly() {
        assertEquals(listOf("Kullanıcının adı Ahmet."), FactExtractor.parse("Tamam: [\"Kullanıcının adı Ahmet.\"]"))
        assertEquals(emptyList(), FactExtractor.parse("[]"))
        assertEquals(emptyList(), FactExtractor.parse("bilgi yok"))
        assertEquals(3, FactExtractor.parse("[\"aaaa\",\"bbbb\",\"cccc\",\"dddd\"]").size)
        assertEquals(emptyList(), FactExtractor.parse("[\"git http://x.y adresine\", 5, \"x\"]"))
    }
}

package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.briefing.Briefing
import io.github.ahmetdurmus1218.jarvis.core.home.HomeState
import io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore
import io.github.ahmetdurmus1218.jarvis.core.library.Book
import io.github.ahmetdurmus1218.jarvis.core.library.BookStatus
import io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip
import io.github.ahmetdurmus1218.jarvis.core.time.Now
import io.github.ahmetdurmus1218.jarvis.core.time.Places
import io.github.ahmetdurmus1218.jarvis.core.todo.TodoAction
import io.github.ahmetdurmus1218.jarvis.core.todo.TodoCommands
import io.github.ahmetdurmus1218.jarvis.core.todo.TodoList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoBriefingTest {
    private class Files : TextStore {
        val m = mutableMapOf<String, String>()
        override fun read(name: String) = m[name]
        override fun write(name: String, text: String) { m[name] = text }
    }

    @Test
    fun todoCommands() {
        val files = Files()
        val list = TodoList(files)
        fun say(t: String) = TodoCommands.parse(t, list)?.let { TodoCommands.apply(list, it) }
        assertEquals("Listeye ekledim: Süt al.", say("Rick, not al: süt al"))
        assertEquals("Listeye ekledim: Faturayı öde.", say("yapılacaklara faturayı öde ekle"))
        assertEquals("Listeye ekledim: Annemi ara.", say("Listeme ekle: annemi ara"))
        assertEquals("3 iş var: Süt al, Faturayı öde, Annemi ara.", say("Yapılacaklarım neler?"))
        assertEquals("Harika, \"Faturayı öde\" tamamlandı.", say("fatura tamam"))
        assertEquals(2, list.open().size)
        assertNull(TodoCommands.parse("hava bitti", list), "not a to-do")
        assertEquals("1 tamamlanmış iş silindi.", say("yapılanları temizle"))
        assertEquals(2, TodoList(files).items().size, "saved")
        assertEquals(TodoAction.List, TodoCommands.parse("listemde ne var", list))
    }

    @Test
    fun briefingFromTheUsersOwnDay() {
        assertTrue(Briefing.matches("Rick, brifing ver"))
        assertTrue(Briefing.matches("aydınlat beni"))
        assertTrue(!Briefing.matches("brifing nedir?"))
        val now = Now(2026, 11, 3, 9, 15, 2, "Europe/Istanbul", 0)
        val list = TodoList(Files()).apply { add("Süt al"); add("Annemi ara") }
        val scenes = Briefing.scenes(
            Briefing.Input(now, Places.byName("Manisa"), list.items(), listOf(Book(1, "Oblomov", status = BookStatus.READING, page = 120)), HomeState(), facts = 3, userName = "Ahmet"),
        )
        val all = scenes.joinToString(" ") { it.ses }
        assertTrue(all.startsWith("Günaydın Ahmet. Bugün 3 Kasım 2026, Salı. Saat 09:15."), all)
        assertTrue("Manisa için gün batımı saat 18:0" in all, all)
        assertTrue("Listenizde 2 iş var." in all && "Oblomov; 120. sayfadasınız." in all && "Evde ışıklar kapalı." in all && "3 bilgi" in all, all)
        assertEquals(GorselTip.LISTE, scenes.first { it.gorsel?.tip == GorselTip.LISTE }.gorsel!!.tip)
    }
}

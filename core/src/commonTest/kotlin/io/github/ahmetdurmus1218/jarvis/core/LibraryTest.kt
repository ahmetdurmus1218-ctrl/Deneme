package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore
import io.github.ahmetdurmus1218.jarvis.core.library.BookStatus
import io.github.ahmetdurmus1218.jarvis.core.library.Library
import io.github.ahmetdurmus1218.jarvis.core.library.LibraryAction
import io.github.ahmetdurmus1218.jarvis.core.library.LibraryCommands
import io.github.ahmetdurmus1218.jarvis.core.library.ShelfReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    private class Files : TextStore {
        val m = mutableMapOf<String, String>()
        override fun read(name: String) = m[name]
        override fun write(name: String, text: String) { m[name] = text }
    }

    @Test
    fun commandsFromSpeech() {
        val files = Files()
        val lib = Library(files)
        fun say(t: String) = LibraryCommands.parse(t, lib)?.let { LibraryCommands.apply(lib, it) }

        assertEquals("Tamamdır efendim, Davranış kitaplığınıza eklendi.", say("Jarvis, kitaplığıma Davranış'ı ekle"))
        assertEquals("Davranış zaten kitaplığınızda.", say("kitaplığıma Davranış ekle"))
        assertTrue(say("Mülksüzler'e not ekle: anarşist bir ütopya, ama çok gerçekçi")!!.contains("Mülksüzler"))
        assertEquals(listOf("anarşist bir ütopya, ama çok gerçekçi"), lib.find("Mülksüzler")!!.notes)
        assertTrue(say("Tahıla Karşı'yı bitirdim")!!.contains("bitti"))
        assertEquals(BookStatus.DONE, lib.find("Tahıla Karşı")!!.status)
        say("Oblomov'da 120. sayfadayım")
        assertEquals(120, lib.find("oblomov")!!.page)
        assertEquals(BookStatus.READING, lib.find("Oblomov")!!.status)
        // An existing book is recognised without the apostrophe too.
        say("davranisi bitirdim")
        assertEquals(BookStatus.DONE, lib.find("Davranış")!!.status)
        assertTrue(say("Kitaplığımda ne var?")!!.startsWith("Kitaplığınızda 4 kitap var"))

        // Ordinary sentences are not books.
        assertNull(LibraryCommands.parse("işimi bitirdim", lib))
        assertNull(LibraryCommands.parse("yemeğimi bitirdim.", lib))
        assertNull(LibraryCommands.parse("not al: süt al", lib))
        assertEquals(4, lib.books().size)

        // Saved and read back.
        assertEquals(4, Library(files).books().size)
        assertTrue(Library(files).describe().contains("Oblomov · okunuyor · sayfa 120"))
    }

    @Test
    fun shelfPhotoReading() {
        val got = ShelfReading.parse("""Buldum: [{"ad":"Suç ve Ceza","yazar":"Dostoyevski"},{"ad":"belirsiz"},{"ad":"Suç ve Ceza"},{"ad":"Kürk Mantolu Madonna","yazar":"Sabahattin Ali"}, 5]""")
        assertEquals(listOf("Suç ve Ceza" to "Dostoyevski", "Kürk Mantolu Madonna" to "Sabahattin Ali"), got)
        val lib = Library(Files())
        lib.add("Suç ve Ceza")
        assertEquals(1, lib.addAll(got), "only the new one is added")
        assertEquals(LibraryAction.List, LibraryCommands.parse("kitaplığımda neler var", lib))
    }
}

class BookNotebookTest {
    @Test
    fun notebookKeepsToTheShelfAndAdmitsNotKnowing() {
        val shelf = listOf(
            io.github.ahmetdurmus1218.jarvis.core.library.Book(1, "Davranış"),
            io.github.ahmetdurmus1218.jarvis.core.library.Book(2, "Düşünme Hızlı ve Yavaş"),
        )
        val nb = io.github.ahmetdurmus1218.jarvis.core.library.BookNotebook.parse(
            """{"ozet":"İnsan davranışının biyolojisi.","ana_fikirler":["a","b"],"benzer":["Düşünme Hızlı ve Yavaş","Sapiens"]}""", shelf,
        )
        assertEquals(listOf("Düşünme Hızlı ve Yavaş"), nb.similar, "Sapiens is not on the shelf")
        assertEquals(false, nb.unknown)
        assertTrue(io.github.ahmetdurmus1218.jarvis.core.library.BookNotebook.parse("""{"bilinmiyor":true}""", shelf).unknown)
        assertTrue(io.github.ahmetdurmus1218.jarvis.core.library.BookNotebook.parse("bilmiyorum", shelf).unknown)
    }
}

package io.github.ahmetdurmus1218.jarvis.core.library

import io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class BookStatus(val label: String) { TO_READ("okunacak"), READING("okunuyor"), DONE("bitti") }

/** Jarvis's notebook for a book; written once by the local model, never invented quotes. */
@Serializable
data class Notebook(
    val summary: String = "",
    val ideas: List<String> = emptyList(),
    val author: String = "",
    val themes: String = "",
    val why: String = "",
    val forWhom: String = "",
    val similar: List<String> = emptyList(),
    /** The model did not know the book: nothing is made up. */
    val unknown: Boolean = false,
)

@Serializable
data class Book(
    val id: Long,
    val title: String,
    val author: String = "",
    val status: BookStatus = BookStatus.TO_READ,
    val page: Int? = null,
    val notes: List<String> = emptyList(),
    /** Quotes are only the user's own entries. */
    val quotes: List<String> = emptyList(),
    val notebook: Notebook? = null,
    val addedAt: Long = 0,
)

/** The user's books (kept in the app's encrypted files). */
class Library(private val store: TextStore, private val clock: () -> Long = { 0L }) {
    @Serializable
    private data class Saved(val nextId: Long = 1, val books: List<Book> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var state = store.read(FILE)?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() } ?: Saved()

    fun books(): List<Book> = state.books

    private fun save() = store.write(FILE, json.encodeToString(Saved.serializer(), state))

    private fun key(s: String) = Turkish.normalize(s).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

    /** The book whose title best matches [name] ("Davranış'ı" → "Davranış"); null if none. */
    fun find(name: String): Book? {
        val k = key(name)
        if (k.isEmpty()) return null
        return state.books.firstOrNull { key(it.title) == k }
            ?: state.books.filter { key(it.title).startsWith(k) || k.startsWith(key(it.title)) }.minByOrNull { it.title.length }
            ?: state.books.firstOrNull { key(it.title).contains(k) }
    }

    /** Adds a book unless it is already there; returns the stored book and whether it is new. */
    fun add(title: String, author: String = ""): Pair<Book, Boolean> {
        val t = title.trim().replace(Regex("\\s+"), " ")
        require(t.isNotEmpty())
        state.books.firstOrNull { key(it.title) == key(t) }?.let { return it to false }
        val b = Book(state.nextId, t, author.trim(), addedAt = clock())
        state = state.copy(nextId = state.nextId + 1, books = state.books + b)
        save()
        return b to true
    }

    fun update(id: Long, f: (Book) -> Book) {
        state = state.copy(books = state.books.map { if (it.id == id) f(it) else it })
        save()
    }

    fun remove(id: Long) {
        state = state.copy(books = state.books.filter { it.id != id })
        save()
    }

    /** Adds the books read from a shelf photo; returns how many were new. */
    fun addAll(found: List<Pair<String, String>>): Int = found.count { (t, a) -> t.isNotBlank() && add(t, a).second }

    /** Short text of the shelf for the model (kitaplik tool). */
    fun describe(): String = if (state.books.isEmpty()) "Kitaplık boş." else state.books.joinToString("\n") { b ->
        "- ${b.title}" + (if (b.author.isNotBlank()) " (${b.author})" else "") + " · ${b.status.label}" + (b.page?.let { " · sayfa $it" } ?: "")
    }

    private companion object { const val FILE = "library.json" }
}

/** What a library command asks for. */
sealed interface LibraryAction {
    data class Add(val title: String) : LibraryAction
    data class Note(val title: String?, val text: String) : LibraryAction
    data class Quote(val title: String?, val text: String) : LibraryAction
    data class Finished(val title: String) : LibraryAction
    data class Started(val title: String) : LibraryAction
    data class Page(val title: String, val page: Int) : LibraryAction
    data object List : LibraryAction
}

/**
 * Turkish library commands, on the text as spoken (titles keep their letters). A title in "… bitirdim",
 * "… not ekle", "… sayfadayım" counts only if the book is already on the shelf or the title is a
 * proper name with its suffix after an apostrophe ("Davranış'ı bitirdim"), so "işimi bitirdim" is
 * not mistaken for a book.
 */
object LibraryCommands {
    private const val T = "(.+?)(['’][a-zçğıöşü]{1,4})?"
    private const val J = "^(?:jarvis[, ]+)?"
    private val ADD = Regex("${J}kitapl[ıi][gğ][ıi]ma $T (?:ekle|kaydet)\\.?$", RegexOption.IGNORE_CASE)
    private val NOTE = Regex("$J$T not (?:ekle|al)[:,]? (.+)$", RegexOption.IGNORE_CASE)
    private val QUOTE = Regex("$J$T al[ıi]nt[ıi] ekle[:,]? (.+)$", RegexOption.IGNORE_CASE)
    private val DONE = Regex("$J$T bitirdim\\.?$", RegexOption.IGNORE_CASE)
    private val START = Regex("$J$T okumaya ba[sş]lad[ıi]m\\.?$", RegexOption.IGNORE_CASE)
    private val PAGE = Regex("$J$T (\\d+)\\.? sayfaday[ıi]m\\.?$", RegexOption.IGNORE_CASE)
    private val LIST = Regex("${J}kitapl[ıi][gğ][ıi]mda ne(?:ler)? var\\??$", RegexOption.IGNORE_CASE)

    private fun bookLike(lib: Library, title: String, suffix: String): Boolean =
        lib.find(title) != null || (suffix.isNotEmpty() && title.firstOrNull()?.isUpperCase() == true && title.length >= 2)

    fun parse(text: String, lib: Library): LibraryAction? {
        val t = text.trim()
        if (LIST.matches(t)) return LibraryAction.List
        ADD.matchEntire(t)?.let { return LibraryAction.Add(it.groupValues[1].trim()) }
        QUOTE.matchEntire(t)?.let { m -> if (bookLike(lib, m.groupValues[1].trim(), m.groupValues[2])) return LibraryAction.Quote(m.groupValues[1].trim(), m.groupValues[3].trim()) }
        NOTE.matchEntire(t)?.let { m -> if (bookLike(lib, m.groupValues[1].trim(), m.groupValues[2])) return LibraryAction.Note(m.groupValues[1].trim(), m.groupValues[3].trim()) }
        PAGE.matchEntire(t)?.let { m -> if (bookLike(lib, m.groupValues[1].trim(), m.groupValues[2])) return LibraryAction.Page(m.groupValues[1].trim(), m.groupValues[3].toInt()) }
        DONE.matchEntire(t)?.let { m -> if (bookLike(lib, m.groupValues[1].trim(), m.groupValues[2])) return LibraryAction.Finished(m.groupValues[1].trim()) }
        START.matchEntire(t)?.let { m -> if (bookLike(lib, m.groupValues[1].trim(), m.groupValues[2])) return LibraryAction.Started(m.groupValues[1].trim()) }
        return null
    }

    /**
     * Applies [a]; [open] = the book open on screen (for "not al: …" without a title). Unknown
     * titles in note/finished/page commands are added first (as the original does).
     */
    fun apply(lib: Library, a: LibraryAction, open: Book? = null): String = when (a) {
        is LibraryAction.Add -> {
            val (b, new) = lib.add(a.title)
            if (new) "Tamamdır efendim, ${b.title} kitaplığınıza eklendi." else "${b.title} zaten kitaplığınızda."
        }
        is LibraryAction.Note -> withBook(lib, a.title, open) { b -> lib.update(b.id) { it.copy(notes = it.notes + a.text) }; "${b.title} için notunuzu ekledim." }
        is LibraryAction.Quote -> withBook(lib, a.title, open) { b -> lib.update(b.id) { it.copy(quotes = it.quotes + a.text) }; "Alıntı ${b.title} defterine eklendi." }
        is LibraryAction.Finished -> withBook(lib, a.title, null) { b -> lib.update(b.id) { it.copy(status = BookStatus.DONE) }; "Tebrikler efendim, ${b.title} bitti olarak işaretlendi." }
        is LibraryAction.Started -> withBook(lib, a.title, null) { b -> lib.update(b.id) { it.copy(status = BookStatus.READING) }; "${b.title} okunuyor olarak işaretlendi. Keyifli okumalar." }
        is LibraryAction.Page -> withBook(lib, a.title, null) { b -> lib.update(b.id) { it.copy(status = BookStatus.READING, page = a.page) }; "${b.title}, sayfa ${a.page}. Not aldım." }
        LibraryAction.List -> {
            val books = lib.books()
            if (books.isEmpty()) "Kitaplığınız henüz boş. \"Kitaplığıma Davranış'ı ekle\" diyebilirsiniz."
            else "Kitaplığınızda ${books.size} kitap var: " + books.take(8).joinToString(", ") { it.title } + if (books.size > 8) " ve diğerleri." else "."
        }
    }

    private fun withBook(lib: Library, title: String?, open: Book?, f: (Book) -> String): String {
        val b = when {
            title.isNullOrBlank() -> open ?: return "Hangi kitap için? Kitabın adını söyleyin."
            else -> lib.find(title) ?: lib.add(title).first
        }
        return f(b)
    }
}

/** Parses the vision model's shelf reading: [{"ad":"…","yazar":"…"}, …]; spines it could not read are left out. */
object ShelfReading {
    const val PROMPT = "Bu bir kitap rafı fotoğrafı. Sırtında adı gerçekten okunabilen her kitabı yaz. Okuyamadığını, emin olmadığını yazma; uydurma. Yalnızca JSON dizisi döndür: [{\"ad\":\"Kitap adı\",\"yazar\":\"Yazar (okunuyorsa)\"}]"

    fun parse(text: String): List<Pair<String, String>> {
        val s = text.indexOf('[')
        val e = text.lastIndexOf(']')
        if (s < 0 || e <= s) return emptyList()
        val arr = runCatching { Json.parseToJsonElement(text.substring(s, e + 1)) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = (o["ad"] as? JsonPrimitive)?.content?.trim().orEmpty()
            val author = (o["yazar"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (title.length !in 2..120 || title.equals("belirsiz", true)) null else title to author.take(80)
        }.distinctBy { Turkish.normalize(it.first) }.take(200)
    }
}

/**
 * kitaplik: the user's own books for "kitaplığımda ne var", "raftan bir kitap öner". Titles may
 * come from a shelf photo, so the output is treated as data, not as instructions.
 */
class KitaplikTool(private val lib: Library?) : io.github.ahmetdurmus1218.jarvis.core.tools.Tool {
    override val name = "kitaplik"
    override val description = "Kullanıcının kendi kitaplığındaki kitaplar (ad, yazar, durum, sayfa). Kitap önerirken yalnızca buradan öner."
    override val parameters = "{}"
    override val trust = io.github.ahmetdurmus1218.jarvis.core.tools.Trust.UNTRUSTED
    override suspend fun run(input: JsonObject) = io.github.ahmetdurmus1218.jarvis.core.tools.ToolOutput(lib?.describe() ?: "Kitaplık yok.")
}

/** Jarvis's notebook for a book, written by the local model; it must not invent a book it does not know. */
object BookNotebook {
    const val SYSTEM = "Sen bir kitap defteri yazarısın. Kitabı gerçekten biliyorsan doldur; bilmiyorsan yalnızca {\"bilinmiyor\":true} yaz. Kitaptan alıntı yazma. Yalnızca JSON döndür."

    fun prompt(book: Book, shelf: List<Book>): String =
        "Kitap: ${book.title}" + (if (book.author.isNotBlank()) " — ${book.author}" else "") + "\n" +
            "Kullanıcının rafındaki diğer kitaplar: ${shelf.filter { it.id != book.id }.joinToString(", ") { it.title }.ifEmpty { "yok" }}\n" +
            "Şu JSON'u Türkçe doldur: {\"ozet\":\"3 cümle\",\"ana_fikirler\":[\"4-6 madde\"],\"yazar\":\"yazar hakkında 2 cümle\",\"temalar\":\"…\",\"neden_okumali\":\"…\",\"kime_uygun\":\"…\",\"benzer\":[\"yalnızca kullanıcının rafından en fazla 4 kitap adı\"]}"

    fun parse(text: String, shelf: List<Book>): Notebook {
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        val o = if (s >= 0 && e > s) runCatching { Json.parseToJsonElement(text.substring(s, e + 1)) as? JsonObject }.getOrNull() else null
        if (o == null || (o["bilinmiyor"] as? JsonPrimitive)?.content == "true") return Notebook(unknown = true)
        fun str(k: String) = (o[k] as? JsonPrimitive)?.content?.trim().orEmpty()
        fun list(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }.orEmpty()
        val titles = shelf.map { Turkish.normalize(it.title) }.toSet()
        return Notebook(
            summary = str("ozet"), ideas = list("ana_fikirler").take(6), author = str("yazar"), themes = str("temalar"),
            why = str("neden_okumali"), forWhom = str("kime_uygun"),
            // Similar books only from the user's own shelf.
            similar = list("benzer").filter { Turkish.normalize(it) in titles }.take(4),
            unknown = str("ozet").isEmpty(),
        )
    }
}

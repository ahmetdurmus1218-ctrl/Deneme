package io.github.ahmetdurmus1218.jarvis.core.todo

import io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Todo(val id: Long, val text: String, val done: Boolean = false, val addedAt: Long = 0)

/** The user's to-do list (kept in the app's encrypted files). */
class TodoList(private val store: TextStore, private val clock: () -> Long = { 0L }) {
    @Serializable
    private data class Saved(val nextId: Long = 1, val items: List<Todo> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var state = store.read(FILE)?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() } ?: Saved()

    fun items(): List<Todo> = state.items
    fun open(): List<Todo> = state.items.filter { !it.done }

    private fun save() = store.write(FILE, json.encodeToString(Saved.serializer(), state))
    private fun key(s: String) = Turkish.normalize(s).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

    fun add(text: String): Todo? {
        val t = text.trim().trimEnd('.').replaceFirstChar { it.uppercase() }
        if (t.isEmpty()) return null
        state.items.firstOrNull { !it.done && key(it.text) == key(t) }?.let { return it }
        val item = Todo(state.nextId, t, addedAt = clock())
        state = state.copy(nextId = state.nextId + 1, items = state.items + item)
        save()
        return item
    }

    /** The open item that best matches [text] ("sütü aldım" → "Süt al"). */
    fun find(text: String): Todo? {
        val words = key(text).split(' ').filter { it.length >= 3 }.map { it.take(4) }
        if (words.isEmpty()) return null
        return open().maxByOrNull { t -> key(t.text).split(' ').count { w -> words.any { w.startsWith(it) } } }
            ?.takeIf { t -> key(t.text).split(' ').any { w -> words.any { w.startsWith(it) } } }
    }

    fun setDone(id: Long, done: Boolean = true) {
        state = state.copy(items = state.items.map { if (it.id == id) it.copy(done = done) else it })
        save()
    }

    fun remove(id: Long) {
        state = state.copy(items = state.items.filter { it.id != id })
        save()
    }

    fun clearDone(): Int {
        val n = state.items.count { it.done }
        state = state.copy(items = state.items.filter { !it.done })
        save()
        return n
    }

    private companion object { const val FILE = "todos.json" }
}

sealed interface TodoAction {
    data class Add(val text: String) : TodoAction
    data class Done(val text: String) : TodoAction
    data object List : TodoAction
    data object ClearDone : TodoAction
}

/** Turkish to-do commands, on normalized text. */
object TodoCommands {
    private const val J = "^(?:hey )?(?:jarvis|cervis)?[ ,]*"
    private val ADD1 = Regex("${J}(?:yapilacaklar(?:im)?a|listeme|yapilacaklar listesine) (.+?) (?:ekle|yaz)$")
    private val ADD2 = Regex("${J}(?:yapilacaklar(?:im)?a ekle|listeme ekle|not al|hatirlatma ekle)[: ]+(.+)$")
    private val LIST = Regex("${J}(?:yapilacaklar(?:im)?|yapilacaklar listem|listem(?:de)?|notlarim)(?: neler| ne| nedir| ne var| var mi)?$")
    private val DONE = Regex("${J}(.+?) (?:yapildi|tamamlandi|tamam|bitti|hallettim|tamamladim)$")
    private val CLEAR = Regex("${J}(?:yapilanlari|tamamlananlari|listeyi) (?:temizle|sil)$")

    /** [raw] keeps the user's own words for the stored text. */
    fun parse(raw: String, list: TodoList): TodoAction? {
        val n = Turkish.normalize(raw).replace(Regex("[?.!]+$"), "").trim()
        if (LIST.matches(n)) return TodoAction.List
        if (CLEAR.matches(n)) return TodoAction.ClearDone
        ADD2.matchEntire(n)?.let { return TodoAction.Add(original(raw, it.groupValues[1])) }
        ADD1.matchEntire(n)?.let { return TodoAction.Add(original(raw, it.groupValues[1])) }
        DONE.matchEntire(n)?.let { m -> if (list.find(m.groupValues[1]) != null) return TodoAction.Done(m.groupValues[1]) }
        return null
    }

    /** The same words as [normalizedPart], cut from the original text (keeps ç, ğ, ı, ö, ş, ü and capitals). */
    private fun original(raw: String, normalizedPart: String): String {
        val norm = Turkish.normalize(raw)
        val i = norm.indexOf(normalizedPart)
        return if (i >= 0 && norm.length == raw.length) raw.substring(i, i + normalizedPart.length) else normalizedPart
    }

    fun apply(list: TodoList, a: TodoAction): String = when (a) {
        is TodoAction.Add -> list.add(a.text)?.let { "Listeye ekledim: ${it.text}." } ?: "Neyi ekleyeyim?"
        is TodoAction.Done -> list.find(a.text)?.let { list.setDone(it.id); "Harika, \"${it.text}\" tamamlandı." } ?: "Listede bulamadım."
        TodoAction.List -> list.open().let { o ->
            if (o.isEmpty()) "Yapılacaklar listeniz boş." else "${o.size} iş var: " + o.take(6).joinToString(", ") { it.text } + if (o.size > 6) " ve diğerleri." else "."
        }
        TodoAction.ClearDone -> "${list.clearDone()} tamamlanmış iş silindi."
    }
}

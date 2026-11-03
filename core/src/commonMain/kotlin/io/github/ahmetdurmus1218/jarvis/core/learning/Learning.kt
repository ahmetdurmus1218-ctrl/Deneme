package io.github.ahmetdurmus1218.jarvis.core.learning

import io.github.ahmetdurmus1218.jarvis.core.knowledge.Terms
import io.github.ahmetdurmus1218.jarvis.core.knowledge.TextStore
import io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiQuery
import io.github.ahmetdurmus1218.jarvis.core.knowledge.Wikipedia
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Something the user taught Rick about one question: a correction ("hayır, doğrusu …") or an
 * answer they liked (👍). Similar later questions get it in the prompt, so the same mistake is not
 * made twice. Only the user's own words and Rick's own answers are kept, on the device, sealed.
 */
@Serializable
data class Lesson(
    val id: Long,
    val question: String,
    /** The answer the user corrected (or liked). */
    val answer: String,
    /** The user's correction; null for a liked answer. */
    val correction: String? = null,
    val at: Long = 0,
) {
    val liked: Boolean get() = correction == null
}

/** A passage Rick read by itself while idle: verbatim from the offline Wikipedia, with its source. */
@Serializable
data class ResearchNote(
    val id: Long,
    /** Why it was read (the user's interest it came from). */
    val topic: String,
    val title: String,
    val path: String,
    val text: String,
    val at: Long = 0,
)

/**
 * Rick's self-improvement, entirely on the device: lessons from the user's corrections and likes,
 * and a research notebook filled while idle. No model weights change; what improves is what Rick
 * is given to work with (section 5.5's idea, extended).
 */
class Learning(private val store: TextStore, private val clock: () -> Long = { 0L }) {
    @Serializable
    private data class Saved(
        val nextId: Long = 1,
        val lessons: List<Lesson> = emptyList(),
        val notes: List<ResearchNote> = emptyList(),
        /** Topics already researched (normalized), so idle time moves on to new ones. */
        val done: List<String> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var state: Saved = store.read(FILE)?.let { runCatching { json.decodeFromString(Saved.serializer(), it) }.getOrNull() } ?: Saved()

    private fun save() = store.write(FILE, json.encodeToString(Saved.serializer(), state))

    fun lessons(): List<Lesson> = state.lessons
    fun notes(): List<ResearchNote> = state.notes

    /** Stores a correction; a newer one for the same question replaces the older. */
    fun correct(question: String, answer: String, correction: String): Lesson = put(question, answer, correction.trim())

    /** Stores an answer the user liked (a good example for similar questions). */
    fun like(question: String, answer: String): Lesson = put(question, answer, null)

    private fun put(question: String, answer: String, correction: String?): Lesson {
        val key = key(question)
        val lesson = Lesson(state.nextId, question.trim(), answer.trim().take(MAX_ANSWER), correction?.take(MAX_ANSWER), clock())
        val kept = state.lessons.filterNot { key(it.question) == key }
        state = state.copy(nextId = state.nextId + 1, lessons = (kept + lesson).takeLast(MAX_LESSONS))
        save()
        return lesson
    }

    fun removeLesson(id: Long) {
        state = state.copy(lessons = state.lessons.filter { it.id != id })
        save()
    }

    fun removeNote(id: Long) {
        state = state.copy(notes = state.notes.filter { it.id != id })
        save()
    }

    fun clear() {
        state = Saved(nextId = state.nextId)
        save()
    }

    /** Lessons about questions like [question], corrections first, best match first. */
    fun relevantLessons(question: String, limit: Int = 3): List<Lesson> =
        ranked(question, state.lessons) { it.question }.sortedBy { it.liked }.take(limit)

    /** Notes whose title or text share enough words with [question]. */
    fun relevantNotes(question: String, limit: Int = 2): List<ResearchNote> =
        ranked(question, state.notes) { it.title + " " + it.text.take(400) }.take(limit)

    /** The lesson for this very question, if the user taught one (a near-identical question). */
    fun exactCorrection(question: String): Lesson? {
        val q = Terms.of(question).toSet()
        if (q.isEmpty()) return null
        return state.lessons.filter { !it.liked }.firstOrNull { l ->
            val t = Terms.of(l.question).toSet()
            t.isNotEmpty() && (q intersect t).size >= maxOf(q.size, t.size) * 0.8
        }
    }

    private fun <T> ranked(question: String, items: List<T>, text: (T) -> String): List<T> {
        val q = Terms.of(question).toSet()
        if (q.isEmpty()) return emptyList()
        return items.map { it to Terms.of(text(it)).toSet() }
            .map { (item, t) -> item to (q intersect t).size }
            // At least two shared words, or every word of a one/two-word question.
            .filter { (_, shared) -> shared >= 2 || (q.size <= 2 && shared >= q.size) }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    fun addNote(topic: String, title: String, path: String, text: String): ResearchNote? {
        if (state.notes.any { it.path == path } || text.isBlank()) return null
        val note = ResearchNote(state.nextId, topic, title, path, text, clock())
        state = state.copy(nextId = state.nextId + 1, notes = (state.notes + note).takeLast(MAX_NOTES))
        save()
        return note
    }

    fun markResearched(topic: String) {
        val k = key(topic)
        if (k in state.done) return
        state = state.copy(done = (state.done + k).takeLast(MAX_DONE))
        save()
    }

    fun researched(topic: String): Boolean = key(topic) in state.done

    /** Notes made since [since] (epoch ms), for the briefing. */
    fun notesSince(since: Long): List<ResearchNote> = state.notes.filter { it.at >= since }

    private fun key(s: String) = Turkish.normalize(s).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex(" +"), " ").trim()

    companion object {
        const val FILE = "learning.json"
        const val MAX_LESSONS = 200
        const val MAX_NOTES = 300
        const val MAX_DONE = 1000
        const val MAX_ANSWER = 600
    }
}

/** Recognizes a correction of the previous answer: "hayır, doğrusu …", "yanlış, … olacak". */
object CorrectionDetector {
    private val PATTERNS = listOf(
        Regex("^(?:hayir|yok|yanlis|olmadi|oyle degil|bu yanlis|yanlis bildin|hatali)[ ,.!:;-]+(?:dogrusu|aslinda|dogru cevap|dogru olan)?[ ,:]*(?:su[ ,:]+)?(.{2,})$"),
        Regex("^(?:dogrusu|dogru cevap|dogru olan|aslinda)[ ,:]+(.{2,})$"),
    )

    /** The corrected fact in the user's own words, or null if [text] is not a correction. */
    fun parse(text: String): String? {
        val n = Turkish.normalize(text)
        for (p in PATTERNS) {
            val m = p.matchEntire(n) ?: continue
            val part = Turkish.cutOriginal(text, m.groupValues[1]).trim().trimEnd('.')
            if (part.length >= 2) return part.replaceFirstChar { it.uppercase() }
        }
        return null
    }
}

/**
 * Reads the offline Wikipedia while Rick is idle, about what the user cares about: places and names
 * from their memory, their books, their recent questions. Each note is the start of an article,
 * verbatim, with its title — nothing is summarized or invented.
 */
class Researcher(private val wiki: Wikipedia, private val learning: Learning) {
    /** Topics worth reading about, most recent interests first, without ones already done. */
    fun topics(facts: List<String>, books: List<String>, recentQuestions: List<String>): List<String> {
        val fromQuestions = recentQuestions.asReversed().map { WikiQuery.clean(it) }.filter { it.split(' ').size in 1..4 }
        val fromFacts = facts.asReversed().flatMap(::properNouns)
        return (fromQuestions + books + fromFacts)
            .map { it.trim() }
            .filter { it.length >= 3 && !learning.researched(it) }
            .distinctBy { Turkish.normalize(it) }
    }

    /** One research step: the best article for [topic] not yet in the notebook. */
    suspend fun research(topic: String): ResearchNote? {
        learning.markResearched(topic)
        if (!wiki.isReady()) return null
        val known = learning.notes().map { it.path }.toSet()
        val hit = wiki.search(topic, 3).firstOrNull { it.path !in known } ?: return null
        val article = wiki.read(hit.path) ?: return null
        val lead = leadSentences(article.text)
        if (lead.length < 40) return null
        return learning.addNote(topic, article.title, hit.path, lead)
    }

    companion object {
        private val NOT_NAMES = setOf(
            "ben", "benim", "bana", "beni", "biz", "bizim", "sen", "senin", "o", "bu", "su", "bugun", "yarin", "dun",
            "evet", "hayir", "rick", "jarvis", "merhaba", "tamam", "lutfen", "cok", "her", "hep",
        )

        /** Capitalized words of the user's own sentence ("Manisa'da yaşıyorum" → Manisa). */
        fun properNouns(sentence: String): List<String> = Regex("\\b\\p{Lu}[\\p{L}]{2,}(?:['’]\\p{L}+)?").findAll(sentence)
            // A capital at the start of a sentence is just the start ("Kızımın adı…"), unless the word
            // carries a name's apostrophe ("Manisa'da yaşıyorum").
            .filterNot { m -> startsSentence(sentence, m.range.first) && '\'' !in m.value && '’' !in m.value }
            .map { it.value.substringBefore('\'').substringBefore('’') }
            .filter { Turkish.normalize(it) !in NOT_NAMES }
            .toList()

        private fun startsSentence(s: String, at: Int): Boolean {
            val before = s.substring(0, at).trimEnd()
            return before.isEmpty() || before.last() in ".!?:"
        }

        /** The first few sentences (up to about 600 characters), cut at a sentence end. */
        fun leadSentences(text: String, maxChars: Int = 600): String {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            val sentences = Regex("(?<=[.!?])\\s+").split(clean)
            val out = StringBuilder()
            for (s in sentences) {
                if (out.isNotEmpty() && out.length + s.length + 1 > maxChars) break
                if (out.isNotEmpty()) out.append(' ')
                out.append(s)
                if (out.length >= maxChars) break
            }
            return out.toString().take(maxChars + 200)
        }
    }
}

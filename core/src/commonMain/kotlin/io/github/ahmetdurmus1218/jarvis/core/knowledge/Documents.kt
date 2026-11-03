package io.github.ahmetdurmus1218.jarvis.core.knowledge

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.core.tools.Tool
import io.github.ahmetdurmus1218.jarvis.core.tools.ToolOutput
import io.github.ahmetdurmus1218.jarvis.core.tools.Trust
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.ln

/** Where the index is kept (the app's private files; encrypted storage arrives with v0.4). */
interface TextStore {
    fun read(name: String): String?
    fun write(name: String, text: String)
}

@Serializable
data class DocInfo(val id: Long, val title: String, val chunks: Int, val chars: Int, val addedAtMillis: Long)

@Serializable
data class DocChunk(val docId: Long, val ordinal: Int, val text: String)

@Serializable
private data class DocState(val nextId: Long = 1, val docs: List<DocInfo> = emptyList(), val chunks: List<DocChunk> = emptyList())

data class DocHit(val doc: DocInfo, val chunk: DocChunk, val score: Double)

/**
 * The user's own documents (PDF/TXT/MD): split into ~700-character pieces and searched with BM25.
 * Turkish words carry many suffixes, so terms are cut to their first five letters ("Ankara'nın",
 * "Ankara'da" → "ankar"), which works well for Turkish retrieval without a dictionary.
 */
class DocumentIndex(private val store: TextStore, private val clock: () -> Long) {
    private val lock = Mutex()
    private var state: DocState = load()
    private var index: InvertedIndex = InvertedIndex(state.chunks)

    private fun load(): DocState = store.read(FILE)?.let { runCatching { json.decodeFromString(DocState.serializer(), it) }.getOrNull() } ?: DocState()

    private fun save() = store.write(FILE, json.encodeToString(DocState.serializer(), state))

    fun list(): List<DocInfo> = state.docs

    fun isEmpty(): Boolean = state.docs.isEmpty()

    suspend fun add(title: String, text: String): DocInfo = lock.withLock {
        val clean = text.replace("\r\n", "\n").replace('\u0000', ' ').trim()
        require(clean.isNotBlank()) { "Belgede okunabilir metin yok" }
        require(state.docs.size < MAX_DOCS) { "En fazla $MAX_DOCS belge eklenebilir" }
        val id = state.nextId
        val pieces = Chunker.split(clean)
        require(state.chunks.size + pieces.size <= MAX_CHUNKS) { "Belge dizini dolu; önce eski belgelerden silin" }
        val info = DocInfo(id, title.ifBlank { "Belge $id" }, pieces.size, clean.length, clock())
        state = state.copy(
            nextId = id + 1,
            docs = state.docs + info,
            chunks = state.chunks + pieces.mapIndexed { i, p -> DocChunk(id, i, p) },
        )
        index = InvertedIndex(state.chunks)
        save()
        info
    }

    suspend fun remove(id: Long): Boolean = lock.withLock {
        if (state.docs.none { it.id == id }) return@withLock false
        state = state.copy(docs = state.docs.filter { it.id != id }, chunks = state.chunks.filter { it.docId != id })
        index = InvertedIndex(state.chunks)
        save()
        true
    }

    suspend fun search(query: String, limit: Int = 4): List<DocHit> = lock.withLock {
        val byId = state.docs.associateBy { it.id }
        index.search(query, limit).mapNotNull { (chunk, score) -> byId[chunk.docId]?.let { DocHit(it, chunk, score) } }
    }

    companion object {
        const val FILE = "documents.json"
        const val MAX_DOCS = 200
        const val MAX_CHUNKS = 30_000
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** Paragraph- and sentence-aware pieces of at most [target] characters. */
object Chunker {
    private val sentenceEnd = Regex("(?<=[.!?;:])\\s+")

    fun split(text: String, target: Int = 700): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        val units = paragraphs.flatMap { p -> if (p.length <= target) listOf(p) else sentences(p, target) }
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (u in units) {
            if (cur.isNotEmpty() && cur.length + 1 + u.length > target) {
                out += cur.toString()
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(u)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Sentences of a long paragraph; a sentence longer than [target] is wrapped at spaces. */
    private fun sentences(p: String, target: Int): List<String> = p.split(sentenceEnd).flatMap { s ->
        if (s.length <= target) listOf(s) else wrap(s, target)
    }

    private fun wrap(s: String, target: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > target) {
            val cut = rest.lastIndexOf(' ', target).takeIf { it > target / 2 } ?: target
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}

/** BM25 over chunks (k1 = 1.2, b = 0.75). */
internal class InvertedIndex(private val chunks: List<DocChunk>) {
    private val postings = HashMap<String, MutableList<Pair<Int, Int>>>() // term → (chunk index, frequency)
    private val lengths = IntArray(chunks.size)
    private val avgLength: Double

    init {
        chunks.forEachIndexed { i, c ->
            val terms = Terms.of(c.text)
            lengths[i] = terms.size
            terms.groupingBy { it }.eachCount().forEach { (t, f) -> postings.getOrPut(t) { mutableListOf() } += i to f }
        }
        avgLength = if (chunks.isEmpty()) 1.0 else lengths.average().coerceAtLeast(1.0)
    }

    fun search(query: String, limit: Int): List<Pair<DocChunk, Double>> {
        val n = chunks.size
        if (n == 0) return emptyList()
        val scores = HashMap<Int, Double>()
        for (t in Terms.of(query).distinct()) {
            val list = postings[t] ?: continue
            val idf = ln(1.0 + (n - list.size + 0.5) / (list.size + 0.5))
            for ((i, f) in list) {
                val tf = f * (K1 + 1) / (f + K1 * (1 - B + B * lengths[i] / avgLength))
                scores[i] = (scores[i] ?: 0.0) + idf * tf
            }
        }
        return scores.entries.sortedByDescending { it.value }.take(limit).map { chunks[it.key] to it.value }
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
    }
}

/** Turkish-aware terms: normalized, apostrophe suffixes dropped, stop words out, first five letters. */
object Terms {
    private val stop = setOf(
        "ve", "veya", "ile", "bir", "bu", "o", "da", "de", "ki", "mi", "mu", "ne", "icin", "gibi", "daha", "cok",
        "olan", "olarak", "en", "ya", "ama", "fakat", "her", "hem", "kadar", "sonra", "once", "ise", "the", "and", "of", "to",
        "in", "is", "a", "an", "nedir", "kimdir", "nerede", "nasil", "hangi", "kac", "belge", "belgede", "belgemde",
    )
    private val split = Regex("[^\\p{L}\\p{N}]+")

    fun of(text: String): List<String> = Turkish.normalize(text.replace('’', '\'').replace(Regex("'\\p{L}+"), " "))
        .split(split)
        .filter { it.isNotEmpty() && it !in stop && (it.length > 1 || it[0].isDigit()) }
        .map { if (it[0].isDigit()) it else it.take(5) }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** belge_ara: searches the user's own documents. */
class BelgeAraTool(private val index: DocumentIndex?) : Tool {
    override val name = "belge_ara"
    override val description = "Kullanıcının içe aktardığı belgelerde (PDF/TXT/MD) arama yapar ve ilgili parçaları verir."
    override val parameters = "{\"sorgu\": \"metin\"}"
    override val trust = Trust.UNTRUSTED

    override suspend fun run(input: JsonObject): ToolOutput {
        val q = input.str("sorgu")?.trim().orEmpty()
        if (index == null || index.isEmpty()) {
            return ToolOutput(buildJsonObject { put("sonuclar", buildJsonArray { }); put("not", "Henüz belge eklenmedi") }.toString(), ok = false)
        }
        if (q.isEmpty()) return ToolOutput("{\"hata\":\"sorgu eksik\"}", ok = false)
        val hits = index.search(WikiQuery.clean(q))
        return ToolOutput(
            buildJsonObject {
                put("sorgu", q)
                put("sonuclar", buildJsonArray {
                    hits.forEach { h ->
                        add(buildJsonObject {
                            put("belge", h.doc.title)
                            put("parca", h.chunk.ordinal + 1)
                            put("metin", h.chunk.text)
                        })
                    }
                })
            }.toString(),
            ok = hits.isNotEmpty(),
        )
    }
}

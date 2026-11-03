package io.github.ahmetdurmus1218.jarvis.core.knowledge

import io.github.ahmetdurmus1218.jarvis.core.text.HtmlText
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.core.tools.Tool
import io.github.ahmetdurmus1218.jarvis.core.tools.ToolOutput
import io.github.ahmetdurmus1218.jarvis.core.tools.Trust
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class WikiHit(val title: String, val path: String, val snippet: String = "")

/** What a platform provides for an offline Wikipedia (libkiwix on Android, kiwix-serve on PC). */
interface ZimBackend {
    /** True when a verified ZIM is installed and can be opened. */
    fun isReady(): Boolean
    /** Title (suggestion) search: good for names and topics. */
    suspend fun titleSearch(query: String, limit: Int): List<WikiHit>
    /** Full-text search over article bodies. */
    suspend fun fullTextSearch(query: String, limit: Int): List<WikiHit>
    /** The article's HTML and title, following redirects; null if there is no such entry. */
    suspend fun article(path: String): Pair<String, String>?
}

data class WikiArticle(val title: String, val path: String, val text: String)

/** Search and reading on top of a [ZimBackend], shared by both platforms. */
class Wikipedia(private val backend: ZimBackend) {
    fun isReady(): Boolean = backend.isReady()

    /** Title hits first (an exact title wins), then full-text hits, without duplicates. */
    suspend fun search(question: String, limit: Int = 5): List<WikiHit> {
        val q = WikiQuery.clean(question)
        if (q.isBlank()) return emptyList()
        val titles = runCatching { backend.titleSearch(q, limit) }.getOrDefault(emptyList())
        val bodies = runCatching { backend.fullTextSearch(q, limit) }.getOrDefault(emptyList())
        val nq = Turkish.normalize(q)
        val exact = titles.filter { Turkish.normalize(it.title) == nq }
        return (exact + titles + bodies).distinctBy { it.path }.take(limit)
    }

    suspend fun read(path: String, part: Int = 1, partChars: Int = PART_CHARS): WikiArticle? {
        val (html, title) = backend.article(path) ?: return null
        val text = HtmlText.toText(html)
        val from = ((part - 1).coerceAtLeast(0)) * partChars
        if (from >= text.length && part > 1) return WikiArticle(title, path, "")
        return WikiArticle(title, path, text.substring(from, minOf(text.length, from + partChars)))
    }

    /** Total length of the article text (to tell the model whether more parts exist). */
    suspend fun length(path: String): Int = backend.article(path)?.let { HtmlText.toText(it.first).length } ?: 0

    companion object {
        const val PART_CHARS = 6_000
        const val FIRST_ARTICLE_CHARS = 2_500
    }
}

/** Turns a spoken question into a search query: "Atatürk kimdir?" → "Atatürk". */
object WikiQuery {
    // Normalized (see Turkish.normalize): question words, politeness and fillers only.
    private val stop = setOf(
        "kim", "kimdir", "kimdi", "ne", "nedir", "neydi", "nerede", "neredir", "nerededir", "neresi", "neresidir",
        "kac", "kactir", "kacti", "nasil", "nasildir", "hangi", "hangisi", "hangisidir",
        "hakkinda", "bilgi", "ver", "verir", "misin", "musun", "mi", "mu", "anlat", "anlatir",
        "bana", "soyle", "soyler", "lutfen", "acikla", "aciklar", "jarvis", "biraz", "kisaca", "detayli",
        "ilgili", "midir", "mudur",
        "who", "what", "is", "are", "the", "a", "an", "of", "when", "where", "how", "tell", "me", "about",
    )
    private val split = Regex("[\\s,.;:!?()\"“”«»]+")

    fun clean(question: String): String {
        val words = split.split(question.trim()).filter { it.isNotBlank() }.mapNotNull { w ->
            // Drop Turkish case suffixes written after an apostrophe: "Türkiye'nin" → "Türkiye".
            val base = w.substringBefore('\'').substringBefore('’')
            base.takeIf { it.isNotBlank() && Turkish.normalize(it) !in stop }
        }
        return words.joinToString(" ").ifBlank { question.trim() }
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

/** wiki_ara: searches the offline Wikipedia and returns the hits plus the start of the best article. */
class WikiAraTool(private val wiki: Wikipedia?, private val label: String = "Türkçe Wikipedia") : Tool {
    override val name = "wiki_ara"
    override val description = "Çevrimdışı $label'da arama yapar; sonuçları ve en iyi makalenin başını verir."
    override val parameters = "{\"sorgu\": \"metin\"}"
    override val trust = Trust.UNTRUSTED

    override suspend fun run(input: JsonObject): ToolOutput {
        val q = input.str("sorgu")?.trim().orEmpty()
        if (wiki == null || !wiki.isReady()) return notInstalled()
        if (q.isEmpty()) return ToolOutput("{\"hata\":\"sorgu eksik\"}", ok = false)
        val hits = wiki.search(q)
        val first = hits.firstOrNull()?.let { wiki.read(it.path, partChars = Wikipedia.FIRST_ARTICLE_CHARS) }
        return ToolOutput(
            buildJsonObject {
                put("sorgu", q)
                put("kaynak", label)
                put("sonuclar", buildJsonArray {
                    hits.forEach { h -> add(buildJsonObject { put("baslik", h.title); put("yol", h.path); put("ozet", h.snippet) }) }
                })
                if (first != null) {
                    put("ilk_makale", buildJsonObject { put("baslik", first.title); put("yol", first.path); put("metin", first.text) })
                }
            }.toString(),
            ok = hits.isNotEmpty(),
        )
    }

    private fun notInstalled() =
        ToolOutput(buildJsonObject { put("sonuclar", buildJsonArray { }); put("not", "Wikipedia paketi henüz kurulu değil") }.toString(), ok = false)
}

/** wiki_oku: reads an article (6000 characters per part). */
class WikiOkuTool(private val wiki: Wikipedia?) : Tool {
    override val name = "wiki_oku"
    override val description = "Wikipedia makalesini okur (yol wiki_ara sonucundan gelir; uzun makaleler parça parça)."
    override val parameters = "{\"yol\": \"metin\", \"parca\": sayı (isteğe bağlı, 1'den başlar)}"
    override val trust = Trust.UNTRUSTED

    override suspend fun run(input: JsonObject): ToolOutput {
        if (wiki == null || !wiki.isReady()) {
            return ToolOutput(buildJsonObject { put("not", "Wikipedia paketi henüz kurulu değil") }.toString(), ok = false)
        }
        val path = input.str("yol")?.trim().orEmpty()
        if (path.isEmpty()) return ToolOutput("{\"hata\":\"yol eksik\"}", ok = false)
        val part = input["parca"]?.jsonPrimitive?.intOrNull ?: 1
        val a = wiki.read(path, part) ?: return ToolOutput(buildJsonObject { put("hata", "makale bulunamadı: $path") }.toString(), ok = false)
        val total = wiki.length(path)
        return ToolOutput(
            buildJsonObject {
                put("baslik", a.title); put("yol", a.path); put("parca", part)
                put("devami_var", part * Wikipedia.PART_CHARS < total)
                put("metin", a.text)
            }.toString(),
            ok = a.text.isNotBlank(),
        )
    }
}

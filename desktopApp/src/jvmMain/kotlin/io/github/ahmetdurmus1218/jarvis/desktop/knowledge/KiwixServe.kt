package io.github.ahmetdurmus1218.jarvis.desktop.knowledge

import io.github.ahmetdurmus1218.jarvis.core.knowledge.WikiHit
import io.github.ahmetdurmus1218.jarvis.core.knowledge.ZimBackend
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.text.HtmlText
import io.github.ahmetdurmus1218.jarvis.desktop.llm.LlamaServerEngine
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.Normalizer
import java.time.Duration

/**
 * Offline Wikipedia on the PC through kiwix-serve (hash-verified kiwix-tools), started as a child
 * process on 127.0.0.1 with the installed ZIM. Search and articles come over its local HTTP API;
 * the process is stopped when Jarvis closes or the ZIM changes.
 */
class KiwixServe(
    private val zimPath: () -> String?,
    private val toolsZip: () -> File?,
    private val binDir: File,
) : ZimBackend, AutoCloseable {
    private val lock = Mutex()
    private var process: Process? = null
    private var baseUrl: String? = null
    private var servedZim: String? = null
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    init {
        Runtime.getRuntime().addShutdownHook(Thread { close() })
    }

    override fun isReady(): Boolean = zimPath() != null && toolsZip() != null

    /** The name kiwix-serve gives a ZIM in its URLs: file name without extension, lowercase, no diacritics. */
    fun zimName(path: String): String = zimNameOf(File(path).name)

    private suspend fun server(): Pair<String, String> = lock.withLock {
        val zim = zimPath() ?: error("Wikipedia paketi kurulu değil")
        val zip = toolsZip() ?: error("kiwix-tools kurulu değil")
        val running = process
        val url = baseUrl
        if (running != null && running.isAlive && url != null && servedZim == zim) return@withLock url to zimName(zim)
        stopLocked()
        val exe = executable(zip)
        val port = ServerSocket(0).use { it.localPort }
        val log = File(binDir, "kiwix-serve.log")
        val p = ProcessBuilder(exe.absolutePath, "--address=127.0.0.1", "--port=$port", zim)
            .directory(exe.parentFile).redirectErrorStream(true).redirectOutput(log).start()
        process = p
        val u = "http://127.0.0.1:$port"
        val deadline = System.currentTimeMillis() + 30_000
        while (!healthy(u)) {
            if (!p.isAlive) error("kiwix-serve kapandı (ayrıntı: ${log.absolutePath})")
            if (System.currentTimeMillis() > deadline) {
                p.destroy()
                error("kiwix-serve 30 saniyede hazır olmadı")
            }
            delay(200)
        }
        baseUrl = u
        servedZim = zim
        u to zimName(zim)
    }

    private fun executable(zip: File): File {
        val target = File(binDir, zip.nameWithoutExtension).apply { mkdirs() }
        val marker = File(target, ".extracted")
        if (!marker.isFile) {
            LlamaServerEngine.extractZip(zip, target)
            marker.writeText(zip.name)
        }
        return target.walkTopDown().firstOrNull { it.isFile && (it.name == "kiwix-serve.exe" || it.name == "kiwix-serve") }
            ?.also { it.setExecutable(true) }
            ?: error("kiwix-serve bulunamadı (${zip.name})")
    }

    private fun healthy(base: String): Boolean = runCatching {
        get("$base/catalog/v2/entries?count=0", Duration.ofSeconds(2)).statusCode() == 200
    }.getOrDefault(false)

    private fun get(url: String, timeout: Duration = Duration.ofSeconds(20)): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString())

    override suspend fun titleSearch(query: String, limit: Int): List<WikiHit> = withContext(Dispatchers.IO) {
        val (base, name) = server()
        val r = get("$base/suggest?content=${enc(name)}&term=${enc(query)}&count=$limit")
        if (r.statusCode() != 200) return@withContext emptyList()
        parseSuggest(r.body(), name).take(limit)
    }

    override suspend fun fullTextSearch(query: String, limit: Int): List<WikiHit> = withContext(Dispatchers.IO) {
        val (base, name) = server()
        val r = get("$base/search?content=${enc(name)}&pattern=${enc(query)}&start=0&pageLength=$limit&format=xml")
        if (r.statusCode() != 200) return@withContext emptyList()
        parseSearchXml(r.body(), name).take(limit)
    }

    override suspend fun article(path: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val (base, name) = server()
        val encoded = path.split('/').joinToString("/") { enc(it).replace("+", "%20") }
        val r = get("$base/raw/${enc(name)}/content/$encoded")
        if (r.statusCode() != 200) return@withContext null
        val html = r.body()
        html to (HtmlText.title(html) ?: path.substringAfterLast('/').replace('_', ' '))
    }

    private fun stopLocked() {
        process?.let { p ->
            p.descendants().forEach { it.destroy() }
            p.destroy()
        }
        process = null
        baseUrl = null
        servedZim = null
    }

    override fun close() {
        process?.let { p ->
            p.descendants().forEach { it.destroy() }
            p.destroy()
        }
        process = null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

        fun zimNameOf(fileName: String): String {
            val base = fileName.substringBeforeLast('.').lowercase()
            val noMarks = Normalizer.normalize(base, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return noMarks.replace(' ', '_').replace("+", "plus")
        }

        /** "/content/NAME/A/Some_Page" (or an absolute URL, or a /raw/ link) → "A/Some_Page", percent-decoded. */
        fun pathFromLink(link: String, name: String): String {
            var l = HtmlText.decode(link.trim())
            if ("://" in l) {
                val rest = l.substringAfter("://")
                l = rest.indexOf('/').let { if (it < 0) "/" else rest.substring(it) }
            }
            l = l.substringBefore('?').substringBefore('#')
            val prefixes = listOf("/content/$name/", "/raw/$name/content/", "content/$name/", "/$name/")
            val raw = prefixes.firstOrNull { l.startsWith(it) }?.let { l.removePrefix(it) } ?: l.trimStart('/')
            // URLDecoder turns '+' into a space; a literal plus in a ZIM path must survive.
            return URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8)
        }

        /** kiwix-serve's /search?format=xml is an OpenSearch RSS feed: one <item> per hit. */
        fun parseSearchXml(xml: String, name: String): List<WikiHit> {
            val item = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            fun tag(block: String, t: String) =
                Regex("<$t>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim().orEmpty()
            return item.findAll(xml).mapNotNull { m ->
                val b = m.groupValues[1]
                val link = tag(b, "link")
                if (link.isEmpty()) return@mapNotNull null
                val title = HtmlText.decode(tag(b, "title")).replace(Regex("<[^>]+>"), "")
                val snippet = HtmlText.decode(HtmlText.decode(tag(b, "description"))).replace(Regex("<[^>]+>"), "")
                WikiHit(title.ifBlank { pathFromLink(link, name) }, pathFromLink(link, name), snippet)
            }.toList()
        }

        /** /suggest returns a JSON array; entries of kind "path" are pages (others offer a full-text search). */
        fun parseSuggest(body: String, name: String): List<WikiHit> = runCatching {
            json.parseToJsonElement(body).jsonArray.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                if (s("kind") != null && s("kind") != "path") return@mapNotNull null
                val path = s("path") ?: return@mapNotNull null
                val title = s("value") ?: s("label")?.replace(Regex("<[^>]+>"), "") ?: path
                WikiHit(HtmlText.decode(title), pathFromLink(path, name))
            }
        }.getOrDefault(emptyList())

        /** The installed, verified ZIM and kiwix-tools zip for the PC. */
        fun forInstalled(storage: ModelStorage, registry: ModelRegistry, binDir: File): KiwixServe {
            val items = registry.forPlatform(Platform.DESKTOP)
            return KiwixServe(
                zimPath = { items.filter { it.kind == "zim" }.firstOrNull { storage.isInstalled(it) }?.let(storage::pathOf) },
                toolsZip = { items.firstOrNull { it.group == "bin-kiwix" && storage.isInstalled(it) }?.let { File(storage.pathOf(it)) } },
                binDir = binDir,
            )
        }
    }
}

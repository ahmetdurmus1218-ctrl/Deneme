package io.github.ahmetdurmus1218.jarvis.desktop

import com.sun.net.httpserver.HttpServer
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.net.DownloadPolicy
import io.github.ahmetdurmus1218.jarvis.desktop.kapi.DesktopKapi
import io.github.ahmetdurmus1218.jarvis.desktop.kapi.PinningTrust
import io.github.ahmetdurmus1218.jarvis.ui.DownloadOutcome
import kotlinx.coroutines.test.runTest
import java.io.File
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopKapiTest {
    private val body = "hello world".toByteArray()
    private val sha = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
    private val ranges = Collections.synchronizedList(mutableListOf<String?>())
    private lateinit var server: HttpServer
    private lateinit var base: String

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            ranges += range
            val from = range?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toInt() }
            if (from != null) {
                ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                ex.sendResponseHeaders(206, (body.size - from).toLong())
                ex.responseBody.use { it.write(body, from, body.size - from) }
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        server.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "/file")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/norange") { ex ->
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/big") { ex ->
            ex.sendResponseHeaders(200, 500)
            ex.responseBody.use { it.write(ByteArray(500)) }
        }
        server.createContext("/gone") { ex ->
            ex.sendResponseHeaders(416, -1)
            ex.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun item(path: String, kind: String = "llm", name: String = "m.gguf") =
        LockedItem("x", kind, listOf("desktop"), "X", "g", base + path, "127.0.0.1", name, body.size.toLong(), sha, "r", "o")

    /** Local plain-HTTP test server: the URL policy is relaxed only here. */
    private fun kapi(dir: File = Files.createTempDirectory("kapi").toFile()) =
        DesktopKapi(dir, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) { DownloadPolicy.Verdict.Allowed }

    @Test
    fun productionPolicyRefusesBeforeAnyNetworkRequest() = runTest {
        val kapi = DesktopKapi(Files.createTempDirectory("kapi").toFile())
        fun locked(url: String, name: String, kind: String = "llm") = LockedItem("x", kind, listOf("desktop"), "X", "g", url, "h", name, 10, "a".repeat(64), "r", "o")
        assertIs<DownloadOutcome.Failed>(kapi.download(locked("https://evil.example/x.gguf", "x.gguf")) {})
        assertIs<DownloadOutcome.Failed>(kapi.download(locked("http://huggingface.co/x.gguf", "x.gguf")) {})
        assertIs<DownloadOutcome.Failed>(kapi.download(locked("https://huggingface.co/x.exe", "x.exe", "binary")) {})
        assertIs<DownloadPolicy.Verdict.Denied>(DesktopKapi.productionUrlCheck("https://huggingface.co\\@evil.example/x"))
        assertIs<DownloadPolicy.Verdict.Allowed>(DesktopKapi.productionUrlCheck("https://huggingface.co/a/b.gguf"))
    }

    @Test
    fun followsRedirectAndReportsTheHash() = runTest {
        val out = kapi().download(item("/redirect")) {}
        assertIs<DownloadOutcome.Done>(out)
        assertEquals(sha, out.sha256)
        assertEquals(body.size.toLong(), out.bytes)
        assertContentEquals(body, File(out.path).readBytes())
    }

    @Test
    fun resumesAPartialDownload() = runTest {
        val dir = Files.createTempDirectory("kapi").toFile()
        val k = kapi(dir)
        val it = item("/file")
        k.partialFile(it).apply { parentFile.mkdirs(); writeBytes(body.copyOfRange(0, 5)) }
        val out = k.download(it) {}
        assertIs<DownloadOutcome.Done>(out)
        assertEquals(listOf<String?>("bytes=5-"), ranges)
        assertContentEquals(body, File(out.path).readBytes())
    }

    @Test
    fun restartsWhenTheServerIgnoresRange() = runTest {
        val dir = Files.createTempDirectory("kapi").toFile()
        val k = kapi(dir)
        val it = item("/norange")
        k.partialFile(it).apply { parentFile.mkdirs(); writeBytes("HELLO".toByteArray()) }
        val out = k.download(it) {}
        assertIs<DownloadOutcome.Done>(out)
        assertContentEquals(body, File(out.path).readBytes())
    }

    @Test
    fun refusesOversizedResponses() = runTest {
        val out = kapi().download(item("/big")) {}
        assertIs<DownloadOutcome.Failed>(out)
        assertTrue(out.message.contains("Boyut"), out.message)
    }

    @Test
    fun invalidRangeDropsThePartialFile() = runTest {
        val dir = Files.createTempDirectory("kapi").toFile()
        val k = kapi(dir)
        val it = item("/gone")
        val partial = k.partialFile(it).apply { parentFile.mkdirs(); writeBytes("hel".toByteArray()) }
        assertIs<DownloadOutcome.Failed>(k.download(it) {})
        assertTrue(!partial.exists())
    }

    // ---- pins checked against the real certificate authorities in the JDK trust store ----

    private fun root(cn: String): X509Certificate {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers
            .firstOrNull { it.subjectX500Principal.name.contains("CN=$cn,") || it.subjectX500Principal.name.endsWith("CN=$cn") }
            ?: error("root $cn not in trust store")
    }

    @Test
    fun pinsMatchTheRealCertificateAuthorities() {
        assertTrue(PinningTrust.spki(root("Amazon Root CA 1")) in PinningTrust.pinsFor("huggingface.co"))
        assertTrue(PinningTrust.spki(root("Amazon Root CA 1")) in PinningTrust.pinsFor("us.aws.cdn.hf.co"))
        assertTrue(PinningTrust.spki(root("ISRG Root X1")) in PinningTrust.pinsFor("mirror.download.kiwix.org"))
        assertTrue(PinningTrust.spki(root("ISRG Root X1")) in PinningTrust.pinsFor("release-assets.githubusercontent.com"))
        assertTrue(PinningTrust.spki(root("USERTrust ECC Certification Authority")) in PinningTrust.pinsFor("github.com"))
        assertTrue(PinningTrust.spki(root("ISRG Root X1")) !in PinningTrust.pinsFor("huggingface.co"))
        assertEquals(emptySet(), PinningTrust.pinsFor("evil.example"))
    }

    @Test
    fun sha256OfFile() {
        val f = Files.createTempFile("h", ".txt").toFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" to 3L, DesktopKapi.sha256(f))
    }

    @Test
    fun webQueriesAreOnlyRicksWikipediaSearches() {
        val kapi = DesktopKapi(Files.createTempDirectory("kapi").toFile())
        // Refused before any connection: other hosts, other API actions, plain http.
        for (url in listOf(
            "https://evil.example/w/api.php?action=query",
            "https://tr.wikipedia.org/w/api.php?action=edit&title=X",
            io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.searchUrl("kedi").replace("https", "http"),
        )) {
            assertIs<io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed>(kapi.query(url), url)
        }
        assertEquals(
            setOf("tr.wikipedia.org"),
            setOf("tr.wikipedia.org").filter { PinningTrust.pinsFor(it).isNotEmpty() }.toSet(),
            "Wikipedia has pins",
        )
    }

    @Test
    fun kapiQueryOutputIsParsed() {
        val body = """{"query":{"pages":[]}}"""
        val ok = io.github.ahmetdurmus1218.jarvis.desktop.kapi.KapiProcessDownloader.parseQuery(
            listOf("Picked up JAVA_TOOL_OPTIONS", "TEXT tr.wikipedia.org " + java.util.Base64.getEncoder().encodeToString(body.toByteArray())),
        )
        assertEquals(io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Ok(body, "tr.wikipedia.org", body.length.toLong()), ok)
        val fail = io.github.ahmetdurmus1218.jarvis.desktop.kapi.KapiProcessDownloader.parseQuery(listOf("FAIL 0 HTTP 503"))
        assertEquals(io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed("HTTP 503"), fail)
        assertIs<io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed>(io.github.ahmetdurmus1218.jarvis.desktop.kapi.KapiProcessDownloader.parseQuery(emptyList()))
    }

    /** The real tr.wikipedia.org through rick-kapi's pinning (skipped only without any network). */
    @Test
    fun liveWikipediaSearchThroughThePins() {
        val url = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.searchUrl("Atatürk")
        when (val r = DesktopKapi(Files.createTempDirectory("kapi").toFile()).query(url)) {
            is io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Ok -> {
                val pages = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.parse(r.body)
                assertTrue(pages.any { "Atatürk" in it.title }, r.body.take(300))
                println("live Wikipedia (PC): ${r.bytes} bytes, ${pages.first().title}")
            }
            is io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed -> {
                val offline = listOf("ConnectException", "UnknownHost", "timed out", "HttpTimeout", "Network is unreachable", "Tunnel failed").any { it in r.message }
                if (!offline) error("live Wikipedia search failed: ${r.message}")
                println("live Wikipedia test skipped: ${r.message}")
            }
        }
    }

    @Test
    fun liveNewsThroughThePins() {
        when (val r = DesktopKapi(Files.createTempDirectory("kapi").toFile()).query(io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews.URL)) {
            is io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Ok -> {
                val items = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews.parse(r.body)
                assertTrue(items.size >= 3, r.body.take(300))
                println("live news (PC): ${r.bytes} bytes, ${items.first().title}")
            }
            is io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed -> {
                val offline = listOf("ConnectException", "UnknownHost", "timed out", "HttpTimeout", "Network is unreachable", "Tunnel failed").any { it in r.message }
                if (!offline) error("live news failed: ${r.message}")
                println("live news test skipped: ${r.message}")
            }
        }
        val refused = DesktopKapi(Files.createTempDirectory("kapi").toFile()).query("https://feeds.bbci.co.uk/news/rss.xml")
        assertTrue(refused is io.github.ahmetdurmus1218.jarvis.ui.WebFetch.Failed)
    }
}

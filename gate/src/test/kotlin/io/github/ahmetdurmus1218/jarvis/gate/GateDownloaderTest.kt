package io.github.ahmetdurmus1218.jarvis.gate

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelLock
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.net.DownloadPolicy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the real gate download logic against scripted HTTP responses (no network). */
class GateDownloaderTest {
    private val body = "hello world".toByteArray()
    private val sha = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
    private val item = LockedItem(
        "m", "llm", listOf("android"), "M", "g", "https://huggingface.co/org/repo/resolve/abc/m.gguf",
        "huggingface.co", "m.gguf", body.size.toLong(), sha, "abc", "hf:org/repo@abc/m.gguf",
    )
    private val registry = ModelRegistry(ModelLock(1, items = listOf(item)))
    private val seen = mutableListOf<String>()

    private fun client(handler: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(Interceptor { chain -> chain.request().also { seen += it.url.toString() }.let(handler) })
        .build()

    private fun respond(req: Request, code: Int, bytes: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap()): Response =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(bytes.toResponseBody(null))
            .build()

    private fun target(prefill: ByteArray = ByteArray(0)): Pair<File, RandomAccessFile> {
        val f = File.createTempFile("gate", ".part").apply { deleteOnExit(); writeBytes(prefill) }
        return f to RandomAccessFile(f, "rw")
    }

    private fun request() = GateRequest(item.id, item.url, item.sha256, item.size, item.fileName)

    @Test
    fun onlyLockedItemsAreAccepted() {
        val gate = GateDownloader(registry, client { error("no network expected") })
        assertEquals(item, gate.validate(request()))
        assertFailsWith<GateException> { gate.validate(request().copy(itemId = "other")) }
        assertFailsWith<GateException> { gate.validate(request().copy(url = "https://huggingface.co/evil/m.gguf")) }
        assertFailsWith<GateException> { gate.validate(request().copy(sha256 = "0".repeat(64))) }
        assertFailsWith<GateException> { gate.validate(request().copy(size = 999)) }
        val apk = item.copy(id = "apk", fileName = "evil.apk", url = "https://huggingface.co/x/evil.apk")
        val withApk = GateDownloader(ModelRegistry(ModelLock(1, items = listOf(apk))), client { error("no network expected") })
        assertFailsWith<GateException> { withApk.validate(GateRequest(apk.id, apk.url, apk.sha256, apk.size, apk.fileName)) }
    }

    @Test
    fun followsAllowedRedirectAndVerifiesHash() {
        val gate = GateDownloader(registry, client { req ->
            when (req.url.host) {
                "huggingface.co" -> respond(req, 302, headers = mapOf("Location" to "https://us.aws.cdn.hf.co/xet-bridge-us/abc?sig=1"))
                "us.aws.cdn.hf.co" -> respond(req, 200, body)
                else -> error("unexpected ${req.url}")
            }
        })
        val (file, raf) = target()
        val result = raf.use { gate.download(item, it.fd, AtomicBoolean(false)) { _, _ -> } }
        assertEquals(GateResult(body.size.toLong(), "us.aws.cdn.hf.co", sha), result)
        assertContentEquals(body, file.readBytes())
    }

    @Test
    fun redirectOutsideTheAllowlistIsRefused() {
        val gate = GateDownloader(registry, client { req ->
            if (req.url.host == "huggingface.co") respond(req, 302, headers = mapOf("Location" to "https://evil.example/m.gguf"))
            else error("must never contact ${req.url}")
        })
        val (_, raf) = target()
        val e = assertFailsWith<GateException> { raf.use { gate.download(item, it.fd, AtomicBoolean(false)) { _, _ -> } } }
        assertTrue(e.message!!.contains("İzin listesinde"), e.message)
        assertEquals(listOf(item.url), seen)
    }

    @Test
    fun redirectToPlainHttpIsRefused() {
        val gate = GateDownloader(registry, client { req -> respond(req, 302, headers = mapOf("Location" to "http://us.aws.cdn.hf.co/x")) })
        val (_, raf) = target()
        val e = assertFailsWith<GateException> { raf.use { gate.download(item, it.fd, AtomicBoolean(false)) { _, _ -> } } }
        assertTrue(e.message!!.contains("HTTPS"), e.message)
    }

    @Test
    fun resumesWithRangeRequest() {
        val gate = GateDownloader(registry, client { req ->
            assertEquals("bytes=5-", req.header("Range"))
            respond(req, 206, body.copyOfRange(5, body.size), mapOf("Content-Range" to "bytes 5-10/11"))
        })
        val (file, raf) = target(body.copyOfRange(0, 5))
        raf.use { gate.download(item, it.fd, AtomicBoolean(false)) { _, _ -> } }
        assertContentEquals(body, file.readBytes())
    }

    @Test
    fun serverIgnoringRangeRestartsFromScratch() {
        val gate = GateDownloader(registry, client { req -> respond(req, 200, body) })
        val (file, raf) = target("HELLO".toByteArray())
        raf.use { gate.download(item, it.fd, AtomicBoolean(false)) { _, _ -> } }
        assertContentEquals(body, file.readBytes())
    }

    @Test
    fun oversizedResponseIsRejected() {
        val gate = GateDownloader(registry, client { req -> respond(req, 200, ByteArray(500)) })
        val (_, raf) = target()
        val e = assertFailsWith<GateException> { raf.use { gate.download(item, it.fd, AtomicBoolean(false)) { _, _ -> } } }
        assertTrue(e.message!!.contains("Boyut"), e.message)
    }

    @Test
    fun hashMismatchWipesTheFile() {
        val gate = GateDownloader(registry, client { req -> respond(req, 200, "hello WORLD".toByteArray()) })
        val (file, raf) = target()
        val e = assertFailsWith<GateException> { raf.use { gate.download(item, it.fd, AtomicBoolean(false)) { _, _ -> } } }
        assertTrue(e.message!!.contains("SHA-256"), e.message)
        assertEquals(0, file.length())
    }

    @Test
    fun cancellationStopsTheTransfer() {
        val gate = GateDownloader(registry, client { req -> respond(req, 200, body) })
        val (_, raf) = target()
        val e = assertFailsWith<GateException> { raf.use { gate.download(item, it.fd, AtomicBoolean(true)) { _, _ -> } } }
        assertTrue(e.message!!.contains("İptal"), e.message)
    }

    // ---- certificate pins checked against the real root certificates in the JDK trust store ----

    private val roots: List<X509Certificate> by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first().acceptedIssuers.toList()
    }

    private fun root(cn: String): X509Certificate =
        roots.firstOrNull { it.subjectX500Principal.name.contains("CN=$cn,") || it.subjectX500Principal.name.endsWith("CN=$cn") }
            ?: error("root $cn not in trust store")

    @Test
    fun pinsMatchTheRealCertificateAuthorities() {
        val amazon = root("Amazon Root CA 1")
        val isrg = root("ISRG Root X1")
        val usertrust = root("USERTrust ECC Certification Authority")
        Pins.pinner.check("huggingface.co", listOf(amazon))
        Pins.pinner.check("cas-bridge.xethub.hf.co", listOf(amazon))
        Pins.pinner.check("download.kiwix.org", listOf(isrg))
        Pins.pinner.check("mirror.download.kiwix.org", listOf(isrg))
        Pins.pinner.check("release-assets.githubusercontent.com", listOf(isrg))
        Pins.pinner.check("github.com", listOf(usertrust))
        Pins.pinner.check("storage.googleapis.com", listOf(root("GTS Root R1")))
        assertFailsWith<SSLPeerUnverifiedException> { Pins.pinner.check("storage.googleapis.com", listOf(amazon)) }
        assertFailsWith<SSLPeerUnverifiedException> { Pins.pinner.check("huggingface.co", listOf(isrg)) }
        assertFailsWith<SSLPeerUnverifiedException> { Pins.pinner.check("github.com", listOf(amazon)) }
        // Wikipedia (approved web searches): DigiCert, GlobalSign and Let's Encrypt chains.
        Pins.pinner.check("tr.wikipedia.org", listOf(root("DigiCert Global Root G2")))
        Pins.pinner.check("tr.wikipedia.org", listOf(root("GlobalSign")))
        Pins.pinner.check("tr.wikipedia.org", listOf(isrg))
        Pins.pinner.check("tr.wikipedia.org", listOf(root("DigiCert TLS ECC P384 Root G5")))
        assertFailsWith<SSLPeerUnverifiedException> { Pins.pinner.check("tr.wikipedia.org", listOf(amazon)) }
    }

    @Test
    fun webQueriesOtherThanRicksWikipediaSearchAreRefused() {
        val q = GateQuery(client { req -> respond(req, 200, "{}".toByteArray()) })
        for (url in listOf(
            "https://tr.wikipedia.org/w/api.php?action=edit&title=X",
            "https://huggingface.co/api/models",
            io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.searchUrl("kedi").replace("https://", "http://"),
        )) {
            val e = assertFailsWith<GateException> { q.fetch(url) }
            assertTrue(e.message!!.contains("reddedildi"), e.message)
        }
        assertTrue(seen.isEmpty(), "refused before any request")
    }

    @Test
    fun aWikipediaSearchReturnsJsonWithinTheLimit() {
        val url = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.searchUrl("Çanakkale")
        val json = mapOf("Content-Type" to "application/json; charset=utf-8")
        val ok = GateQuery(client { req -> respond(req, 200, "{\"query\":{}}".toByteArray(), json) }).fetch(url)
        assertEquals("{\"query\":{}}", ok.body)
        assertEquals("tr.wikipedia.org", ok.host)
        assertTrue(seen.single().contains("gsrsearch=%C3%87anakkale"), seen.single())
        assertFailsWith<GateException> { GateQuery(client { req -> respond(req, 200, "<html>".toByteArray(), mapOf("Content-Type" to "text/html")) }).fetch(url) }
        val big = ByteArray(io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.MAX_BYTES + 10) { 'a'.code.toByte() }
        assertFailsWith<GateException> { GateQuery(client { req -> respond(req, 200, big, json) }).fetch(url) }
        assertFailsWith<GateException> { GateQuery(client { req -> respond(req, 302, ByteArray(0), mapOf("Location" to "https://evil.example/")) }).fetch(url) }
    }

    @Test
    fun everyAllowedHostIsPinned() {
        val samples = DownloadPolicy.ALLOWED_HOSTS.map { if (it.startsWith("*.")) "cdn.sub" + it.substring(1) else it }
        for (host in samples) {
            assertTrue(DownloadPolicy.hostAllowed(host), host)
            assertTrue(Pins.pinner.findMatchingPins(host).isNotEmpty(), "no pins for $host")
        }
    }

    /**
     * Against the real tr.wikipedia.org (CI machines have internet): the pins accept Wikimedia's
     * actual chain and the API answers in the format Rick reads. Skipped only when there is no
     * network at all; a pinning or format problem fails.
     */
    @Test
    fun liveWikipediaSearchThroughThePins() {
        val url = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.searchUrl("Atatürk")
        val r = try {
            GateQuery().fetch(url)
        } catch (e: GateException) {
            throw e
        } catch (e: java.io.IOException) {
            if (e is SSLPeerUnverifiedException || e is javax.net.ssl.SSLHandshakeException) throw e
            println("live Wikipedia test skipped: ${e.javaClass.simpleName} ${e.message}")
            return
        }
        val pages = io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia.parse(r.body)
        assertTrue(pages.isNotEmpty(), r.body.take(300))
        assertTrue(pages.any { "Atatürk" in it.title }, pages.map { it.title }.toString())
        println("live Wikipedia: ${r.bytes} bytes, first: ${pages.first().title}: ${pages.first().text.take(80)}")
    }
}

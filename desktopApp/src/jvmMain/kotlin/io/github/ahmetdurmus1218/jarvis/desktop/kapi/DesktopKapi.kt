package io.github.ahmetdurmus1218.jarvis.desktop.kapi

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.net.DownloadPolicy
import io.github.ahmetdurmus1218.jarvis.ui.DownloadOutcome
import io.github.ahmetdurmus1218.jarvis.ui.DownloadProgress
import io.github.ahmetdurmus1218.jarvis.ui.DownloadState
import io.github.ahmetdurmus1218.jarvis.ui.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import java.net.Socket

/**
 * Desktop counterpart of the Android gate ("jarvis-kapi"). Same rules: locked items only, HTTPS and
 * host allowlist on every redirect hop, certificate pinning, extension allowlist, exact size limit,
 * resumable Range requests, SHA-256 at the end.
 *
 * v0.1 runs it in-process; v0.5 moves it into the separate `jarvis-kapi` launcher that is the only
 * executable allowed through the Windows firewall (see docs/DECISIONS.md).
 */
class DesktopKapi(private val partialDir: File) : Downloader {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Ready("jarvis-kapi (yerleşik)"))
    override val state: StateFlow<DownloadState> = _state
    private val cancelled = AtomicBoolean(false)

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .sslContext(PinningTrust.sslContext())
            .build()
    }

    override fun refresh() {}

    override fun cancel() = cancelled.set(true)

    fun partialFile(item: LockedItem) = File(partialDir, item.sha256 + ".part")

    override suspend fun download(item: LockedItem, onProgress: (DownloadProgress) -> Unit): DownloadOutcome = withContext(Dispatchers.IO) {
        cancelled.set(false)
        when (val v = DownloadPolicy.check(item, Platform.DESKTOP)) {
            is DownloadPolicy.Verdict.Denied -> return@withContext DownloadOutcome.Failed(v.reason)
            DownloadPolicy.Verdict.Allowed -> {}
        }
        partialDir.mkdirs()
        val dest = partialFile(item)
        try {
            var existing = if (dest.length() > item.size) { dest.delete(); 0L } else dest.length()
            var host = item.host
            if (existing < item.size) {
                var url = item.url
                var response: HttpResponse<java.io.InputStream>? = null
                for (hop in 0..DownloadPolicy.MAX_REDIRECTS) {
                    when (val v = DownloadPolicy.checkUrl(url)) {
                        is DownloadPolicy.Verdict.Denied -> return@withContext DownloadOutcome.Failed(v.reason, existing)
                        DownloadPolicy.Verdict.Allowed -> {}
                    }
                    val rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).header("User-Agent", "JarvisKapi/1").GET()
                    if (existing > 0) rb.header("Range", "bytes=$existing-")
                    val r = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream())
                    if (r.statusCode() in setOf(301, 302, 303, 307, 308)) {
                        r.body().close()
                        val loc = r.headers().firstValue("location").orElse(null) ?: return@withContext DownloadOutcome.Failed("Yönlendirme adresi yok")
                        url = URI.create(url).resolve(loc).toString()
                        continue
                    }
                    response = r
                    break
                }
                val r = response ?: return@withContext DownloadOutcome.Failed("Çok fazla yönlendirme")
                host = r.uri().host
                when (r.statusCode()) {
                    200 -> if (existing > 0) { dest.delete(); existing = 0 }
                    206 -> {}
                    else -> { r.body().close(); return@withContext DownloadOutcome.Failed("HTTP ${r.statusCode()}", existing) }
                }
                RandomAccessFile(dest, "rw").use { raf ->
                    raf.seek(existing)
                    val buf = ByteArray(1 shl 20)
                    var total = existing
                    var last = 0L
                    r.body().use { input ->
                        while (true) {
                            if (cancelled.get()) return@withContext DownloadOutcome.Failed("İptal edildi", total)
                            val n = input.read(buf)
                            if (n < 0) break
                            if (total + n > item.size) return@withContext DownloadOutcome.Failed("Sunucu beklenenden fazla veri gönderdi", total)
                            raf.write(buf, 0, n)
                            total += n
                            val now = System.currentTimeMillis()
                            if (now - last > 300) { last = now; onProgress(DownloadProgress(total, item.size)) }
                        }
                    }
                    if (total != item.size) return@withContext DownloadOutcome.Failed("Eksik indirme; tekrar denenince kaldığı yerden sürer", total)
                }
            }
            onProgress(DownloadProgress(item.size, item.size))
            val (sha, size) = sha256(dest)
            DownloadOutcome.Done(dest.absolutePath, size, sha, host)
        } catch (e: Exception) {
            DownloadOutcome.Failed(e.message ?: e.javaClass.simpleName, dest.length())
        }
    }

    companion object {
        fun sha256(file: File): Pair<String, Long> = file.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 20)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                total += n
            }
            md.digest().joinToString("") { "%02x".format(it) } to total
        }
    }
}

/**
 * Validates normally against the JDK trust store, then requires that the chain (including the
 * trust anchor that signed it) contains a pinned CA key for the host. Pins match gate/Pins.kt.
 */
internal object PinningTrust {
    private val AMAZON = setOf(
        "++MBgDH5WGvL9Bcn5Be30cRcL0f5O+NyoXuWtQdX1aI=", "f0KW/FtqTjs108NpYj42SrGvOB2PpxIVM8nWxjPqJGE=",
        "NqvDJlas/GRcYbcWE8S/IceH9cq77kg0jVhZeAPXq8k=", "9+ze1cZgR9KO1kZrVDxA4HQ6voHRCSVNz4RdTCx4U8U=",
        "DxH4tt40L+eduF6szpY6TONlxhZhBd+pJ9wbHlQ2fuw=",
    )
    private val LETS_ENCRYPT = setOf(
        "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=", "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
        "fk6IOKit1ild5647BH06ujSIq5XbCgqlbYl6ANhhi88=", "LoMHBotttiDko50Gi13uXW71eIy7LAttI+rYT8wXF4w=",
        "nWN7PSep5XDQdge5zK24CnCRXHr3KvzhKEGxsdqCX9E=",
    )
    private val SECTIGO = setOf(
        "sLVjNUaFYfW7n6EtgBeEpjOlcnBdNPMrZDRF36iwBdE=", "Douxi77vs4G+Ib/BogbTFymEYq0QSFXwSgVCaZcI09Q=",
        "ZSagvDzjltLkewXEBuDxIzpW/dpVw1Juvvmd0hhkzdY=", "ICGRfpgmOUXIWcQ/HXPLQTkFPEFPoDyjvH7ohhQpjzs=",
        "x4QzPSC810K5/cMjb05Qm4k3Bw5zBn4lTdO/nEW/Td4=",
    )

    fun pinsFor(host: String): Set<String> {
        val h = host.lowercase()
        return when {
            h == "huggingface.co" || h.endsWith(".huggingface.co") || h == "hf.co" || h.endsWith(".hf.co") -> AMAZON
            h == "download.kiwix.org" || h.endsWith(".download.kiwix.org") -> LETS_ENCRYPT
            h == "github.com" -> SECTIGO
            h == "objects.githubusercontent.com" || h == "release-assets.githubusercontent.com" -> LETS_ENCRYPT
            else -> emptySet()
        }
    }

    fun spki(cert: X509Certificate): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

    fun sslContext(): SSLContext {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val base = tmf.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
        val tm = object : X509ExtendedTrustManager() {
            private fun pinCheck(chain: Array<out X509Certificate>, host: String?) {
                val pins = pinsFor(host ?: throw CertificateException("Ana bilgisayar adı yok"))
                if (pins.isEmpty()) throw CertificateException("İzin listesinde olmayan alan adı: $host")
                val anchors = base.acceptedIssuers.filter { a -> chain.any { it.issuerX500Principal == a.subjectX500Principal } }
                val keys = (chain.toList() + anchors).map(::spki)
                if (keys.none { it in pins }) throw CertificateException("Sertifika sabitleme başarısız: $host")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) {
                base.checkServerTrusted(chain, authType, engine)
                pinCheck(chain, engine.peerHost)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) {
                base.checkServerTrusted(chain, authType, socket)
                pinCheck(chain, (socket as? javax.net.ssl.SSLSocket)?.handshakeSession?.peerHost)
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
                throw CertificateException("Ana bilgisayar adı olmadan doğrulama yapılmaz")
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine) = throw CertificateException()
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket) = throw CertificateException()
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException()
            override fun getAcceptedIssuers(): Array<X509Certificate> = base.acceptedIssuers
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    }
}

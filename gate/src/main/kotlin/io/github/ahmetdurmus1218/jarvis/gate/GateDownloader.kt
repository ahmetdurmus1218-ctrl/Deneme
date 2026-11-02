package io.github.ahmetdurmus1218.jarvis.gate

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.net.DownloadPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GateException(message: String, val bytes: Long = 0) : IOException(message)

data class GateRequest(
    val itemId: String,
    val url: String,
    val sha256: String,
    val size: Long,
    val fileName: String,
)

data class GateResult(val bytes: Long, val host: String, val sha256: String)

/**
 * Downloads one locked file into a file descriptor owned by Kale.
 * Rules (section 3.2): only items from the built-in lock, HTTPS + host allowlist checked on every
 * redirect hop, certificate pinning, extension allowlist, exact size limit, resumable (Range),
 * SHA-256 verified at the end (Kale verifies again on its side).
 */
class GateDownloader(
    private val registry: ModelRegistry = ModelRegistry.bundled,
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .certificatePinner(Pins.pinner)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Returns the locked item the request refers to, or throws if anything does not match. */
    fun validate(req: GateRequest): LockedItem {
        val item = registry.byId(req.itemId) ?: throw GateException("Kilit dosyasında olmayan öğe reddedildi")
        if (item.url != req.url || !item.sha256.equals(req.sha256, true) || item.size != req.size || item.fileName != req.fileName) {
            throw GateException("İstek kilit dosyasıyla eşleşmiyor")
        }
        when (val v = DownloadPolicy.check(item, Platform.ANDROID)) {
            is DownloadPolicy.Verdict.Denied -> throw GateException(v.reason)
            DownloadPolicy.Verdict.Allowed -> {}
        }
        return item
    }

    fun download(item: LockedItem, fd: FileDescriptor, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit): GateResult {
        val out = FileOutputStream(fd).channel
        var existing = out.size()
        if (existing > item.size) {
            out.truncate(0)
            existing = 0
        }
        var host = item.host
        if (existing < item.size) {
            host = fetch(item, out, existing, cancelled, progress)
        }
        progress(item.size, item.size)
        val sha = sha256(fd, item.size)
        if (!sha.equals(item.sha256, ignoreCase = true)) {
            out.truncate(0)
            throw GateException("SHA-256 uyuşmadı, dosya silindi", item.size)
        }
        out.force(true)
        return GateResult(item.size, host, sha)
    }

    private fun fetch(item: LockedItem, out: java.nio.channels.FileChannel, start: Long, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit): String {
        var existing = start
        var url = item.url
        var response: Response? = null
        try {
            for (hop in 0..DownloadPolicy.MAX_REDIRECTS) {
                when (val v = DownloadPolicy.checkUrl(url)) {
                    is DownloadPolicy.Verdict.Denied -> throw GateException(v.reason, existing)
                    DownloadPolicy.Verdict.Allowed -> {}
                }
                // Check again with OkHttp's own parser, so a URL that two parsers read differently
                // can never reach a host outside the allowlist.
                val httpUrl = url.toHttpUrlOrNull() ?: throw GateException("Geçersiz adres", existing)
                if (!httpUrl.isHttps || !DownloadPolicy.hostAllowed(httpUrl.host)) {
                    throw GateException("İzin listesinde olmayan alan adı: ${httpUrl.host}", existing)
                }
                val rb = Request.Builder().url(httpUrl).header("User-Agent", "JarvisKapi/1").header("Accept-Encoding", "identity")
                if (existing > 0) rb.header("Range", "bytes=$existing-")
                val r = client.newCall(rb.build()).execute()
                if (r.code in setOf(301, 302, 303, 307, 308)) {
                    val loc = r.header("Location") ?: throw GateException("Yönlendirme adresi yok", existing)
                    val next = r.request.url.resolve(loc)?.toString() ?: throw GateException("Geçersiz yönlendirme", existing)
                    r.close()
                    url = next
                    if (hop == DownloadPolicy.MAX_REDIRECTS) throw GateException("Çok fazla yönlendirme", existing)
                    continue
                }
                response = r
                break
            }
            val r = response ?: throw GateException("Yanıt alınamadı", existing)
            when (r.code) {
                200 -> if (existing > 0) { out.truncate(0); existing = 0 }
                206 -> {
                    val startOk = r.header("Content-Range")?.let { Regex("bytes (\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() } == existing
                    if (!startOk) throw GateException("Sunucu devam ettirmeyi desteklemedi; tekrar deneyin", existing)
                }
                416 -> if (existing == item.size) return r.request.url.host else { out.truncate(0); throw GateException("Kısmi dosya geçersizdi, tekrar deneyin", 0) }
                else -> throw GateException("HTTP ${r.code}", existing)
            }
            val body = r.body
            val announced = body.contentLength()
            if (announced >= 0 && existing + announced > item.size) throw GateException("Boyut sınırı aşıldı", existing)
            out.position(existing)
            val buf = ByteArray(1 shl 20)
            var total = existing
            var lastReport = 0L
            body.byteStream().use { input ->
                while (true) {
                    if (cancelled.get()) throw GateException("İptal edildi", total)
                    val n = input.read(buf)
                    if (n < 0) break
                    if (total + n > item.size) {
                        throw GateException("Sunucu beklenenden fazla veri gönderdi", total)
                    }
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    while (bb.hasRemaining()) out.write(bb)
                    total += n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 400) {
                        lastReport = now
                        progress(total, item.size)
                    }
                }
            }
            if (total != item.size) throw GateException("Eksik indirme (${total}/${item.size}); tekrar denenince kaldığı yerden sürer", total)
            return r.request.url.host
        } finally {
            response?.close()
        }
    }

    private fun sha256(fd: FileDescriptor, size: Long): String {
        val ch = FileInputStream(fd).channel
        val md = MessageDigest.getInstance("SHA-256")
        val bb = ByteBuffer.allocate(1 shl 20)
        var pos = 0L
        while (pos < size) {
            bb.clear()
            val n = ch.read(bb, pos)
            if (n <= 0) break
            bb.flip()
            md.update(bb)
            pos += n
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

package io.github.ahmetdurmus1218.jarvis.gate

import io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class QueryResult(val body: String, val host: String, val bytes: Long)

/**
 * One approved Wikipedia search for Rick (since 1.1). Only addresses built by Rick's
 * OnlineWikipedia pass (tr.wikipedia.org's search API, nothing else); HTTPS with pinning, no
 * redirects, JSON only, at most OnlineWikipedia.MAX_BYTES.
 */
class GateQuery(private val client: OkHttpClient = GateDownloader.defaultClient()) {
    fun fetch(url: String): QueryResult {
        if (!OnlineWikipedia.allowed(url)) throw GateException("İzin verilmeyen web isteği reddedildi")
        val httpUrl = url.toHttpUrlOrNull() ?: throw GateException("Geçersiz adres")
        if (!httpUrl.isHttps || httpUrl.host != OnlineWikipedia.HOST) throw GateException("İzin listesinde olmayan alan adı: ${httpUrl.host}")
        val request = Request.Builder().url(httpUrl)
            .header("User-Agent", OnlineWikipedia.USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { r ->
            if (r.code != 200) throw GateException("HTTP ${r.code}")
            val type = r.header("Content-Type").orEmpty()
            if (!type.startsWith("application/json")) throw GateException("Beklenmeyen yanıt türü: $type")
            val bytes = r.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > OnlineWikipedia.MAX_BYTES) throw GateException("Yanıt boyut sınırını aştı")
                }
                out.toByteArray()
            }
            return QueryResult(bytes.decodeToString(), httpUrl.host, bytes.size.toLong())
        }
    }
}

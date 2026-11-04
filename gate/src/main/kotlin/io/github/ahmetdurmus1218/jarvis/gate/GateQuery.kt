package io.github.ahmetdurmus1218.jarvis.gate

import io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews
import io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia
import io.github.ahmetdurmus1218.jarvis.core.assistant.WebSources
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class QueryResult(val body: String, val host: String, val bytes: Long)

/**
 * One approved read for Rick: a Wikipedia search (since 1.1) or the day's headlines (since 1.3).
 * Only the addresses Rick builds pass (WebSources: tr.wikipedia.org's search API and BBC Türkçe's
 * RSS feed, nothing else); HTTPS with pinning, no redirects, JSON / XML only, size-capped.
 */
class GateQuery(private val client: OkHttpClient = GateDownloader.defaultClient()) {
    fun fetch(url: String): QueryResult {
        if (!WebSources.allowed(url)) throw GateException("İzin verilmeyen web isteği reddedildi")
        val httpUrl = url.toHttpUrlOrNull() ?: throw GateException("Geçersiz adres")
        if (!httpUrl.isHttps || httpUrl.host != WebSources.hostOf(url)) throw GateException("İzin listesinde olmayan alan adı: ${httpUrl.host}")
        val news = OnlineNews.allowed(url)
        val request = Request.Builder().url(httpUrl)
            .header("User-Agent", OnlineWikipedia.USER_AGENT)
            .header("Accept", if (news) "application/rss+xml, application/xml, text/xml" else "application/json")
            .build()
        val max = WebSources.maxBytes(url)
        client.newCall(request).execute().use { r ->
            if (r.code != 200) throw GateException("HTTP ${r.code}")
            val type = r.header("Content-Type").orEmpty()
            val typeOk = if (news) Regex("^(?:application/(?:rss\\+)?xml|text/xml)").containsMatchIn(type) else type.startsWith("application/json")
            if (!typeOk) throw GateException("Beklenmeyen yanıt türü: $type")
            val bytes = r.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw GateException("Yanıt boyut sınırını aştı")
                }
                out.toByteArray()
            }
            return QueryResult(bytes.decodeToString(), httpUrl.host, bytes.size.toLong())
        }
    }
}

package io.github.ahmetdurmus1218.jarvis.core.assistant

import io.github.ahmetdurmus1218.jarvis.core.AssistantName
import io.github.ahmetdurmus1218.jarvis.core.text.HtmlText
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish

/** A headline from the news feed, verbatim. */
data class NewsItem(val title: String, val summary: String, val url: String)

/**
 * "Günün haberleri": the headlines of one public Turkish news feed (BBC Türkçe's RSS), read after
 * the user's approval through the gate. Nothing about the user is sent: the address is fixed, there
 * is no search text. Only builds / checks the address and reads the answer; the request itself is
 * made by the gate (Kapı / rick-kapi), never by this module.
 */
object OnlineNews {
    const val HOST = "feeds.bbci.co.uk"
    const val URL = "https://$HOST/turkce/rss.xml"
    const val SOURCE = "BBC Türkçe"
    const val MAX_BYTES = 600_000

    /** How many headlines Rick reads aloud. */
    const val READ = 5

    private val TRIGGER = Regex(
        "^(?:hey )?(?:${AssistantName.WAKE})?[ ,]*(?:bana )?(?:bugunun |gunun |son dakika |son |guncel |bugunku |sabahin )?" +
            "(?:haber\\w*|manset\\w*|gundem\\w*|son dakika\\w*)(?: (?:neler|ne|var mi|ne var|nedir|oku|okur musun|soyle|soyler misin|anlat|ver|getir|bak|neymis))*" +
            "(?: (?:lutfen|rick|bakalim))*$|^(?:dunyada|turkiyede|bugun) (?:neler oluyor|ne oldu|ne var ne yok)$|^neler oluyor$",
    )

    fun matches(text: String): Boolean = TRIGGER.matches(Turkish.normalize(text).replace(Regex("[?.!,]+"), " ").replace(Regex(" +"), " ").trim())

    fun allowed(url: String): Boolean = url == URL

    private val ITEM = Regex("<item\\b[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)

    private fun tag(xml: String, name: String): String? =
        Regex("<$name\\b[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.let(::clean)

    private fun clean(s: String): String {
        val cdata = s.trim().removePrefix("<![CDATA[").removeSuffix("]]>")
        // Feeds escape their HTML; whatever tags remain are dropped, entities decoded.
        return HtmlText.decode(HtmlText.decode(cdata).replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()
    }

    /** Headlines of an RSS answer in the feed's order; empty when the text is not RSS. */
    fun parse(xml: String): List<NewsItem> = ITEM.findAll(xml).mapNotNull { m ->
        val body = m.groupValues[1]
        val title = tag(body, "title")?.takeIf { it.length >= 8 } ?: return@mapNotNull null
        val link = tag(body, "link")?.takeIf { it.startsWith("https://") }.orEmpty()
        NewsItem(title.take(200), tag(body, "description").orEmpty().take(400), link)
    }.distinctBy { it.title }.take(20).toList()
}

/** Every address Rick may ask a gate to read (one approved request each). */
object WebSources {
    fun allowed(url: String): Boolean = OnlineWikipedia.allowed(url) || OnlineNews.allowed(url)

    fun hostOf(url: String): String? = when {
        OnlineWikipedia.allowed(url) -> OnlineWikipedia.HOST
        OnlineNews.allowed(url) -> OnlineNews.HOST
        else -> null
    }

    /** Upper size of the answer for [url]. */
    fun maxBytes(url: String): Int = if (OnlineNews.allowed(url)) OnlineNews.MAX_BYTES else OnlineWikipedia.MAX_BYTES
}

package io.github.ahmetdurmus1218.jarvis.core.text

/**
 * Turns a Wikipedia (Kiwix ZIM) article into plain text for the model: drops head, scripts, styles
 * and citation markers, keeps paragraphs, list items and table rows (infoboxes hold most numbers),
 * and stops at the reference / external-links sections.
 */
object HtmlText {
    private val dropBlocks = listOf("head", "script", "style", "noscript", "math", "svg").map {
        Regex("<$it\\b[^>]*>.*?</$it\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
    private val citation = Regex("<sup\\b[^>]*class=\"[^\"]*reference[^\"]*\"[^>]*>.*?</sup>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val comment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val heading = Regex("<h[1-6]\\b[^>]*>(.*?)</h[1-6]\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val listItem = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val cell = Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE)
    private val block = Regex("</?(p|div|section|br|tr|table|ul|ol|dl|dd|dt|details|summary|blockquote|figure|figcaption|center)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]+>")
    private val bracketRef = Regex("\\[(\\d{1,3}|[a-z]|not \\d+|k \\d+)]")
    private val spaces = Regex("[ \\t\\u00A0]+")
    private val titleTag = Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Section headings after which an article has no prose worth reading. */
    private val stopSections = setOf(
        "references", "notes", "external links", "see also", "further reading", "bibliography", "sources",
        "kaynakça", "kaynaklar", "notlar", "dış bağlantılar", "ayrıca bakınız", "ilave okumalar", "dipnotlar",
    )

    fun title(html: String): String? = titleTag.find(html)?.groupValues?.get(1)?.let { decode(it).trim() }?.ifBlank { null }

    fun toText(html: String): String {
        var s = html
        s = comment.replace(s, " ")
        for (rx in dropBlocks) s = rx.replace(s, " ")
        s = citation.replace(s, "")
        // Headings become their own lines, marked so the stop sections can be found after decoding.
        s = heading.replace(s) { m -> "\n\u0001" + tag.replace(m.groupValues[1], "").replace(Regex("\\s+"), " ").trim() + "\n" }
        s = listItem.replace(s, "\n• ")
        s = cell.replace(s, " | ")
        s = block.replace(s, "\n")
        s = tag.replace(s, "")
        s = decode(s)
        s = bracketRef.replace(s, "")
        val out = StringBuilder()
        for (raw in s.lines()) {
            val isHeading = raw.startsWith('\u0001')
            val line = spaces.replace(raw.removePrefix("\u0001"), " ").trim().trim('|').trim()
            if (line.isEmpty()) continue
            if (isHeading && line.lowercase() in stopSections) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(if (isHeading) "## $line" else line)
        }
        return out.toString()
    }

    private val entity = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–",
        "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»", "deg" to "°", "times" to "×", "minus" to "−",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "middot" to "·", "shy" to "", "zwj" to "", "zwnj" to "",
    )

    fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(::codePoint) ?: m.value
            else -> named[e] ?: m.value
        }
    }

    private fun codePoint(cp: Int): String = when {
        cp < 0 || cp > 0x10FFFF -> ""
        cp < 0x10000 -> cp.toChar().toString()
        else -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
    }
}

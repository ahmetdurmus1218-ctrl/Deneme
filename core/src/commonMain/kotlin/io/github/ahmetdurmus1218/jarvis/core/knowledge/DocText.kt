package io.github.ahmetdurmus1218.jarvis.core.knowledge

/** Text of imported TXT / MD files (PDF text comes from the platform's PDF library). */
object DocText {
    /** UTF-8 when valid (BOM removed); otherwise Windows-1254, the classic Turkish encoding. */
    fun decode(bytes: ByteArray): String {
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            cp1254(bytes)
        }
        return text.removePrefix("﻿")
    }

    fun forFile(fileName: String, bytes: ByteArray): String {
        val text = decode(bytes)
        return if (fileName.lowercase().endsWith(".md") || fileName.lowercase().endsWith(".markdown")) markdown(text) else text
    }

    /** Markdown → plain text: headings, emphasis, code fences, links and images reduced to their words. */
    fun markdown(md: String): String = md.lines().joinToString("\n") { raw ->
        var l = raw
        l = l.replace(Regex("^\\s{0,3}#{1,6}\\s*"), "")          // # Heading
        l = l.replace(Regex("^\\s{0,3}>\\s?"), "")               // > quote
        l = l.replace(Regex("^\\s*([-*+]|\\d+[.)])\\s+"), "• ")  // list items
        l = l.replace(Regex("^\\s*(```|~~~).*$"), "")            // code fences
        l = l.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")  // images
        l = l.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")   // links
        l = l.replace(Regex("(\\*\\*|__|\\*|_|`)(\\S(?:.*?\\S)?)\\1"), "$2")
        l = l.replace(Regex("^\\s*([-*_]\\s*){3,}$"), "")        // horizontal rules
        l
    }

    private val cp1254High = charArrayOf(
        '€', '�', '‚', 'ƒ', '„', '…', '†', '‡', 'ˆ', '‰', 'Š', '‹', 'Œ', '�', '�', '�',
        '�', '‘', '’', '“', '”', '•', '–', '—', '˜', '™', 'š', '›', 'œ', '�', '�', 'Ÿ',
    )

    /** Latin-1 with the Turkish letters at 0xD0 Ğ, 0xDD İ, 0xDE Ş, 0xF0 ğ, 0xFD ı, 0xFE ş. */
    private fun cp1254(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(
                when (v) {
                    in 0x80..0x9F -> cp1254High[v - 0x80]
                    0xD0 -> 'Ğ'
                    0xDD -> 'İ'
                    0xDE -> 'Ş'
                    0xF0 -> 'ğ'
                    0xFD -> 'ı'
                    0xFE -> 'ş'
                    else -> v.toChar()
                },
            )
        }
        return sb.toString()
    }
}

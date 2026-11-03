package io.github.ahmetdurmus1218.jarvis.core.text

/** Turkish-aware text helpers. Pure Kotlin, no platform locale dependency. */
object Turkish {

    /** Lowercases with Turkish dotted/dotless I rules (I → ı, İ → i). */
    fun lowercase(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                'I' -> sb.append('ı')
                'İ' -> sb.append('i')
                else -> sb.append(c.lowercaseChar())
            }
        }
        // "İ" can also arrive as "I" + combining dot above (U+0307).
        return sb.toString().replace("i̇", "i")
    }

    /**
     * Normalizes text for command matching: Turkish lowercase, folds Turkish letters to ASCII
     * (ç→c, ğ→g, ı→i, ö→o, ş→s, ü→u, plus circumflex vowels), turns apostrophes into nothing,
     * other punctuation into spaces and collapses whitespace.
     */
    fun normalize(text: String): String {
        val lower = lowercase(text)
        val sb = StringBuilder(lower.length)
        for (c in lower) {
            when (c) {
                'ç' -> sb.append('c')
                'ğ' -> sb.append('g')
                'ı' -> sb.append('i')
                'ö' -> sb.append('o')
                'ş' -> sb.append('s')
                'ü' -> sb.append('u')
                'â' -> sb.append('a')
                'î' -> sb.append('i')
                'û' -> sb.append('u')
                '\'', '’', '‘', '`', '´' -> {}
                '×', '·' -> sb.append('*')
                '÷' -> sb.append('/')
                '−', '–' -> sb.append('-')
                else -> sb.append(if (c.isLetterOrDigit() || c == ':' || c == '.' || c == ',' || c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '^' || c == '(' || c == ')') c else ' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', ':').trim()
    }

    /**
     * The words of [raw] that [normalizedPart] (a piece of normalize(raw)) came from, in the user's own
     * spelling: "Ahmet'i ara" + "ahmet" → "Ahmet". The last word may be cut where the piece ends.
     */
    fun cutOriginal(raw: String, normalizedPart: String): String {
        fun bare(s: String) = normalize(s).trimEnd(',', '.', ':', ';')
        val words = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val norms = words.map(::bare)
        val target = normalizedPart.split(' ').filter { it.isNotEmpty() }.map { it.trimEnd(',', '.', ':', ';') }
        if (target.isEmpty()) return normalizedPart
        for (i in words.indices) {
            if (i + target.size > words.size) break
            val fits = target.indices.all { k ->
                val w = norms[i + k]
                w == target[k] || (k == target.lastIndex && w.startsWith(target[k]))
            }
            if (!fits) continue
            val out = words.subList(i, i + target.size).toMutableList()
            val lastNorm = norms[i + target.lastIndex]
            val want = target.last()
            if (lastNorm != want) {
                // Keep raw characters until as many normalized letters as wanted were produced.
                val w = out.last()
                var produced = 0
                var cut = 0
                while (cut < w.length && produced < want.length) {
                    produced += normalize(w[cut].toString()).length
                    cut++
                }
                out[out.lastIndex] = w.substring(0, cut)
            }
            return out.joinToString(" ")
        }
        return normalizedPart
    }

    private val MONTHS = listOf(
        "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
        "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık",
    )
    private val DAYS = listOf("Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi", "Pazar")

    /** @param month 1..12 */
    fun monthName(month: Int): String = MONTHS[month - 1]

    /** @param isoDayOfWeek 1 = Monday .. 7 = Sunday */
    fun dayName(isoDayOfWeek: Int): String = DAYS[isoDayOfWeek - 1]
}

/** How a spoken or typed arithmetic expression is shown on a card: "12 carpi 7" → "12 × 7". */
object MathText {
    // Java's \b only knows ASCII letters, so the Turkish spellings are replaced as plain text.
    private val turkish = listOf("çarpı" to "×", "bölü" to "÷", "artı" to "+")
    private val ascii = listOf(
        Regex("\\bcarpi\\b") to "×",
        Regex("\\bbolu\\b") to "÷",
        Regex("\\barti\\b") to "+",
        Regex("\\beksi\\b") to "−",
    )

    fun pretty(expression: String): String {
        var s = expression
        for ((w, sym) in turkish) s = s.replace(w, sym)
        for ((rx, sym) in ascii) s = s.replace(rx, sym)
        s = s.replace("*", " × ").replace("/", " ÷ ")
        return s.replace(Regex("\\s+"), " ").trim()
    }
}

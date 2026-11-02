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
                else -> sb.append(if (c.isLetterOrDigit() || c == ':' || c == '.' || c == ',' || c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '^' || c == '(' || c == ')') c else ' ')
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim().trimEnd('.', ',', ':').trim()
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

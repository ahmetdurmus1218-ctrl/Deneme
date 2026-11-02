package io.github.ahmetdurmus1218.jarvis.core.text

/**
 * Turns Turkish number words in already-normalized text into digits, so spoken commands work:
 * "bes dakikalik" → "5 dakikalik", "yedide" → "7de", "on iki carpi yedi" → "12 carpi 7",
 * "bin dokuz yuz yirmi uc" → "1923". A case suffix on the last word is kept ("dokuzda" → "9da").
 *
 * Only used for routing commands; the text sent to the LLM is never rewritten.
 */
object TurkishNumbers {
    private val UNITS = mapOf(
        "sifir" to 0, "bir" to 1, "iki" to 2, "uc" to 3, "dort" to 4,
        "bes" to 5, "alti" to 6, "yedi" to 7, "sekiz" to 8, "dokuz" to 9,
    )
    private val TENS = mapOf(
        "on" to 10, "yirmi" to 20, "otuz" to 30, "kirk" to 40, "elli" to 50,
        "altmis" to 60, "yetmis" to 70, "seksen" to 80, "doksan" to 90,
    )

    /** Case/derivation suffixes that may follow a number word (after ASCII folding). */
    private val SUFFIXES = listOf("lik", "luk", "den", "dan", "ten", "tan", "de", "da", "te", "ta", "ye", "ya", "yi", "yu", "e", "a", "i", "u")

    /** Words that look like number+suffix but mean something else. */
    private val EXCLUDED = setOf("yuzde", "biri", "birini", "bira", "binde")

    private enum class Kind { UNIT, TEN, HUNDRED, THOUSAND }

    private data class Word(val kind: Kind, val value: Int, val suffix: String)

    private fun bare(word: String): Word? = when {
        word in UNITS -> Word(Kind.UNIT, UNITS.getValue(word), "")
        word in TENS -> Word(Kind.TEN, TENS.getValue(word), "")
        word == "yuz" -> Word(Kind.HUNDRED, 100, "")
        word == "bin" -> Word(Kind.THOUSAND, 1000, "")
        else -> null
    }

    private fun parseWord(token: String): Word? {
        if (token in EXCLUDED) return null
        bare(token)?.let { return it }
        for (s in SUFFIXES) {
            if (token.length > s.length + 1 && token.endsWith(s)) {
                bare(token.dropLast(s.length))?.let { return it.copy(suffix = s) }
            }
        }
        return null
    }

    /** Whether [next] can continue a number whose last word was [prev] ("on" + "iki", "iki" + "yuz"). */
    private fun canFollow(prev: Kind, next: Kind): Boolean = when (prev) {
        Kind.UNIT -> next == Kind.HUNDRED || next == Kind.THOUSAND
        Kind.TEN -> next == Kind.UNIT || next == Kind.THOUSAND
        Kind.HUNDRED -> next == Kind.UNIT || next == Kind.TEN || next == Kind.THOUSAND
        Kind.THOUSAND -> next == Kind.UNIT || next == Kind.TEN || next == Kind.HUNDRED
    }

    fun wordsToDigits(normalized: String): String {
        val tokens = normalized.split(' ')
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val first = parseWord(tokens[i])
            if (first == null) {
                out += tokens[i]
                i++
                continue
            }
            var total = 0
            var current = 0
            var last: Word? = null
            var j = i
            while (j < tokens.size) {
                val w = parseWord(tokens[j]) ?: break
                if (last != null && (last.suffix.isNotEmpty() || !canFollow(last.kind, w.kind))) break
                when (w.kind) {
                    Kind.UNIT, Kind.TEN -> current += w.value
                    Kind.HUNDRED -> current = if (current == 0) 100 else current * 100
                    Kind.THOUSAND -> {
                        total += if (current == 0) 1000 else current * 1000
                        current = 0
                    }
                }
                last = w
                j++
            }
            out += "${total + current}${last?.suffix.orEmpty()}"
            i = j
        }
        return out.joinToString(" ")
    }
}

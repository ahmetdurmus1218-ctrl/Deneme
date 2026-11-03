package io.github.ahmetdurmus1218.jarvis.core.tts

/**
 * Turkish text → the phonemes the Piper voice (tr_TR-dfki-medium) was trained on.
 *
 * Piper voices learn from espeak-ng's IPA. sherpa-onnx 2.0 no longer bundles espeak-ng, so this
 * reproduces espeak-ng's Turkish output with rules (Turkish spelling is almost phonemic):
 * vowel variants by syllable (e: e/ɛ/æ, i: i/ɪ, u: u/ʊ, ü: y/ø, o: o/ɔ, ö: ø/œ), ğ as a long vowel
 * or j, dark l (ɫ) in back-vowel syllables, flapped r (ɾ) before vowels, palatal g (ɟ) in front-vowel
 * words, long double consonants, and stress marked right before the stressed vowel (last syllable,
 * with espeak's common exceptions). Numbers are read out first.
 */
object TurkishPhonemizer {
    private const val VOWELS = "aeıioöuüâîû"
    private val FRONT = setOf('e', 'i', 'ö', 'ü', 'î')
    private const val STRESS = 'ˈ'

    /** Sentences of phonemes; each becomes one row of sherpa-onnx's phoneme_codepoints. */
    fun sentences(text: String): List<String> =
        splitSentences(TurkishNumberWords.spellOut(text)).map(::phonemizeSentence).filter { it.isNotBlank() }

    fun codepoints(text: String): Array<IntArray> =
        sentences(text).map { s -> s.toCodePointArray() }.toTypedArray()

    private fun String.toCodePointArray(): IntArray {
        val out = ArrayList<Int>(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length) {
                out += ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) + 0x10000
                i += 2
            } else {
                out += c.code
                i++
            }
        }
        return out.toIntArray()
    }

    private fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                out += sb.toString()
                sb.clear()
            }
        }
        if (sb.isNotBlank()) out += sb.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Words become phonemes; the punctuation Piper knows is kept, everything else is dropped. */
    fun phonemizeSentence(sentence: String): String {
        val out = StringBuilder()
        var word = StringBuilder()
        fun flushWord() {
            if (word.isNotEmpty()) {
                if (out.isNotEmpty() && !out.endsWith(" ")) out.append(' ')
                out.append(word(word.toString()))
                word = StringBuilder()
            }
        }
        for (c in sentence) {
            when {
                c.isLetter() -> word.append(c)
                c == '\'' || c == '’' -> word.append('\'')
                c in ",.!?;:" -> { flushWord(); out.append(c) }
                else -> flushWord()
            }
        }
        flushWord()
        return out.toString().replace(Regex(" +"), " ").trim()
    }

    fun lower(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                'I' -> 'ı'
                'İ' -> 'i'
                else -> c.lowercaseChar()
            },
        )
    }

    /** Words that espeak does not stress on the last syllable: stressed vowel index (0-based). */
    private val STRESS_EXCEPTIONS: Map<String, Int> = mapOf(
        "ankara" to 0, "izmir" to 0, "istanbul" to 1, "türkiye" to 0, "antalya" to 1, "adana" to 0, "bursa" to 0,
        "konya" to 0, "avrupa" to 1, "amerika" to 1, "almanya" to 1, "fransa" to 0, "ingiltere" to 1, "rusya" to 0,
        "merhaba" to 0, "lütfen" to 0, "sekiz" to 0, "dokuz" to 0, "değil" to 0, "elma" to 0, "gece" to 0, "eğitim" to 1,
        "şimdi" to 0, "nasıl" to 0, "nerede" to 0, "neden" to 0, "niçin" to 0, "hangi" to 0, "kimse" to 0, "yarın" to 0,
        "bazen" to 0, "sonra" to 0, "önce" to 0, "belki" to 0, "ama" to 0, "fakat" to 0, "ancak" to 0, "yani" to 0,
        "çünkü" to 0, "bugün" to 1, "masa" to 0, "anne" to 0, "baba" to 0, "teyze" to 0, "efendim" to 1, "hemen" to 0,
        "tamam" to 1, "hayır" to 1, "evet" to 1, "lokanta" to 1, "radyo" to 0, "okyanus" to 1, "hiçbir" to 0,
        "jarvis" to 1,
    )

    /** Suffixes and particles espeak never stresses (the question particle mI). */
    private val UNSTRESSED = setOf("mı", "mi", "mu", "mü", "mısın", "misin", "musun", "müsün", "mıyım", "miyim", "ki", "de", "da", "ile")

    fun word(raw: String): String {
        // "gâ" keeps its palatal g (rüzgâr → røzɟar); the circumflex itself is otherwise just the vowel.
        val lw = lower(raw).replace("gâ", "ĝa").replace('â', 'a').replace('î', 'i').replace('û', 'u')
            .replace('q', 'k').replace('w', 'v').replace("x", "ks")
        // Case endings after an apostrophe ("Ankara'dır") belong to the word but not to its stress.
        val base = lw.substringBefore('\'')
        val w = lw.replace("'", "")
        if (w.isEmpty()) return ""
        if (w.none { it in VOWELS }) return spell(w)
        val vowelIdx = w.indices.filter { w[it] in VOWELS }
        val stressedVowel = when {
            w in UNSTRESSED -> -1
            base in STRESS_EXCEPTIONS -> STRESS_EXCEPTIONS.getValue(base).coerceAtMost(vowelIdx.lastIndex)
            lw.contains('\'') -> base.count { it in VOWELS }.minus(1).coerceAtLeast(0) // stress stays on the name
            else -> vowelIdx.lastIndex
        }
        return phonemes(w, if (stressedVowel >= 0) vowelIdx[stressedVowel] else -1)
    }

    /** Letters spelled out for vowel-less abbreviations ("TBMM", "km"). */
    private fun spell(w: String): String = w.map { c ->
        when (c) {
            'b' -> "be"; 'c' -> "dʒe"; 'ç' -> "tʃe"; 'd' -> "de"; 'f' -> "fe"; 'g' -> "ɟe"; 'ğ' -> "jumuʃak ɟe"
            'h' -> "he"; 'j' -> "ʒe"; 'k' -> "ka"; 'l' -> "le"; 'm' -> "me"; 'n' -> "ne"; 'p' -> "pe"; 'r' -> "ɾe"
            's' -> "se"; 'ş' -> "ʃe"; 't' -> "te"; 'v' -> "ve"; 'y' -> "je"; 'z' -> "ze"; else -> ""
        }
    }.filter { it.isNotEmpty() }.joinToString(" ").let { if (it.isEmpty()) it else "$it" }

    private fun isVowel(c: Char) = c in VOWELS

    /** Index of the vowel of the syllable that letter [i] belongs to (Turkish syllables: V, CV, VC, CVC, CVCC). */
    private fun syllableVowel(w: String, i: Int): Int {
        if (isVowel(w[i])) return i
        // A consonant directly before a vowel opens that vowel's syllable; otherwise it closes the previous one.
        if (i + 1 < w.length && isVowel(w[i + 1])) return i + 1
        var j = i - 1
        while (j >= 0 && !isVowel(w[j])) j--
        if (j >= 0) return j
        var k = i + 1
        while (k < w.length && !isVowel(w[k])) k++
        return if (k < w.length) k else i
    }

    /** True when the syllable of vowel [v] ends in a consonant (VC, CVC...). */
    private fun closed(w: String, v: Int): Boolean {
        val next = v + 1
        if (next >= w.length) return false
        if (isVowel(w[next])) return false
        if (w[next] == 'ğ') return false
        // One consonant then a vowel: it starts the next syllable. Two or more, or the word ends: closed.
        return !(next + 1 < w.length && isVowel(w[next + 1]))
    }

    private fun closingConsonant(w: String, v: Int): Char? = if (closed(w, v)) w[v + 1] else null

    private fun phonemes(w: String, stressAt: Int): String {
        val out = StringBuilder()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            if (i == stressAt) out.append(STRESS)
            if (isVowel(c)) {
                out.append(vowel(w, i))
                // ğ after the vowel: j after e/i, otherwise a long vowel (and a following identical vowel merges).
                if (i + 1 < w.length && w[i + 1] == 'ğ') {
                    val after = w.getOrNull(i + 2)
                    if (c == 'e' || c == 'i') {
                        out.append('j')
                        i += 2
                        continue
                    }
                    out.append('ː')
                    if (after != null && after == c) {
                        i += 3
                        continue
                    }
                    i += 2
                    continue
                }
                i++
                continue
            }
            // Double consonants are one long consonant.
            if (i + 1 < w.length && w[i + 1] == c && c != 'ğ') {
                out.append(consonant(w, i)).append('ː')
                i += 2
                continue
            }
            out.append(consonant(w, i))
            i++
        }
        return out.toString()
    }

    private fun vowel(w: String, i: Int): String {
        val c = w[i]
        val closedBy = closingConsonant(w, i)
        val final = i == w.lastIndex
        return when (c) {
            'a' -> "a"
            'ı' -> "ɯ"
            'e' -> when {
                closedBy != null && closedBy in "lmn" -> "æ"
                closedBy != null -> "ɛ"
                final -> "ɛ"
                else -> "e"
            }
            'i' -> if (closedBy != null || final) "ɪ" else "i"
            'o' -> if (closedBy != null) "ɔ" else "o"
            'ö' -> if (closedBy != null) "œ" else "ø"
            'u' -> if (closedBy != null || final) "ʊ" else "u"
            'ü' -> if (closedBy != null || final) "ø" else "y"
            else -> c.toString()
        }
    }

    private fun consonant(w: String, i: Int): String {
        val c = w[i]
        return when (c) {
            'c' -> "dʒ"
            'ç' -> "tʃ"
            'ş' -> "ʃ"
            'j' -> "ʒ"
            'y' -> "j"
            'ĝ' -> "ɟ"
            'ğ' -> "" // only when no vowel precedes it (handled with the vowel otherwise)
            'g' -> {
                val nextV = (i + 1 until w.length).firstOrNull { isVowel(w[it]) }?.let { w[it] }
                val prevV = (i - 1 downTo 0).firstOrNull { isVowel(w[it]) }?.let { w[it] }
                if (nextV != null && nextV in FRONT && (prevV == null || prevV in FRONT)) "ɟ" else "ɡ"
            }
            'l' -> if (w[syllableVowel(w, i)] in "aıou") "ɫ" else "l"
            // Flapped between vowels and after consonants; a word-initial or closing r is the full r.
            'r' -> if (i > 0 && i + 1 < w.length && isVowel(w[i + 1])) "ɾ" else "r"
            else -> c.toString()
        }
    }
}

/** Reads numbers out in Turkish ("1923" → "bin dokuz yüz yirmi üç", "3,5" → "üç virgül beş"). */
object TurkishNumberWords {
    private val ones = listOf("", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz")
    private val tens = listOf("", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan")
    private val scales = listOf("", "bin", "milyon", "milyar", "trilyon")

    fun words(n: Long): String {
        if (n == 0L) return "sıfır"
        if (n < 0) return "eksi " + words(-n)
        val groups = mutableListOf<Int>()
        var x = n
        while (x > 0) {
            groups += (x % 1000).toInt()
            x /= 1000
        }
        val parts = mutableListOf<String>()
        for (g in groups.indices.reversed()) {
            val v = groups[g]
            if (v == 0) continue
            val scale = scales.getOrElse(g) { "" }
            // "bin", not "bir bin".
            val head = if (g == 1 && v == 1) "" else hundreds(v)
            parts += listOf(head, scale).filter { it.isNotEmpty() }.joinToString(" ")
        }
        return parts.joinToString(" ")
    }

    private fun hundreds(v: Int): String {
        val h = v / 100
        val t = (v / 10) % 10
        val o = v % 10
        return listOf(
            when (h) { 0 -> ""; 1 -> "yüz"; else -> ones[h] + " yüz" },
            tens[t],
            ones[o],
        ).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private val number = Regex("\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:,\\d+)?")

    /** Every number in [text] in words; Turkish separators: "5.910.320", "8.848,86", "3,5". */
    fun spellOut(text: String): String = number.replace(text.replace(Regex("%\\s?(?=\\d)"), "yüzde ")) { m ->
        val s = m.value
        val intPart = s.substringBefore(',').replace(".", "")
        val frac = s.substringAfter(',', "")
        val n = intPart.toLongOrNull() ?: return@replace s
        val w = words(n)
        if (frac.isEmpty()) w else w + " virgül " + (frac.toLongOrNull()?.let { f -> "0".repeat(frac.takeWhile { it == '0' }.length.coerceAtMost(3)).map { "sıfır" }.plus(words(f)).joinToString(" ") } ?: frac)
    }
}

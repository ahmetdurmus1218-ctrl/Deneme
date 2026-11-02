package io.github.ahmetdurmus1218.jarvis.core.verify

import io.github.ahmetdurmus1218.jarvis.core.scene.Gorsel
import io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip
import io.github.ahmetdurmus1218.jarvis.core.scene.Reply
import io.github.ahmetdurmus1218.jarvis.core.scene.Sahne
import io.github.ahmetdurmus1218.jarvis.core.scene.VerifiedReply
import io.github.ahmetdurmus1218.jarvis.core.scene.VerifiedScene
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish

/**
 * Number canonicalization that understands both Turkish (5.910.320 / 8.848,86) and English
 * (5,910,320 / 8,848.86) notation. An ambiguous token such as "5.910" yields every reading
 * ({5910, 5.91}); two numbers match when their reading sets intersect.
 */
object NumberCanon {
    private val TOKEN = Regex("\\d[\\d.,]*\\d|\\d")

    fun readings(token: String): Set<String> {
        val t = token.trim().trim('.', ',')
        if (t.isEmpty() || t.any { !it.isDigit() && it != '.' && it != ',' }) return emptySet()
        val dots = t.count { it == '.' }
        val commas = t.count { it == ',' }
        val out = mutableSetOf<String>()
        fun groupedOk(groups: List<String>) = groups.first().length in 1..3 && groups.drop(1).all { it.length == 3 }
        when {
            dots == 0 && commas == 0 -> out += canon(t, "")
            dots > 0 && commas > 0 -> {
                val decSep = if (t.lastIndexOf(',') > t.lastIndexOf('.')) ',' else '.'
                val groupSep = if (decSep == ',') '.' else ','
                val intPart = t.substringBeforeLast(decSep)
                val frac = t.substringAfterLast(decSep)
                if (intPart.count { it == decSep } == 0 && groupedOk(intPart.split(groupSep))) out += canon(intPart.replace(groupSep.toString(), ""), frac)
            }
            else -> {
                val sep = if (dots > 0) '.' else ','
                val parts = t.split(sep)
                if (parts.size > 2) {
                    if (groupedOk(parts)) out += canon(parts.joinToString(""), "")
                } else {
                    // single separator: decimal reading always possible; grouping reading when 3 digits follow
                    out += canon(parts[0], parts[1])
                    if (parts[1].length == 3 && parts[0].length in 1..3) out += canon(parts[0] + parts[1], "")
                }
            }
        }
        return out
    }

    private fun canon(intPart: String, frac: String): String {
        val i = intPart.trimStart('0').ifEmpty { "0" }
        val f = frac.trimEnd('0')
        return if (f.isEmpty()) i else "$i.$f"
    }

    /** All number readings found in a text. */
    fun extract(text: String): Set<String> = TOKEN.findAll(text).flatMap { readings(it.value) }.toSet()

    /** Every number token in [claim] must have a reading present in [evidence]. */
    fun allPresent(claim: String, evidence: Set<String>): Boolean {
        val tokens = TOKEN.findAll(claim).map { it.value }.toList()
        if (tokens.isEmpty()) return false
        return tokens.all { tok -> readings(tok).any { it in evidence } }
    }
}

/** Quote matching: case/space/quote-mark insensitive substring search. */
object QuoteMatcher {
    fun normalize(s: String): String = Turkish.lowercase(s)
        .replace(Regex("[\"“”„«»'’‘`´]"), "")
        .replace(Regex("[\\u2010-\\u2015]"), "-")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.', ',', ';', ':', '…')

    fun contains(evidence: String, quote: String): Boolean {
        val q = normalize(quote)
        return q.length >= 3 && normalize(evidence).contains(q)
    }
}

/**
 * Section 5.4: values in `sayi` and `alinti` visuals must appear verbatim in tool output. Failing
 * visuals are downgraded to a title card; scenes asserting numbers that no source contains get the
 * "kaynak yok" label when the question needed sources.
 */
object Verifier {

    fun verifyScene(sahne: Sahne, evidence: List<String>, requireSources: Boolean): VerifiedScene {
        val joined = evidence.joinToString("\n")
        val numbers = NumberCanon.extract(joined)
        var downgraded = false
        var gorsel = sahne.gorsel
        if (gorsel != null) {
            val ok = when (gorsel.tip) {
                GorselTip.SAYI -> {
                    val v = gorsel.deger.orEmpty()
                    if (v.any { it.isDigit() }) NumberCanon.allPresent(v, numbers) else QuoteMatcher.contains(joined, v)
                }
                GorselTip.ALINTI -> QuoteMatcher.contains(joined, gorsel.metin.orEmpty())
                else -> true
            }
            if (!ok) {
                downgraded = true
                gorsel = Gorsel(tip = GorselTip.BASLIK, baslik = titleFor(gorsel, sahne))
            } else if (gorsel.tip !in GorselTip.ALL) {
                gorsel = Gorsel(tip = GorselTip.YOK)
            }
        }
        val spokenNumbers = Regex("\\d[\\d.,]*\\d|\\d").findAll(sahne.ses).map { it.value }.toList()
        val unsupportedNumbers = spokenNumbers.isNotEmpty() && !NumberCanon.allPresent(sahne.ses, numbers)
        val kaynakYok = requireSources && (evidence.isEmpty() || unsupportedNumbers || downgraded)
        return VerifiedScene(sahne.copy(gorsel = gorsel), downgraded = downgraded, kaynakYok = kaynakYok)
    }

    fun verify(reply: Reply, evidence: List<String>, requireSources: Boolean): VerifiedReply =
        VerifiedReply(
            scenes = reply.sahneler.map { verifyScene(it, evidence, requireSources) },
            detay = reply.detay,
            kaynaklar = reply.kaynaklar,
        )

    private fun titleFor(g: Gorsel, s: Sahne): String =
        (g.etiket ?: g.baslik ?: g.kaynak ?: s.ses.substringBefore('.').take(60)).ifBlank { "Bilgi" }
}

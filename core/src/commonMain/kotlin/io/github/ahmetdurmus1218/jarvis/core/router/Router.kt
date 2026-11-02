package io.github.ahmetdurmus1218.jarvis.core.router

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish

enum class Complexity { EASY, MEDIUM, HARD }

/** Generation budget chosen from the complexity score. */
data class Budget(
    val complexity: Complexity,
    val maxTokens: Int,
    val thinking: Boolean,
    /** Ask the model to review its own answer once before finalizing. */
    val selfCheck: Boolean,
)

/**
 * Heuristic complexity score (idea from OpenJarvis complexity.py): looks for code, math and
 * reasoning signals ("açıkla", "karşılaştır", "neden") plus length.
 */
object ComplexityScorer {
    private val CODE = Regex("```|\\b(kod|kodu|fonksiyon|python|kotlin|java|javascript|sql|regex|algoritma|derle|hata ayikla|debug|class|def|stack trace)\\b|[{};]\\s*$")
    private val MATH = Regex("\\b(integral|turev|denklem|olasilik|ispat|kanitla|matris|limit|logaritma|istatistik|ortalama|varyans)\\b|\\d\\s*[+\\-*/^]\\s*\\d")
    private val REASONING = listOf(
        "acikla", "karsilastir", "neden", "nicin", "nasil calisir", "fark", "analiz", "degerlendir",
        "avantaj", "dezavantaj", "adim adim", "planla", "strateji", "yorumla", "ozetle", "tartis",
    )

    fun score(text: String): Int {
        val n = Turkish.normalize(text)
        var s = 0
        if (text.length > 120) s += 1
        if (text.length > 300) s += 1
        if (CODE.containsMatchIn(n) || text.contains("```")) s += 2
        if (MATH.containsMatchIn(n)) s += 2
        s += REASONING.count { n.contains(it) }.coerceAtMost(3)
        if (text.count { it == '?' } > 1) s += 1
        return s
    }

    fun budget(text: String): Budget {
        val s = score(text)
        return when {
            s >= 4 -> Budget(Complexity.HARD, maxTokens = 2048, thinking = true, selfCheck = true)
            s >= 2 -> Budget(Complexity.MEDIUM, maxTokens = 768, thinking = false, selfCheck = false)
            else -> Budget(Complexity.EASY, maxTokens = 256, thinking = false, selfCheck = false)
        }
    }
}

sealed interface Route {
    data class Quick(val command: QuickCommand) : Route
    /** Factual question: search local sources first and answer only from them. */
    data class Knowledge(val budget: Budget) : Route
    data class Chat(val budget: Budget) : Route
}

object Router {
    private val KNOWLEDGE = Regex(
        "\\b(kimdir|kim|nedir|ne zaman|nerede|nerededir|kac yilinda|hangi yil|nufusu|baskenti|yuzolcumu|" +
            "yuksekligi|uzunlugu|tarihi|kurucusu|yazari|baskani|kac kilometre|kac metre|ne kadar buyuk|" +
            "hakkinda bilgi|anlat bana|bilgi ver)\\b",
    )
    private val CHITCHAT = Regex("^(merhaba|selam|gunaydin|iyi aksamlar|iyi geceler|nasilsin|tesekkurler|tesekkur ederim|sagol|eyvallah)\\b")

    fun route(text: String): Route {
        QuickCommandRouter.route(text)?.let { return Route.Quick(it) }
        val n = Turkish.normalize(text)
        val budget = ComplexityScorer.budget(text)
        if (CHITCHAT.containsMatchIn(n)) return Route.Chat(budget)
        if (KNOWLEDGE.containsMatchIn(n)) return Route.Knowledge(budget)
        return Route.Chat(budget)
    }
}

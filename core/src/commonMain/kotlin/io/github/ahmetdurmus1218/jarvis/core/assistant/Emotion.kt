package io.github.ahmetdurmus1218.jarvis.core.assistant

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish

/** What Rick's eyes show for a moment after a turn: a smile, joy, sadness or surprise. */
enum class Emotion { NONE, HAPPY, JOY, SAD, SURPRISED }

/**
 * Reads the feeling of a turn from its words, the way a person reacts to what was said: thanks and good news
 * bring joy, a greeting a smile, sad news sympathy, "vay be" surprise. The user's words come first (Rick reacts
 * to the person); when they carry nothing, Rick's own answer is read ("Maalesef …" is a little sad).
 */
object EmotionCue {
    private val JOY = listOf(
        "tesekkur", "sagol", "sag ol", "eyvallah", "harika", "super", "muhtesem", "mukemmel", "aferin", "bravo",
        "seni seviyorum", "tebrik", "yasasin", "kazandim", "basardim", "dogum gunu", "cok iyi", "cok guzel", "efsane",
    )
    private val HAPPY = listOf(
        "merhaba", "gunaydin", "iyi aksamlar", "iyi geceler", "selam", "nasilsin", "naber", "guzel", "sevindim",
        "keyifli", "mutluyum", "iyiyim",
    )
    private val SAD = listOf(
        "uzgun", "uzuldum", "moralim bozuk", "kotu hissediyorum", "agliyorum", "yalnizim", "mutsuz", "vefat",
        "kaybettim", "hastayim", "canim sikkin", "kotu haber", "aptal", "salak", "gerizekali", "beceriksiz",
    )
    private val SURPRISED = listOf(
        "vay", "inanilmaz", "gercekten mi", "ciddi misin", "sasirdim", "hayret", "olamaz", "yok artik", "wow",
    )
    private val ANSWER_SAD = listOf("maalesef", "uzgunum", "bulamadim", "kutuphanemde yok", "yapamiyorum")
    private val ANSWER_HAPPY = listOf("tebrikler", "ne guzel", "harika", "sevindim", "rica ederim")

    /** The feeling for a turn: what the user said, else Rick's answer; [Emotion.NONE] when neither shows one. */
    fun of(user: String, answer: String = ""): Emotion {
        val u = " " + Turkish.normalize(user) + " "
        when {
            JOY.any { it in u } -> return Emotion.JOY
            SAD.any { it in u } -> return Emotion.SAD
            SURPRISED.any { word(u, it) } -> return Emotion.SURPRISED
            HAPPY.any { it in u } -> return Emotion.HAPPY
        }
        val a = " " + Turkish.normalize(answer) + " "
        return when {
            ANSWER_SAD.any { it in a } -> Emotion.SAD
            ANSWER_HAPPY.any { it in a } -> Emotion.HAPPY
            else -> Emotion.NONE
        }
    }

    /** Short cues ("vay", "wow") only as whole words: "vaybe" yes, "kavaya" no. */
    private fun word(text: String, cue: String): Boolean =
        Regex("(?<![a-z])" + Regex.escape(cue)).containsMatchIn(text)
}

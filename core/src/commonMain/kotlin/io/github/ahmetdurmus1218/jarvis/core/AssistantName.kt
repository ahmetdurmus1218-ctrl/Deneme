package io.github.ahmetdurmus1218.jarvis.core

/**
 * The assistant's name as the user sees and says it. Code identifiers, the package name and the
 * Android application id keep "jarvis", so updates install over the old app and keep its data.
 */
object AssistantName {
    /** In sentences: "Rick'e yazın", "Rick'in sesi" (same vowel and final-consonant class as before). */
    const val SHORT = "Rick"

    /** The HUD title. */
    const val DOTTED = "R.I.C.K"

    /**
     * The name as speech recognition writes it, on Turkish-normalized text (ç→c, ı→i …): "Rick",
     * "Rik", "Rich"; the old name is still understood so habits keep working.
     */
    const val WAKE = "(?:rick|rik|ric|rich|riyk|jarvis|jarviz|jervis|cervis|cerviz|carvis|carviz)"
}

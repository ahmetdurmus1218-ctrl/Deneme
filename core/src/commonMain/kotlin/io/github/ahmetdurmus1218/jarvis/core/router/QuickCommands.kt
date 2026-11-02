package io.github.ahmetdurmus1218.jarvis.core.router

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.core.text.TurkishNumbers
import io.github.ahmetdurmus1218.jarvis.core.tools.Calculator
import io.github.ahmetdurmus1218.jarvis.core.tools.UnitConverter

/** Commands answered instantly, without the LLM. */
sealed interface QuickCommand {
    data object TimeNow : QuickCommand
    data object DateToday : QuickCommand
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null) : QuickCommand
    data class StartTimer(val seconds: Int) : QuickCommand
    data class OpenApp(val appName: String) : QuickCommand
    data object StopSpeaking : QuickCommand
    data class Calculate(val expression: String) : QuickCommand
    data class ConvertUnit(val value: Double, val from: String, val to: String) : QuickCommand
}

/**
 * Regex router over Turkish-normalized text (ç→c, ğ→g, ı→i, ö→o, ş→s, ü→u). Number words are
 * turned into digits first, so spoken commands like "yedi buçukta alarm kur" work too.
 */
object QuickCommandRouter {

    private const val POLITE = "(?: (?:lutfen|acaba|soyler misin|soyle|soyleyebilir misin|ogrenebilir miyim))*"
    private val TIME = Regex("^(?:simdi )?saat(?:in)? kac(?: oldu)?$POLITE$|^saati (?:soyle|soyler misin)$|^saat ne$")
    private val DATE = Regex("^(?:bugun )?(?:ayin kaci|tarih ne|bugunun tarihi(?: ne)?|ne gun|gunlerden ne|hangi gundeyiz|hangi gun|tarih kac)$POLITE$")
    private val STOP = Regex("^(?:tamam )?(?:sus|dur|sessiz ol|sessize al|yeter|kes|sustur|kapa ceneni)(?: artik)?$")

    private const val UNIT_WORD = "(?:saat|dakika|dk|saniye|sn)"
    // Unit words may carry suffixes: "dakikalik", "dakikaya", "saatte".
    private val DURATION = Regex("(?:\\b\\d+(?:\\s+bucuk)?\\s*$UNIT_WORD[a-z]*\\s*)+")
    private val DURATION_PART = Regex("(\\d+)(\\s+bucuk)?\\s*(saat|dakika|dk|saniye|sn)")
    private val TIMER_WORD = Regex("\\b(?:zamanlayici\\w*|sayac\\w*|geri say\\w*|kronometre\\w*)")
    private val REMIND_WORD = Regex("\\b(?:haber ver|hatirlat|uyar|uyandir)\\w*")
    private val ALARM = Regex("\\b(?:alarm|uyandir)\\w*")
    private val CLOCK = Regex("(\\d{1,2})(?:[:.](\\d{2})|\\s+(\\d{2})(?!\\d)|\\s+(bucuk)\\w*)?\\s*(?:de|da|te|ta|e|a|ye|ya)?\\b")
    private val OPEN_APP = Regex("^(.+?)\\s+(?:uygulamasini\\s+)?(?:ac|acar misin|acsana|acabilir misin|baslat)$")
    private val CALC_SHAPE = Regex("^[\\d\\s.,+\\-*/^%()×÷:]+$")
    private val CALC_FUNC = Regex("^(?:sqrt|ln|log|sin|cos|tan|abs)\\b")

    private const val VOWELS = "aeiou"

    /** Removes the wake word and politeness words around a command. */
    internal fun clean(normalized: String): String = normalized
        .replace(Regex("^(?:hey |ey )?jarvis\\b[ ,]*"), "")
        .replace(Regex("[ ,]*\\bjarvis$"), "")
        .replace(Regex("^lutfen "), "")
        .replace(Regex(" lutfen$"), "")
        .trim()

    fun route(text: String): QuickCommand? {
        val n = clean(Turkish.normalize(text))
        if (n.isEmpty()) return null
        if (TIME.matches(n)) return QuickCommand.TimeNow
        if (DATE.matches(n)) return QuickCommand.DateToday
        if (STOP.matches(n)) return QuickCommand.StopSpeaking
        val nd = TurkishNumbers.wordsToDigits(n)
        timer(nd)?.let { return it }
        if (ALARM.containsMatchIn(nd)) alarm(nd)?.let { return it }
        unit(text)?.let { return it }
        calc(text)?.let { return it }
        OPEN_APP.matchEntire(n)?.let { m ->
            val app = stripAccusative(m.groupValues[1].trim())
            if (app.isNotEmpty() && app.split(' ').size <= 3) return QuickCommand.OpenApp(app)
        }
        return null
    }

    /** "5 dakikalik zamanlayici", "1 saat 10 dakika sonra hatirlat", "2 bucuk saat sonra alarm kur". */
    private fun timer(nd: String): QuickCommand? {
        val d = DURATION.find(nd) ?: return null
        if (!(TIMER_WORD.containsMatchIn(nd) || REMIND_WORD.containsMatchIn(nd) || ALARM.containsMatchIn(nd))) return null
        var seconds = 0.0
        for (p in DURATION_PART.findAll(d.value)) {
            val amount = p.groupValues[1].toDoubleOrNull() ?: return null
            val unitSeconds = when (p.groupValues[3]) {
                "saniye", "sn" -> 1.0
                "dakika", "dk" -> 60.0
                else -> 3600.0
            }
            seconds += (amount + if (p.groupValues[2].isNotBlank()) 0.5 else 0.0) * unitSeconds
        }
        val s = seconds.toInt()
        return if (s in 1..(24 * 3600)) QuickCommand.StartTimer(s) else null
    }

    /** "sabah 7de", "6:45e", "aksam 8.30", "23 15", "yedi bucukta" (after number-word conversion). */
    private fun alarm(nd: String): QuickCommand? {
        val m = CLOCK.find(nd) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = when {
            m.groupValues[2].isNotEmpty() -> m.groupValues[2].toInt()
            m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt()
            m.groupValues[4].isNotEmpty() -> 30
            else -> 0
        }
        val evening = Regex("\\b(aksam|ogleden sonra)\\b").containsMatchIn(nd)
        val night = Regex("\\bgece\\b").containsMatchIn(nd)
        val morning = Regex("\\bsabah\\b").containsMatchIn(nd)
        if (evening && hour in 1..11) hour += 12
        if (night && hour in 7..11) hour += 12
        if ((morning || night) && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return QuickCommand.SetAlarm(hour, minute)
    }

    private fun unit(text: String): QuickCommand? {
        val (v, from, to) = UnitConverter.parse(text) ?: return null
        return QuickCommand.ConvertUnit(v, from, to)
    }

    private fun calc(text: String): QuickCommand? {
        val expr = Calculator.fromSpeech(text)
        if (expr.isEmpty() || expr.none { it.isDigit() }) return null
        val looksLikeMath = CALC_SHAPE.matches(expr) && expr.any { it in "+-*/^%×÷:" }
        val isFunction = CALC_FUNC.containsMatchIn(expr) && expr.replace(Regex("[a-z]+"), "").let { CALC_SHAPE.matches(it.ifBlank { "0" }) }
        if (!looksLikeMath && !isFunction) return null
        return try {
            Calculator.evaluate(expr)
            QuickCommand.Calculate(expr)
        } catch (_: Calculator.CalcException) {
            null
        }
    }

    /**
     * "kamerayi" → "kamera", "ayarlari" → "ayarlar", "hesap makinesini" → "hesap makinesi".
     * Consonant softening ("muzigi" → "muzig") is left to the platform's alias list, because undoing
     * it would break brand names ("Bing", "Discord").
     */
    internal fun stripAccusative(word: String): String {
        val last = word.substringAfterLast(' ')
        val head = word.removeSuffix(last)
        // The buffer consonant (y/n) only exists after a vowel: "kamera-y-i", "makinesi-n-i",
        // but "spotify-i" keeps its own "y".
        fun afterVowel(suffixLength: Int) = last.length > suffixLength && last[last.length - suffixLength - 1] in VOWELS
        val stripped = when {
            last.length > 4 && (last.endsWith("yi") || last.endsWith("yu")) && afterVowel(2) -> last.dropLast(2)
            last.length > 4 && (last.endsWith("ni") || last.endsWith("nu")) && afterVowel(2) -> last.dropLast(2)
            last.length > 3 && (last.endsWith("i") || last.endsWith("u")) -> last.dropLast(1)
            else -> last
        }
        return (head + stripped).trim()
    }
}

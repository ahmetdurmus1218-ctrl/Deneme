package io.github.ahmetdurmus1218.jarvis.core.router

import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
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

/** Regex router over Turkish-normalized text (ç→c, ğ→g, ı→i, ö→o, ş→s, ü→u). */
object QuickCommandRouter {

    private val TIME = Regex("^(?:jarvis )?(?:simdi )?saat kac(?: oldu)?(?: acaba)?$|^saati (?:soyle|soyler misin)$|^saat ne$")
    private val DATE = Regex("^(?:jarvis )?(?:bugun )?(?:ayin kaci|tarih ne|bugunun tarihi(?: ne)?|bugun ne gun|bugun gunlerden ne|hangi gundeyiz|bugun hangi gun|tarih kac)$")
    private val STOP = Regex("^(?:jarvis )?(?:sus|dur|sessiz ol|sessize al|yeter|tamam sus|kes|sustur)$")
    private val TIMER = Regex("(\\d+)\\s*(saniye|sn|dakika|dk|saat)(?:lik)?\\b.*\\b(zamanlayici|sayac|geri say|alarm kur(?:ma)?\\s*sayac)")
    private val TIMER2 = Regex("\\b(?:zamanlayici|sayac)\\w*\\s+(?:kur\\s+)?(\\d+)\\s*(saniye|sn|dakika|dk|saat)")
    private val TIMER3 = Regex("\\b(\\d+)\\s*(saniye|sn|dakika|dk|saat)\\s+(?:sonra\\s+)?(?:.*\\s)?(?:haber ver|hatirlat|uyar|alarm)")
    private val ALARM = Regex("\\balarm\\b")
    private val CLOCK = Regex("(?:saat\\s*)?(\\d{1,2})(?:[:.](\\d{2})|\\s+(\\d{2})(?!\\d))?\\s*(?:de|da|te|ta|e|a|ye|ya)?\\b")
    private val OPEN_APP = Regex("^(?:jarvis )?(.+?)\\s+(?:uygulamasini\\s+)?(?:ac|acar misin|acsana|baslat)$")
    private val CALC_SHAPE = Regex("^[\\d\\s.,+\\-*/^%()×÷:]+$")
    private val CALC_FUNC = Regex("^(?:sqrt|ln|log|sin|cos|tan|abs)\\b")

    fun route(text: String): QuickCommand? {
        val n = Turkish.normalize(text)
        if (n.isEmpty()) return null
        if (TIME.matches(n)) return QuickCommand.TimeNow
        if (DATE.matches(n)) return QuickCommand.DateToday
        if (STOP.matches(n)) return QuickCommand.StopSpeaking
        timer(n)?.let { return it }
        if (ALARM.containsMatchIn(n)) alarm(n)?.let { return it }
        unit(text)?.let { return it }
        calc(text)?.let { return it }
        OPEN_APP.matchEntire(n)?.let { m ->
            val app = stripAccusative(m.groupValues[1].trim())
            if (app.isNotEmpty() && app.split(' ').size <= 3) return QuickCommand.OpenApp(app)
        }
        return null
    }

    private fun timer(n: String): QuickCommand? {
        val m = TIMER.find(n) ?: TIMER2.find(n) ?: TIMER3.find(n) ?: return null
        val amount = m.groupValues[1].toIntOrNull() ?: return null
        val seconds = when (m.groupValues[2]) {
            "saniye", "sn" -> amount
            "dakika", "dk" -> amount * 60
            else -> amount * 3600
        }
        return if (seconds in 1..(24 * 3600)) QuickCommand.StartTimer(seconds) else null
    }

    private fun alarm(n: String): QuickCommand? {
        val m = CLOCK.find(n) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = (m.groupValues[2].ifEmpty { m.groupValues[3] }).ifEmpty { "0" }.toInt()
        val evening = Regex("\\b(aksam|gece|ogleden sonra)\\b").containsMatchIn(n)
        val morning = Regex("\\b(sabah)\\b").containsMatchIn(n)
        if (evening && hour in 1..11 && !(Regex("\\bgece\\b").containsMatchIn(n) && hour <= 4)) hour += 12
        if (morning && hour == 12) hour = 0
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

    /** "kamerayi" → "kamera", "ayarlari" → "ayarlar", "haritalari" → "haritalar". */
    internal fun stripAccusative(word: String): String {
        val last = word.substringAfterLast(' ')
        val head = word.removeSuffix(last)
        val stripped = when {
            last.length > 4 && (last.endsWith("yi") || last.endsWith("yu")) -> last.dropLast(2)
            last.length > 4 && last.endsWith("ni") -> last.dropLast(2)
            last.length > 3 && (last.endsWith("i") || last.endsWith("u")) -> last.dropLast(1)
            else -> last
        }
        return (head + stripped).trim()
    }
}

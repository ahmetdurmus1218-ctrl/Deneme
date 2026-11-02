package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommandRouter
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.core.text.TurkishNumbers
import io.github.ahmetdurmus1218.jarvis.core.tools.Calculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Spoken Turkish: number words, suffixes, wake word and politeness words. */
class VoiceCommandTest {
    private fun digits(s: String) = TurkishNumbers.wordsToDigits(Turkish.normalize(s))

    @Test
    fun numberWordsBecomeDigits() {
        assertEquals("5 dakikalik zamanlayici", digits("beş dakikalık zamanlayıcı"))
        assertEquals("12 carpi 7", digits("on iki çarpı yedi"))
        assertEquals("1923", digits("bin dokuz yüz yirmi üç"))
        assertEquals("250", digits("iki yüz elli"))
        assertEquals("sabah 7de", digits("sabah yedide"))
        assertEquals("aksam 9da", digits("akşam dokuzda"))
        assertEquals("saat 11 bucukta", digits("saat on bir buçukta"))
        assertEquals("yuzde 50", digits("yüzde elli"))
        assertEquals("saat kac", digits("saat kaç"))
    }

    @Test
    fun spokenTimers() {
        assertEquals(QuickCommand.StartTimer(300), QuickCommandRouter.route("beş dakikalık zamanlayıcı kur"))
        assertEquals(QuickCommand.StartTimer(4200), QuickCommandRouter.route("1 saat 10 dakika sonra hatırlat"))
        assertEquals(QuickCommand.StartTimer(9000), QuickCommandRouter.route("iki buçuk saat sonra alarm kur"))
        assertEquals(QuickCommand.StartTimer(300), QuickCommandRouter.route("5 dakikaya alarm kur"))
        assertEquals(QuickCommand.StartTimer(30), QuickCommandRouter.route("otuz saniye geri say"))
        assertEquals(QuickCommand.StartTimer(900), QuickCommandRouter.route("on beş dakika sonra beni uyandır"))
    }

    @Test
    fun spokenAlarms() {
        assertEquals(QuickCommand.SetAlarm(7, 30), QuickCommandRouter.route("yedi buçukta beni uyandır"))
        assertEquals(QuickCommand.SetAlarm(6, 0), QuickCommandRouter.route("sabah altıda alarm kur"))
        assertEquals(QuickCommand.SetAlarm(21, 0), QuickCommandRouter.route("akşam dokuzda alarm kur"))
        assertEquals(QuickCommand.SetAlarm(0, 0), QuickCommandRouter.route("gece on ikide alarm kur"))
        assertEquals(QuickCommand.SetAlarm(23, 0), QuickCommandRouter.route("gece 11'de alarm kur"))
        assertEquals(QuickCommand.SetAlarm(5, 0), QuickCommandRouter.route("saat beşe alarm kur"))
    }

    @Test
    fun wakeWordAndPolitenessAreIgnored() {
        assertEquals(QuickCommand.TimeNow, QuickCommandRouter.route("Hey Jarvis, saat kaç?"))
        assertEquals(QuickCommand.TimeNow, QuickCommandRouter.route("saat kaç söyler misin"))
        assertEquals(QuickCommand.TimeNow, QuickCommandRouter.route("Saat kaç Jarvis"))
        assertEquals(QuickCommand.DateToday, QuickCommandRouter.route("Jarvis bugün ayın kaçı lütfen"))
        assertEquals(QuickCommand.StopSpeaking, QuickCommandRouter.route("Tamam sus artık"))
        assertEquals(QuickCommand.OpenApp("kamera"), QuickCommandRouter.route("Jarvis kamerayı açar mısın"))
    }

    @Test
    fun spokenArithmeticAndUnits() {
        val c = QuickCommandRouter.route("on iki çarpı yedi kaç eder")
        assertIs<QuickCommand.Calculate>(c)
        assertEquals("84", Calculator.format(Calculator.evaluate(c.expression)))
        assertEquals("8", Calculator.format(Calculator.evaluate(Calculator.fromSpeech("beş artı üç"))))
        assertEquals("1024", Calculator.format(Calculator.evaluate(Calculator.fromSpeech("iki üssü on"))))
        assertEquals("7", Calculator.format(Calculator.evaluate("21 ÷ 3")))
        val u = QuickCommandRouter.route("beş kilometre kaç mil")
        assertIs<QuickCommand.ConvertUnit>(u)
        assertEquals(5.0, u.value)
    }

    @Test
    fun appNamesKeepTheirSpelling() {
        assertEquals(QuickCommand.OpenApp("spotify"), QuickCommandRouter.route("Spotify'ı aç"))
        assertEquals(QuickCommand.OpenApp("discord"), QuickCommandRouter.route("Discord'u aç"))
        assertEquals(QuickCommand.OpenApp("hesap makinesi"), QuickCommandRouter.route("hesap makinesini aç"))
        assertEquals(QuickCommand.OpenApp("galeri"), QuickCommandRouter.route("galeriyi aç"))
    }

    @Test
    fun everydaySentencesStayWithTheAssistant() {
        assertNull(QuickCommandRouter.route("bana bir fıkra anlat"))
        assertNull(QuickCommandRouter.route("bir şey sorabilir miyim"))
        assertNull(QuickCommandRouter.route("yüzde elli indirim ne demek"))
        assertNull(QuickCommandRouter.route("on yıl önce ne oldu"))
    }
}

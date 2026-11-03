package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.router.Complexity
import io.github.ahmetdurmus1218.jarvis.core.router.ComplexityScorer
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommandRouter
import io.github.ahmetdurmus1218.jarvis.core.router.Route
import io.github.ahmetdurmus1218.jarvis.core.router.Router
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouterTest {
    @Test
    fun normalizesTurkish() {
        assertEquals("cigdem isik oyku sut", Turkish.normalize("ÇİĞDEM IŞIK Öykü Süt"))
        assertEquals("saat kac", Turkish.normalize("Saat kaç?"))
        assertEquals("istanbul", Turkish.normalize("İSTANBUL"))
        assertEquals("kamerayi ac", Turkish.normalize("Kamera'yı aç."))
    }

    @Test
    fun timeAndDate() {
        assertEquals(QuickCommand.TimeNow, QuickCommandRouter.route("Saat kaç?"))
        assertEquals(QuickCommand.TimeNow, QuickCommandRouter.route("Rick şimdi saat kaç"))
        assertEquals(QuickCommand.DateToday, QuickCommandRouter.route("Bugün ayın kaçı?"))
        assertEquals(QuickCommand.DateToday, QuickCommandRouter.route("bugün günlerden ne"))
    }

    @Test
    fun alarms() {
        assertEquals(QuickCommand.SetAlarm(7, 0), QuickCommandRouter.route("Yarın sabah 7'de alarm kur"))
        assertEquals(QuickCommand.SetAlarm(6, 45), QuickCommandRouter.route("6:45'e alarm kur"))
        assertEquals(QuickCommand.SetAlarm(20, 30), QuickCommandRouter.route("akşam 8.30 için alarm"))
        assertEquals(QuickCommand.SetAlarm(23, 15), QuickCommandRouter.route("alarm kur 23 15"))
    }

    @Test
    fun timers() {
        assertEquals(QuickCommand.StartTimer(300), QuickCommandRouter.route("5 dakikalık zamanlayıcı kur"))
        assertEquals(QuickCommand.StartTimer(90), QuickCommandRouter.route("zamanlayıcı 90 saniye"))
        assertEquals(QuickCommand.StartTimer(1200), QuickCommandRouter.route("20 dakika sonra haber ver"))
        assertEquals(QuickCommand.StartTimer(7200), QuickCommandRouter.route("2 saat sonra alarm kur"))
    }

    @Test
    fun silenceAndApps() {
        assertEquals(QuickCommand.StopSpeaking, QuickCommandRouter.route("Sus"))
        assertEquals(QuickCommand.StopSpeaking, QuickCommandRouter.route("sessiz ol"))
        assertEquals(QuickCommand.OpenApp("kamera"), QuickCommandRouter.route("Kamerayı aç"))
        assertEquals(QuickCommand.OpenApp("ayarlar"), QuickCommandRouter.route("ayarları aç"))
    }

    @Test
    fun calculationAndUnits() {
        val c = QuickCommandRouter.route("12 artı 30 kaç eder")
        assertIs<QuickCommand.Calculate>(c)
        assertIs<QuickCommand.Calculate>(QuickCommandRouter.route("(3+4)*2"))
        val u = QuickCommandRouter.route("5 km kaç mil")
        assertIs<QuickCommand.ConvertUnit>(u)
        assertEquals(5.0, u.value)
        assertIs<QuickCommand.ConvertUnit>(QuickCommandRouter.route("100 fahrenhayt kaç derece"))
    }

    @Test
    fun ordinaryQuestionsAreNotQuickCommands() {
        assertNull(QuickCommandRouter.route("İstanbul'un nüfusu kaç?"))
        assertNull(QuickCommandRouter.route("Bana bir şiir yaz"))
        assertNull(QuickCommandRouter.route("1923 yılında ne oldu"))
    }

    @Test
    fun routesKnowledgeAndChat() {
        assertIs<Route.Knowledge>(Router.route("Mustafa Kemal Atatürk kimdir?"))
        assertIs<Route.Knowledge>(Router.route("Ankara'nın nüfusu ne kadar"))
        assertIs<Route.Chat>(Router.route("Merhaba"))
        assertIs<Route.Quick>(Router.route("saat kaç"))
    }

    @Test
    fun complexityBudgets() {
        assertEquals(Complexity.EASY, ComplexityScorer.budget("Merhaba").complexity)
        val hard = ComplexityScorer.budget("Bu Kotlin kodunu adım adım açıkla ve neden çalışmadığını analiz et: fun f() {}")
        assertEquals(Complexity.HARD, hard.complexity)
        assertTrue(hard.thinking)
        assertTrue(hard.selfCheck)
        assertTrue(hard.maxTokens > ComplexityScorer.budget("Merhaba").maxTokens)
    }

    @Test
    fun handControlCommands() {

        assertEquals(QuickCommand.ShowPlanet(io.github.ahmetdurmus1218.jarvis.core.vision.PlanetKind.MARS), QuickCommandRouter.route("Rick, Mars yap"))
        assertEquals(QuickCommand.ShowPlanet(io.github.ahmetdurmus1218.jarvis.core.vision.PlanetKind.JUPITER), QuickCommandRouter.route("Jüpiter'e çevir"))
        assertEquals(QuickCommand.ShowPlanet(io.github.ahmetdurmus1218.jarvis.core.vision.PlanetKind.MOON), QuickCommandRouter.route("Aya çevir"))
        assertEquals(QuickCommand.ShowPlanet(io.github.ahmetdurmus1218.jarvis.core.vision.PlanetKind.SATURN), QuickCommandRouter.route("bir Satürn yap"))
        assertEquals(QuickCommand.HandControl(true), QuickCommandRouter.route("Rick, el kontrolünü aç"))
        assertEquals(QuickCommand.HandControl(false), QuickCommandRouter.route("el kontrolünü kapat"))
        assertEquals(null, QuickCommandRouter.route("Mars'ta hayat var mı?"))
        assertEquals(QuickCommand.Sleep, QuickCommandRouter.route("Rick, uyku moduna geç"))
    }
}

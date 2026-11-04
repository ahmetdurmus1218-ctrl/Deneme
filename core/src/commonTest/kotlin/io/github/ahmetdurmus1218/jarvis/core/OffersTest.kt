package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant
import io.github.ahmetdurmus1218.jarvis.core.assistant.AssistantEvent
import io.github.ahmetdurmus1218.jarvis.core.assistant.DefaultTools
import io.github.ahmetdurmus1218.jarvis.core.assistant.NeedDetector
import io.github.ahmetdurmus1218.jarvis.core.assistant.Offer
import io.github.ahmetdurmus1218.jarvis.core.assistant.OfferReply
import io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineWikipedia
import io.github.ahmetdurmus1218.jarvis.core.assistant.WebPage
import io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction
import io.github.ahmetdurmus1218.jarvis.core.device.DeviceCommands
import io.github.ahmetdurmus1218.jarvis.core.device.MediaKey
import io.github.ahmetdurmus1218.jarvis.core.device.OutsideApp
import io.github.ahmetdurmus1218.jarvis.core.device.VolumeChange
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import io.github.ahmetdurmus1218.jarvis.core.memory.FactExtractor
import io.github.ahmetdurmus1218.jarvis.core.memory.InMemoryMemoryStore
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommandRouter
import io.github.ahmetdurmus1218.jarvis.core.assistant.LiveInfo
import io.github.ahmetdurmus1218.jarvis.core.assistant.OnlineNews
import io.github.ahmetdurmus1218.jarvis.core.assistant.WebSources
import io.github.ahmetdurmus1218.jarvis.core.assistant.WebCommand
import io.github.ahmetdurmus1218.jarvis.core.llm.ChatMessage
import io.github.ahmetdurmus1218.jarvis.core.llm.Role
import io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OffersTest {
    private fun need(text: String) = NeedDetector.detect(text)?.offer?.command

    @Test
    fun needsSaidInPassingBecomeOffers() {
        assertEquals(QuickCommand.SetAlarm(7, 0), need("Yarın 7'de kalkmam lazım"))
        assertEquals(QuickCommand.SetAlarm(6, 30), need("sabah 6.30'da uyanmam gerek"))
        assertEquals(QuickCommand.SetAlarm(20, 0), need("akşam 8'de uyanmalıyım"))
        assertEquals(QuickCommand.Device(DeviceAction.Torch(true)), need("Burası çok karanlık"))
        assertEquals(QuickCommand.Device(DeviceAction.Media(MediaKey.PLAY)), need("Canım çok sıkılıyor"))
        assertEquals(QuickCommand.Device(DeviceAction.Volume(VolumeChange.SILENT_PHONE)), need("Ben artık yatıyorum"))
        assertEquals(QuickCommand.Device(DeviceAction.Call("Annem")), need("Annemi aramam lazım"))
        assertEquals("Annem aransın mı?", NeedDetector.detect("Annemi aramam lazım")!!.say)
        assertEquals("Saat 07:00 için alarm kurayım mı?", NeedDetector.detect("Yarın 7'de kalkmam lazım")!!.say)
    }

    @Test
    fun ordinarySentencesAreNotNeeds() {
        assertNull(need("Karanlık madde nedir?"))
        assertNull(need("Bugün hava çok güzel"))
        assertNull(need("Saat 7'de ne oldu?"))
        assertNull(need("Onu aramam lazım"))
    }

    @Test
    fun yesAndNo() {
        listOf("Evet", "olur", "tamam yap", "Evet lütfen.", "hadi bak", "tabii ki", "kur").forEach { assertEquals(true, OfferReply.parse(it), it) }
        listOf("Hayır", "gerek yok", "boşver", "şimdi değil", "yok teşekkürler").forEach { assertEquals(false, OfferReply.parse(it), it) }
        listOf("Evet ama önce saat kaç?", "Atatürk kimdir", "tamam da neden").forEach { assertNull(OfferReply.parse(it), it) }
        // Yes in more words, as people say it.
        listOf("Online ol, onay verdim, bak", "izin veriyorum bak", "onaylıyorum", "tamam internete çık", "evet bak hadi rick").forEach { assertEquals(true, OfferReply.parse(it), it) }
        listOf("hayır internete çıkma", "istemiyorum teşekkürler").forEach { assertEquals(false, OfferReply.parse(it), it) }
        // A new command is not a yes to the old offer.
        listOf("tamam alarm kur saat 7", "annemi ara", "YouTube'dan Tarkan aç").forEach { assertNull(OfferReply.parse(it), it) }
    }

    private fun openIn(text: String) = (QuickCommandRouter.route(text) as? QuickCommand.Device)?.action

    @Test
    fun searchesAreHandedToYouTubeSpotifyAndGoogle() {
        assertEquals(DeviceAction.OpenIn(OutsideApp.YOUTUBE, "Tarkan Şımarık"), openIn("YouTube'dan Tarkan Şımarık aç"))
        assertEquals(DeviceAction.OpenIn(OutsideApp.YOUTUBE, "Sezen Aksu Gidiyorum"), openIn("YouTube'da Sezen Aksu'nun Gidiyorum şarkısını açar mısın"))
        assertEquals(DeviceAction.OpenIn(OutsideApp.YOUTUBE, "Barış Manço"), openIn("Barış Manço'yu YouTube'dan aç"))
        assertEquals(DeviceAction.OpenIn(OutsideApp.SPOTIFY, "Duman"), openIn("Spotify'dan Duman çal"))
        assertEquals(DeviceAction.OpenIn(OutsideApp.YOUTUBE_MUSIC, "Müslüm Gürses"), openIn("YouTube Müzik'ten Müslüm Gürses çal"))
        assertEquals(DeviceAction.OpenIn(OutsideApp.GOOGLE, "kedi bakımı"), openIn("Google'da kedi bakımı ara"))
        assertEquals(DeviceAction.OpenIn(OutsideApp.MUSIC, "Tarkan Kuzu Kuzu"), openIn("Tarkan Kuzu Kuzu şarkısını çal"))
        assertEquals(DeviceAction.OpenIn(OutsideApp.YOUTUBE, "kedi"), openIn("kedi videosunu aç"))
        // Not said what: Rick asks.
        assertEquals(DeviceAction.OpenIn(OutsideApp.YOUTUBE, ""), openIn("YouTube'dan şu müziği aç"))
        // Opening the app itself, and plain music, stay as they were.
        assertEquals(QuickCommand.OpenApp("youtube"), QuickCommandRouter.route("YouTube'u aç"))
        assertEquals(QuickCommand.OpenApp("youtube muzik"), QuickCommandRouter.route("YouTube Müzik aç"))
        assertEquals(DeviceAction.Media(MediaKey.PLAY), openIn("bir şarkı aç"))
        assertEquals(DeviceAction.Media(MediaKey.PLAY), openIn("müzik çal"))
        assertEquals("https://www.youtube.com/results?search_query=Tarkan%20%C5%9E%C4%B1mar%C4%B1k", OutsideApp.YOUTUBE.webUrl("Tarkan Şımarık"))
        assertEquals("YouTube'da \"Duman\" sonuçlarını açıyorum; istediğinize dokunun.", DeviceCommands.describe(DeviceAction.OpenIn(OutsideApp.YOUTUBE, "Duman")))
    }

    @Test
    fun askingToGoOnline() {
        assertEquals("Çanakkale Savaşı", WebCommand.parse("internette Çanakkale Savaşı ara"))
        assertEquals("Atatürk", WebCommand.parse("Atatürk'ü internetten arar mısın?"))
        assertEquals("kara delik", WebCommand.parse("internetten kara delik bak"))
        assertEquals("", WebCommand.parse("Online ol, onay verdim, bak"))
        assertEquals("", WebCommand.parse("internete çık"))
        assertEquals("", WebCommand.parse("bunu internette ara"))
        assertNull(WebCommand.parse("internet nedir"))
        assertNull(WebCommand.parse("Atatürk kimdir?"))
        assertNull(WebCommand.parse("online mısın?"))
    }

    @Test
    fun liveQuestionsAreNotGuessed() {
        listOf("Bugün hava nasıl?", "Dolar kaç TL", "Galatasaray maçı kaç kaç bitti").forEach { assertTrue(LiveInfo.matches(it), it) }
        listOf("Borsa nedir?", "Atatürk kimdir?", "Su kaç derecede kaynar?").forEach { assertFalse(LiveInfo.matches(it), it) }
    }

    @Test
    fun goOnlineUsesTheLastQuestion() = runTest {
        val history = listOf(ChatMessage(Role.USER, "Atatürk kimdir?"), ChatMessage(Role.ASSISTANT, "Bunu kütüphanemde bulamadım."))
        val ev = assistant(web = true).ask("online ol onay verdim bak", history).toList().single()
        assertEquals(Offer.WebLookup("Atatürk", "Atatürk kimdir?"), (ev as AssistantEvent.WebRequested).lookup)
        val direct = assistant(web = true).ask("internette Çanakkale Savaşı ara").toList().single() as AssistantEvent.WebRequested
        assertEquals("Çanakkale Savaşı", direct.lookup.query)
        // Nothing asked before: Rick asks what to look up.
        val none = assistant(web = true).ask("internete çık").toList()
        assertTrue(none.filterIsInstance<AssistantEvent.Completed>().single().reply.scenes.single().sahne.ses.startsWith("Neye bakayım"))
        // Today's weather goes to the browser's search, not to an encyclopedia.
        val weather = assistant(web = true).ask("internette bugün hava durumu ara").toList().single() as AssistantEvent.Quick
        assertEquals(QuickCommand.Device(DeviceAction.OpenIn(OutsideApp.GOOGLE, "bugün hava durumu")), weather.command)
    }

    @Test
    fun withoutAGateTheBrowserIsOffered() = runTest {
        val memory = InMemoryMemoryStore()
        val a = Assistant(MockLlmEngine(tokenDelayMs = 0), DefaultTools.create(memory), memory, webOffer = { false }, browserOffer = { true })
        val offered = a.ask("Atatürk kimdir?").toList().filterIsInstance<AssistantEvent.Offered>().single()
        assertEquals(Offer.Action(QuickCommand.Device(DeviceAction.OpenIn(OutsideApp.GOOGLE, "Atatürk"))), offered.offer)
        val live = a.ask("Bugün hava nasıl?").toList().single() as AssistantEvent.Offered
        assertEquals(Offer.Action(QuickCommand.Device(DeviceAction.OpenIn(OutsideApp.GOOGLE, "Bugün hava nasıl"))), live.offer)
        val vague = a.ask("YouTube'dan bir şarkı aç").toList().single() as AssistantEvent.Offered
        assertEquals(Offer.AskWhat(OutsideApp.YOUTUBE), vague.offer)
    }

    @Test
    fun onlyRickBuiltWikipediaSearchesPass() {
        val url = OnlineWikipedia.searchUrl("Çanakkale Savaşı")
        assertTrue(url.startsWith("https://tr.wikipedia.org/w/api.php?"))
        assertTrue("%C3%87anakkale%20Sava%C5%9F%C4%B1" in url, url)
        assertEquals("Çanakkale Savaşı", OnlineWikipedia.queryOf(url))
        assertTrue(OnlineWikipedia.allowed(url))
        assertFalse(OnlineWikipedia.allowed("https://tr.wikipedia.org/w/api.php?action=edit"))
        assertFalse(OnlineWikipedia.allowed(url.replace("tr.wikipedia.org", "evil.example")))
        assertFalse(OnlineWikipedia.allowed(url.replace("https", "http")))
        assertFalse(OnlineWikipedia.allowed(url + "&x=1"))
        assertFalse(OnlineWikipedia.allowed(url.replace("%C3%87anakkale", "a&b=c")))
    }

    @Test
    fun apiAnswerIsReadVerbatimBestFirst() {
        val json = """{"batchcomplete":true,"query":{"pages":[
            {"pageid":2,"ns":0,"title":"Çanakkale (il)","index":2,"extract":"Çanakkale, Türkiye'nin bir ilidir. Nüfusu 559.383'tür."},
            {"pageid":1,"ns":0,"title":"Çanakkale Savaşı","index":1,"extract":"Çanakkale Savaşı, 1915-1916 yıllarında Gelibolu Yarımadası'nda yapılan savaştır."},
            {"pageid":3,"ns":0,"title":"Boş","index":3,"extract":""}]}}"""
        val pages = OnlineWikipedia.parse(json)
        assertEquals(listOf("Çanakkale Savaşı", "Çanakkale (il)"), pages.map { it.title })
        assertEquals("https://tr.wikipedia.org/wiki/%C3%87anakkale_Sava%C5%9F%C4%B1", pages[0].url)
        assertEquals(listOf("Çanakkale (il)", "Çanakkale Savaşı"), OnlineWikipedia.parse(json, "Çanakkale").map { it.title }, "exact title first")
        assertTrue(OnlineWikipedia.parse("<html>").isEmpty())
        assertTrue(OnlineWikipedia.parse("""{"batchcomplete":true}""").isEmpty())
    }

    private fun assistant(web: Boolean, canOffer: (QuickCommand) -> Boolean = { true }): Assistant {
        val memory = InMemoryMemoryStore()
        return Assistant(MockLlmEngine(tokenDelayMs = 0), DefaultTools.create(memory), memory, webOffer = { web }, canOffer = canOffer)
    }

    @Test
    fun aMissingAnswerOffersTheInternet() = runTest {
        val events = assistant(web = true).ask("Atatürk kimdir?").toList()
        val offered = events.filterIsInstance<AssistantEvent.Offered>().single()
        assertEquals(Offer.WebLookup("Atatürk", "Atatürk kimdir?"), offered.offer)
        assertTrue("Wikipedia" in offered.scene.sahne.ses)
        // Without a gate the old honest answer stays.
        val offline = assistant(web = false).ask("Atatürk kimdir?").toList()
        assertTrue(offline.none { it is AssistantEvent.Offered })
    }

    @Test
    fun needOffersRespectThePlatform() = runTest {
        val events = assistant(web = false).ask("Burası çok karanlık").toList()
        assertTrue(events.single() is AssistantEvent.Offered)
        val pc = assistant(web = false, canOffer = { !(it is QuickCommand.Device && it.action is DeviceAction.Torch) }).ask("Burası çok karanlık").toList()
        assertTrue(pc.none { it is AssistantEvent.Offered })
    }

    @Test
    fun withoutAModelTheWebPageIsReadWithItsSource() = runTest {
        val page = WebPage("Çanakkale Savaşı", "Çanakkale Savaşı, 1915-1916 yıllarında yapılan savaştır. İkinci cümle burada.", "https://tr.wikipedia.org/wiki/x")
        val events = assistant(web = true).answerFromWeb("Çanakkale Savaşı nedir", listOf(page)).toList()
        val done = events.filterIsInstance<AssistantEvent.Completed>().single()
        val scene = done.reply.scenes.single()
        assertTrue(scene.sahne.ses.startsWith("Çanakkale Savaşı, 1915-1916"), scene.sahne.ses)
        assertEquals(GorselTip.KAYNAK_KARTI, scene.sahne.gorsel?.tip)
        assertEquals("web", done.reply.kaynaklar.single().tur)
        val none = assistant(web = true).answerFromWeb("x", emptyList()).toList()
        assertTrue(none.filterIsInstance<AssistantEvent.Completed>().single().reply.scenes.single().sahne.ses.contains("bulamadım"))
    }

    @Test
    fun onlyPersonalSentencesGoToTheFactModel() {
        assertTrue(FactExtractor.worthAsking("Benim adım Ahmet"))
        assertTrue(FactExtractor.worthAsking("Kahveyi hep şekersiz içerim, unutma"))
        assertFalse(FactExtractor.worthAsking("Bana bir fıkra anlatır mısın?"))
        assertFalse(FactExtractor.worthAsking("Bugün hava güzel"))
    }

    @Test
    fun callsFindThePersonAsSaid() {
        fun call(t: String) = (QuickCommandRouter.route(t) as? QuickCommand.Device)?.action
        assertEquals(DeviceAction.Call("Ahmet"), call("Ahmet'e telefon et"))
        assertEquals(DeviceAction.Call("Annem"), call("Annemi ara hemen"))
        assertEquals(DeviceAction.Call("Ahmet"), call("Ahmet'i telefonla ara"))
        assertEquals(DeviceAction.Call("babam"), call("babamı arayabilir misin"))
        assertEquals(DeviceAction.Call("Ayşe"), call("Ayşe'yi aramak istiyorum"))
        assertEquals("Ahmet aranıyor.", DeviceCommands.describe(DeviceAction.Call("Ahmet")))
        val book = listOf("Anne" to "0532 1", "Baba" to "0533 2", "Ahmet Yılmaz" to "0534 3", "Ayşe Kara" to "0535 4", "Mehmet" to "0536 5")
        assertEquals("Anne", DeviceCommands.findContact("Annem", book)?.first)
        assertEquals("Baba", DeviceCommands.findContact("babam", book)?.first)
        assertEquals("Ahmet Yılmaz", DeviceCommands.findContact("ahmete", book)?.first)
        assertEquals("Ahmet Yılmaz", DeviceCommands.findContact("Ahmet'i", book)?.first)
        assertEquals("Ayşe Kara", DeviceCommands.findContact("ayseyi", book)?.first)
        assertEquals("Mehmet", DeviceCommands.findContact("Mehmet", book)?.first)
        assertNull(DeviceCommands.findContact("Zeynep", book))
    }

    @Test
    fun theDaysNewsIsAskedInPlainWords() {
        listOf("Günün haberleri", "Rick, bugünün haberleri neler?", "haberleri oku", "Son dakika haberler", "gündemde ne var", "neler oluyor", "haberler").forEach {
            assertTrue(OnlineNews.matches(it), it)
        }
        listOf("Haber kanalını aç", "Bu haberi kim yazdı?", "Atatürk kimdir?", "haberleşme nasıl çalışır").forEach { assertFalse(OnlineNews.matches(it), it) }
        assertTrue(WebSources.allowed(OnlineNews.URL))
        assertFalse(WebSources.allowed(OnlineNews.URL + "?x=1"))
        assertFalse(WebSources.allowed("https://feeds.bbci.co.uk/news/rss.xml"))
        assertEquals("feeds.bbci.co.uk", WebSources.hostOf(OnlineNews.URL))
    }

    @Test
    fun headlinesAreReadFromTheFeedAsTheyAre() {
        val rss = """<?xml version="1.0"?><rss><channel><title>BBC News Türkçe</title>
            <item><title><![CDATA[Meclis'te yeni bütçe görüşmeleri başladı]]></title><description><![CDATA[Görüşmeler 12 gün sürecek.]]></description><link>https://www.bbc.com/turkce/articles/a1</link></item>
            <item><title>Deprem bölgesinde &amp; konut teslimleri sürüyor</title><description>Ayrıntılar &lt;b&gt;haberde&lt;/b&gt;.</description><link>https://www.bbc.com/turkce/articles/a2</link></item>
            <item><title>Kısa</title></item>
            </channel></rss>"""
        val items = OnlineNews.parse(rss)
        assertEquals(listOf("Meclis'te yeni bütçe görüşmeleri başladı", "Deprem bölgesinde & konut teslimleri sürüyor"), items.map { it.title })
        assertEquals("Ayrıntılar haberde .".replace(" .", "."), items[1].summary.replace(" .", "."))
        assertEquals("https://www.bbc.com/turkce/articles/a1", items[0].url)
        assertTrue(OnlineNews.parse("<html>nope</html>").isEmpty())
    }

    @Test
    fun newsGoesStraightToTheApprovalAndIsReadVerbatim() = runTest {
        val ev = assistant(web = true).ask("Günün haberleri neler?").toList().single()
        assertTrue(ev is AssistantEvent.NewsRequested)
        val items = listOf(io.github.ahmetdurmus1218.jarvis.core.assistant.NewsItem("Meclis'te yeni bütçe görüşmeleri başladı", "Görüşmeler 12 gün sürecek.", "https://www.bbc.com/turkce/a1"))
        val done = assistant(web = true).newsReply(items).toList().filterIsInstance<AssistantEvent.Completed>().single()
        assertEquals("Meclis'te yeni bütçe görüşmeleri başladı.", done.reply.scenes[1].sahne.ses)
        assertTrue(done.reply.scenes[0].sahne.ses.contains("BBC Türkçe"))
        assertEquals("web", done.reply.kaynaklar.single().tur)
        val question = assistant(web = true).newsReply(listOf(io.github.ahmetdurmus1218.jarvis.core.assistant.NewsItem("Bu ne anlama geliyor?", "", "https://www.bbc.com/turkce/a2"))).toList()
        assertEquals("Bu ne anlama geliyor?", question.filterIsInstance<AssistantEvent.Completed>().single().reply.scenes[1].sahne.ses)
        val none = assistant(web = true).newsReply(emptyList()).toList().filterIsInstance<AssistantEvent.Completed>().single()
        assertTrue(none.reply.scenes.single().sahne.ses.contains("okuyamadım"))
    }
}

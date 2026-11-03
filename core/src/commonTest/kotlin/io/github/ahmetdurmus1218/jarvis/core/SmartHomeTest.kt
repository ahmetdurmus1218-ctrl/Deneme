package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.home.Brew
import io.github.ahmetdurmus1218.jarvis.core.home.Foam
import io.github.ahmetdurmus1218.jarvis.core.home.HomeAction
import io.github.ahmetdurmus1218.jarvis.core.home.HomeCommands
import io.github.ahmetdurmus1218.jarvis.core.home.Room
import io.github.ahmetdurmus1218.jarvis.core.home.SmartHome
import io.github.ahmetdurmus1218.jarvis.core.home.Sugar
import io.github.ahmetdurmus1218.jarvis.core.home.Tone
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommandRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmartHomeTest {
    @Test
    fun turkishCommandsAreUnderstood() {
        assertEquals(HomeAction.SetLight(Room.SALON, on = true), HomeCommands.parse("Rick, salonun ışığını aç"))
        assertEquals(HomeAction.SetLight(Room.SALON, on = false), HomeCommands.parse("salon ışığını kapat"))
        assertEquals(HomeAction.SetLight(Room.STUDY, on = null, brightness = 30, tone = Tone.COLD), HomeCommands.parse("çalışma odasını yüzde otuz yap, soğuk beyaz"))
        assertEquals(HomeAction.AllLights(false), HomeCommands.parse("tüm ışıkları kapat"))
        assertEquals(HomeAction.MakeFilterCoffee(2), HomeCommands.parse("filtre kahveyi iki fincan demle"))
        assertEquals(HomeAction.MakeTurkishCoffee(Foam.LOTS, Sugar.MEDIUM), HomeCommands.parse("Rick, bana bol köpüklü bir Türk kahvesi yap"))
        assertEquals(HomeAction.MakeTurkishCoffee(Foam.SOME, Sugar.NONE), HomeCommands.parse("sade bir türk kahvesi yapar mısın"))
        assertEquals(HomeAction.CoffeeStatus, HomeCommands.parse("kahvem hazır mı?"))
        assertEquals(null, HomeCommands.parse("kahvenin tarihi nedir?"))
        assertEquals(null, HomeCommands.parse("ışık hızı kaç?"))
        // Reaches the router before the generic "… aç" app command.
        assertEquals(QuickCommand.Home(HomeAction.SetLight(Room.SALON, on = true)), QuickCommandRouter.route("salonun ışığını aç"))
        assertEquals(QuickCommand.OpenApp("whatsapp"), QuickCommandRouter.route("whatsapp aç"))
    }

    @Test
    fun devicesBehaveLikeTheRealThing() {
        val home = SmartHome()
        assertEquals("Salon ışığı açıldı.", home.apply(HomeAction.SetLight(Room.SALON, on = true), 0))
        assertEquals("Çalışma odası ışığı yüzde 30, soğuk beyaz.", home.apply(HomeAction.SetLight(Room.STUDY, brightness = 30, tone = Tone.COLD), 0))
        assertTrue(home.state(0).light(Room.STUDY).on, "setting brightness turns the light on")
        home.apply(HomeAction.AllLights(false), 0)
        assertTrue(home.state(0).lights.none { it.on })

        val said = home.apply(HomeAction.MakeTurkishCoffee(Foam.LOTS, Sugar.MEDIUM), 1_000)
        assertTrue("Bol köpüklü, orta şekerli" in said, said)
        assertEquals(Brew.BREWING, home.state(30_000).turkish.state)
        assertEquals("Türk kahveniz 46 saniye içinde hazır.", home.apply(HomeAction.CoffeeStatus, 30_000))
        assertTrue("zaten" in home.apply(HomeAction.MakeTurkishCoffee(Foam.NONE, Sugar.NONE), 31_000))
        assertEquals(Brew.READY, home.state(76_000).turkish.state)
        assertEquals("Türk kahveniz hazır, bol köpüklü.", home.apply(HomeAction.CoffeeStatus, 80_000))

        home.apply(HomeAction.MakeFilterCoffee(3), 100_000)
        assertEquals(Brew.BREWING, home.state(219_000).filter.state)
        val f = home.state(220_000).filter
        assertEquals(Brew.READY, f.state)
        assertTrue(f.keepWarm)
    }
}

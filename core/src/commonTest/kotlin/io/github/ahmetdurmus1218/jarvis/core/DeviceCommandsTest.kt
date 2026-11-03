package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction
import io.github.ahmetdurmus1218.jarvis.core.device.DeviceCommands
import io.github.ahmetdurmus1218.jarvis.core.device.MediaKey
import io.github.ahmetdurmus1218.jarvis.core.device.SettingsPanel
import io.github.ahmetdurmus1218.jarvis.core.device.VolumeChange
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommandRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceCommandsTest {
    private fun d(text: String) = (QuickCommandRouter.route(text) as? QuickCommand.Device)?.action

    @Test
    fun torchVolumeAndMusic() {
        assertEquals(DeviceAction.Torch(true), d("Feneri aç"))
        assertEquals(DeviceAction.Torch(false), d("Rick, el fenerini kapat"))
        assertEquals(DeviceAction.Volume(VolumeChange.UP), d("Sesi aç"))
        assertEquals(DeviceAction.Volume(VolumeChange.UP), d("sesi biraz artır"))
        assertEquals(DeviceAction.Volume(VolumeChange.DOWN), d("Sesi kıs"))
        assertEquals(DeviceAction.Volume(VolumeChange.MUTE), d("sesi kapat"))
        assertEquals(DeviceAction.Volume(VolumeChange.MAX), d("Sesi sonuna kadar aç"))
        assertEquals(DeviceAction.Volume(VolumeChange.SILENT_PHONE), d("Telefonu sessize al"))
        assertEquals(DeviceAction.Media(MediaKey.PAUSE), d("Müziği durdur"))
        assertEquals(DeviceAction.Media(MediaKey.PLAY), d("müzik çal"))
        assertEquals(DeviceAction.Media(MediaKey.NEXT), d("Sonraki şarkı"))
        assertEquals(DeviceAction.Media(MediaKey.NEXT), d("şarkıyı geç"))
        assertEquals(DeviceAction.Media(MediaKey.PREVIOUS), d("önceki şarkı"))
    }

    @Test
    fun panelsAndBattery() {
        assertEquals(DeviceAction.Panel(SettingsPanel.WIFI, true), d("Wi-Fi aç".replace("-", " ")))
        assertEquals(DeviceAction.Panel(SettingsPanel.WIFI, false), d("wifiyi kapat"))
        assertEquals(DeviceAction.Panel(SettingsPanel.BLUETOOTH, true), d("Bluetooth'u aç".replace("'", "")))
        assertEquals(DeviceAction.Panel(SettingsPanel.INTERNET, null), d("mobil veri ayarları"))
        assertEquals(DeviceAction.Panel(SettingsPanel.DISPLAY, null), d("parlaklığı ayarla"))
        assertEquals(DeviceAction.Battery, d("Şarjım kaç?"))
        assertEquals(DeviceAction.Battery, d("pil yüzde kaç"))
        assertEquals(DeviceAction.Battery, d("kaç şarjım kaldı"))
    }

    @Test
    fun callsAndMessagesAreOnlyPrepared() {
        assertEquals(DeviceAction.Call("05551234567"), d("0555 123 45 67'yi ara"))
        assertEquals(DeviceAction.Call("112"), d("112'yi ara"))
        assertEquals(DeviceAction.Call("Annem"), d("Annemi ara"))
        assertEquals(DeviceAction.Call("Ahmet"), d("Rick, Ahmet'i ara"))
        assertEquals(DeviceAction.Call("Ayşe"), d("Ayşeyi arar mısın"))
        assertEquals(DeviceAction.Message("Ahmet", "geliyorum, 10 dakikaya oradayım"), d("Ahmet'e mesaj at: geliyorum, 10 dakikaya oradayım"))
        assertEquals(DeviceAction.Message("annem", "Yoldayım"), d("anneme mesaj yaz Yoldayım"))
        assertEquals(DeviceAction.Message(null, "Akşam görüşürüz"), d("mesaj yaz: Akşam görüşürüz"))
    }

    @Test
    fun searchingIsNotCalling() {
        assertNull(d("Wikipedia'da kedi ara"))
        assertNull(d("beni ara"))
        assertNull(d("belgelerimde vergi ara"))
        assertNull(d("internette hava durumu ara"))
        assertNull(d("Ankara'da otel ara"))
    }

    @Test
    fun directions() {
        assertEquals(DeviceAction.Navigate("Ankara"), d("Ankara'ya yol tarifi"))
        assertEquals(DeviceAction.Navigate("Kızılay"), d("haritada Kızılay'ı göster"))
    }

    @Test
    fun existingCommandsStillWin() {
        assertEquals(QuickCommand.StopSpeaking, QuickCommandRouter.route("sus"))
        assertEquals(QuickCommand.StopSpeaking, QuickCommandRouter.route("sessize al"))
        assertEquals(QuickCommand.OpenApp("kamera"), QuickCommandRouter.route("kamerayı aç"))
        assertEquals(QuickCommand.TimeNow, QuickCommandRouter.route("saat kaç"))
        // The simulated home keeps its lights.
        assertEquals(true, QuickCommandRouter.route("salonun ışığını aç") is QuickCommand.Home)
    }

    @Test
    fun contactNames() {
        assertEquals("Babam", DeviceCommands.contactName("Babamı"))
        assertEquals("Ali", DeviceCommands.contactName("Aliyi"))
        assertEquals("Mehmet", DeviceCommands.contactName("Mehmet"))
        assertEquals("Deniz", DeviceCommands.contactName("Deniz'i"))
    }
}

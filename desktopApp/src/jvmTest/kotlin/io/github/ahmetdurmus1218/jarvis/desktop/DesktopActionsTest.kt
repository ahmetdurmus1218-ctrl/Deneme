package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction
import io.github.ahmetdurmus1218.jarvis.core.device.MediaKey
import io.github.ahmetdurmus1218.jarvis.core.device.SettingsPanel
import io.github.ahmetdurmus1218.jarvis.core.device.VolumeChange
import io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopActionsTest {
    private val commands = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val menu = Files.createTempDirectory("start-menu").toFile()

    private fun actions(batteryOut: String = "") = DesktopActions(
        shell = { cmd -> commands += cmd; WindowsFirewall.ShellResult(0, if ("Win32_Battery" in cmd) batteryOut else "") },
        isWindows = true,
        startMenus = { listOf(menu) },
        open = { opened += it },
    )

    @Test
    fun volumeAndMediaAreKeyPresses() {
        val a = actions()
        assertNull(a.device(DeviceAction.Volume(VolumeChange.UP)))
        assertTrue("[char]175" in commands.last() && "1..5" in commands.last(), commands.last())
        a.device(DeviceAction.Volume(VolumeChange.MUTE))
        assertTrue("[char]173" in commands.last())
        a.device(DeviceAction.Media(MediaKey.NEXT))
        assertTrue("[char]176" in commands.last())
        assertTrue(commands.none { "\"" in it }, "no double quotes in a -Command argument")
    }

    @Test
    fun settingsPagesAndPhoneOnlyThings() {
        val a = actions()
        assertNull(a.device(DeviceAction.Panel(SettingsPanel.BLUETOOTH, true)))
        assertEquals("ms-settings:bluetooth", opened.single())
        assertTrue(a.device(DeviceAction.Torch(true))!!.contains("fener"))
        assertTrue(a.device(DeviceAction.Call("05551234567"))!!.contains("telefon"))
    }

    @Test
    fun battery() {
        assertEquals("Pil yüzde 87.", actions("87\r\n").device(DeviceAction.Battery))
        assertTrue(actions("").device(DeviceAction.Battery)!!.contains("pil görünmüyor"))
    }

    @Test
    fun programsFromTheStartMenu() {
        File(menu, "Spotify.lnk").writeText("")
        File(menu, "Microsoft Office/Word.lnk").apply { parentFile.mkdirs() }.writeText("")
        File(menu, "Microsoft Office/Word Uninstall.lnk").writeText("")
        File(menu, "Google Chrome.lnk").writeText("")
        val a = actions()
        assertNull(a.openApp("Spotify"))
        assertTrue(opened.last().endsWith("Spotify.lnk"))
        assertNull(a.openApp("word"))
        assertTrue(opened.last().endsWith("Word.lnk"), opened.last())
        assertNull(a.openApp("chrome"))
        assertTrue(opened.last().endsWith("Google Chrome.lnk"))
        assertNull(a.openApp("Not Defteri"))
        assertEquals("notepad.exe", opened.last())
        assertTrue(a.openApp("olmayan program")!!.contains("bulamadım"))
    }

    @Test
    fun alarmRingsAtTheNextOccurrence() {
        val a = actions()
        assertEquals(30, a.setAlarm(8, 30, LocalDateTime.of(2026, 11, 3, 8, 0)))
        assertEquals(23 * 60 + 30, a.setAlarm(7, 30, LocalDateTime.of(2026, 11, 3, 8, 0)), "already passed today: tomorrow")
    }

    @Test
    fun aTimerCallsBack() {
        val a = actions()
        val said = java.util.concurrent.LinkedBlockingQueue<String>()
        a.onReminder = { said += it }
        a.startTimer(1, "Zamanlayıcı")
        assertEquals("Zamanlayıcı bitti efendim.", said.poll(5, java.util.concurrent.TimeUnit.SECONDS))
    }
}

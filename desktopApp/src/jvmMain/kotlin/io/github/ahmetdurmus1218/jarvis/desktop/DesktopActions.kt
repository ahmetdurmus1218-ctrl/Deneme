package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction
import io.github.ahmetdurmus1218.jarvis.core.device.MediaKey
import io.github.ahmetdurmus1218.jarvis.core.device.SettingsPanel
import io.github.ahmetdurmus1218.jarvis.core.device.VolumeChange
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall
import java.io.File
import java.time.LocalDateTime
import java.util.Timer
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.schedule

/**
 * What the PC can do at once, without the language model: volume and media keys, Windows settings
 * pages, the battery, opening programs from the Start menu, timers and alarms (while Rick is open).
 * Nothing here touches the network; every program is started by the user's own request.
 */
class DesktopActions(
    private val shell: (String) -> WindowsFirewall.ShellResult = WindowsFirewall::powershell,
    private val isWindows: Boolean = WindowsFirewall.isWindows,
    /** Start menu folders (shortcuts of installed programs). */
    private val startMenus: () -> List<File> = ::defaultStartMenus,
    /** Opens a file / shortcut / URI the way Explorer would. */
    private val open: (String) -> Unit = ::shellOpen,
    /** Called when a timer or an alarm goes off, with the sentence to say. */
    var onReminder: (String) -> Unit = {},
) {
    private val timer = Timer("rick-reminders", true)

    fun device(a: DeviceAction): String? {
        if (!isWindows) return "Bu komut yalnızca Windows'ta çalışıyor."
        return when (a) {
            is DeviceAction.Torch -> "Bilgisayarda fener yok."
            is DeviceAction.Volume -> {
                when (a.change) {
                    VolumeChange.UP -> keys(VOLUME_UP, 5)
                    VolumeChange.DOWN -> keys(VOLUME_DOWN, 5)
                    VolumeChange.MUTE, VolumeChange.SILENT_PHONE -> keys(VOLUME_MUTE, 1)
                    VolumeChange.MAX -> keys(VOLUME_UP, 50)
                }
            }
            is DeviceAction.Media -> keys(
                when (a.key) {
                    MediaKey.PLAY, MediaKey.PAUSE -> MEDIA_PLAY_PAUSE
                    MediaKey.NEXT -> MEDIA_NEXT
                    MediaKey.PREVIOUS -> MEDIA_PREVIOUS
                },
                1,
            )
            is DeviceAction.Panel -> runCatching { open(settingsUri(a.panel)) }.exceptionOrNull()?.let { "Ayar sayfası açılamadı: ${it.message}" }
            DeviceAction.Battery -> battery()?.let { "Pil yüzde $it." } ?: "Bu bilgisayarda pil görünmüyor; prize bağlı çalışıyor."
            is DeviceAction.Call, is DeviceAction.Message -> "Arama ve mesaj bilgisayardan yapılamıyor; telefondaki Rick'e söyleyin."
            is DeviceAction.Navigate -> "Bilgisayarda internetsiz harita yok; telefondaki Rick yol tarifini harita uygulamasında açabilir."
        }
    }

    /** Virtual-key presses through Windows Script Host: volume and media keys, no extra program. */
    private fun keys(vk: Int, times: Int): String? {
        val r = shell("\$w = New-Object -ComObject WScript.Shell; 1..$times | ForEach-Object { \$w.SendKeys([char]$vk) }")
        return if (r.exit == 0) null else "Tuş gönderilemedi."
    }

    // ---- battery (cached: Mark 2 asks every few seconds and PowerShell takes ~a second) ----
    private val batteryCache = AtomicReference<Pair<Long, Int?>?>(null)

    fun battery(): Int? {
        if (!isWindows) return null
        val now = System.currentTimeMillis()
        batteryCache.get()?.let { (at, v) -> if (now - at < 60_000) return v }
        val v = shell("(Get-CimInstance Win32_Battery | Select-Object -First 1).EstimatedChargeRemaining").out.trim().toIntOrNull()?.takeIf { it in 0..100 }
        batteryCache.set(now to v)
        return v
    }

    @Volatile private var refreshing = false

    /** Last known value without waiting; refreshes in the background when stale. */
    fun batteryCached(): Int? {
        if (!isWindows) return null
        val c = batteryCache.get()
        if ((c == null || System.currentTimeMillis() - c.first > 60_000) && !refreshing) {
            refreshing = true
            Thread({ try { battery() } finally { refreshing = false } }, "rick-battery").apply { isDaemon = true }.start()
        }
        return c?.second
    }

    // ---- programs ----

    /** Opens a program by what the user called it: well-known Windows tools, else a Start menu shortcut. */
    fun openApp(name: String): String? {
        if (!isWindows) return "Bu komut yalnızca Windows'ta çalışıyor."
        val n = Turkish.normalize(name)
        BUILT_IN[n]?.let { target ->
            return runCatching { open(target) }.exceptionOrNull()?.let { "\"$name\" açılamadı." }
        }
        val shortcut = findShortcut(n) ?: return "\"$name\" adında bir program bulamadım."
        return runCatching { open(shortcut.absolutePath) }.exceptionOrNull()?.let { "\"$name\" açılamadı." }
    }

    /** The Start menu shortcut whose name best matches (exact, then starts with, then contains). */
    fun findShortcut(normalizedName: String): File? {
        val all = startMenus().filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().maxDepth(4).filter { it.isFile && it.extension.equals("lnk", true) }.toList()
        }
        // Uninstallers and help links are never what "open X" means.
        val usable = all.filterNot { Regex("(?i)uninstall|kaldır|readme|help|yardım|website").containsMatchIn(it.nameWithoutExtension) }
        fun key(f: File) = Turkish.normalize(f.nameWithoutExtension)
        return usable.firstOrNull { key(it) == normalizedName }
            ?: usable.filter { key(it).startsWith(normalizedName) }.minByOrNull { key(it).length }
            ?: usable.filter { normalizedName.length >= 3 && key(it).contains(normalizedName) }.minByOrNull { key(it).length }
    }

    // ---- timers and alarms (in the app: they ring while Rick is open) ----

    fun startTimer(seconds: Int, label: String) {
        timer.schedule(seconds * 1000L) { onReminder("$label bitti efendim.") }
    }

    /** Next occurrence of [hour]:[minute] (today, or tomorrow if that time has passed). Returns minutes until then. */
    fun setAlarm(hour: Int, minute: Int, now: LocalDateTime = LocalDateTime.now()): Long {
        var at = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val ms = java.time.Duration.between(now, at).toMillis()
        timer.schedule(ms) { onReminder("Saat ${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}, alarm efendim.") }
        return ms / 60_000
    }

    companion object {
        const val VOLUME_MUTE = 173
        const val VOLUME_DOWN = 174
        const val VOLUME_UP = 175
        const val MEDIA_NEXT = 176
        const val MEDIA_PREVIOUS = 177
        const val MEDIA_PLAY_PAUSE = 179

        fun settingsUri(p: SettingsPanel): String = "ms-settings:" + when (p) {
            SettingsPanel.WIFI -> "network-wifi"
            SettingsPanel.BLUETOOTH -> "bluetooth"
            SettingsPanel.INTERNET -> "network-status"
            SettingsPanel.LOCATION -> "privacy-location"
            SettingsPanel.AIRPLANE -> "network-airplanemode"
            SettingsPanel.DISPLAY -> "display"
            SettingsPanel.BATTERY_SAVER -> "batterysaver"
            SettingsPanel.DND -> "quiethours"
            SettingsPanel.SOUND -> "sound"
        }

        /** Windows' own tools by their Turkish (and common) names. */
        val BUILT_IN: Map<String, String> = mapOf(
            "not defteri" to "notepad.exe", "notepad" to "notepad.exe",
            "hesap makinesi" to "calc.exe", "hesap makinasi" to "calc.exe",
            "dosya gezgini" to "explorer.exe", "gezgin" to "explorer.exe", "dosyalar" to "explorer.exe", "bilgisayarim" to "explorer.exe",
            "ayarlar" to "ms-settings:", "paint" to "mspaint.exe", "boya" to "mspaint.exe",
            "gorev yoneticisi" to "taskmgr.exe", "denetim masasi" to "control.exe",
            "ekran alintisi" to "ms-screenclip:", "ekran goruntusu" to "ms-screenclip:", "kamera" to "microsoft.windows.camera:",
            "takvim" to "outlookcal:", "saat" to "ms-clock:", "alarm" to "ms-clock:", "alarmlar" to "ms-clock:",
            "muzik" to "mswindowsmusic:", "fotograflar" to "ms-photos:", "galeri" to "ms-photos:", "resimler" to "ms-photos:",
            "magaza" to "ms-windows-store:",
        )

        fun defaultStartMenus(): List<File> = listOfNotNull(
            System.getenv("ProgramData")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
            System.getenv("APPDATA")?.let { File(it, "Microsoft/Windows/Start Menu/Programs") },
        )

        /** Explorer's "open" for a program name, a shortcut or a URI scheme; the target is a single-quoted literal. */
        fun shellOpen(target: String) {
            val r = WindowsFirewall.powershell("Start-Process -FilePath '" + target.replace("'", "''") + "'")
            check(r.exit == 0) { r.out.trim().take(200) }
        }
    }
}

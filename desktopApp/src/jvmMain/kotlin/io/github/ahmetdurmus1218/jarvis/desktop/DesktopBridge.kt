package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.desktop.kapi.DesktopKapi
import io.github.ahmetdurmus1218.jarvis.ui.AppFiles
import io.github.ahmetdurmus1218.jarvis.ui.ApprovalMethod
import io.github.ahmetdurmus1218.jarvis.ui.ApprovalResult
import io.github.ahmetdurmus1218.jarvis.ui.Approver
import io.github.ahmetdurmus1218.jarvis.ui.DeviceInfo
import io.github.ahmetdurmus1218.jarvis.ui.ImportOutcome
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
import io.github.ahmetdurmus1218.jarvis.ui.PlatformBridge
import io.github.ahmetdurmus1218.jarvis.ui.SpeechInput
import io.github.ahmetdurmus1218.jarvis.ui.SpeechOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.lang.management.ManagementFactory
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/** %LOCALAPPDATA%\Jarvis on Windows, ~/.local/share/Jarvis elsewhere. */
fun dataDir(): File {
    val local = System.getenv("LOCALAPPDATA")
    val base = if (!local.isNullOrBlank()) File(local) else File(System.getProperty("user.home"), ".local/share")
    return File(base, "Jarvis").apply { mkdirs() }
}

class DesktopFiles(private val dir: File) : AppFiles {
    init { dir.mkdirs() }
    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()
    override fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
    override fun append(name: String, line: String) = File(dir, name).appendText(line + "\n")
}

class DesktopModelStorage(private val dir: File, private val partialDir: File) : ModelStorage {
    private val unverifiedDir = File(dir, "dogrulanmamis")

    init { dir.mkdirs() }

    private fun file(item: LockedItem) = File(dir, item.fileName)
    private fun marker(item: LockedItem) = File(dir, item.fileName + ".verified")

    override fun isInstalled(item: LockedItem): Boolean {
        val f = file(item)
        val m = marker(item)
        return f.isFile && f.length() == item.size && m.isFile && m.readText().trim().equals(item.sha256, ignoreCase = true)
    }

    override fun pathOf(item: LockedItem): String = file(item).absolutePath

    override fun commit(item: LockedItem, tempPath: String) {
        val src = File(tempPath)
        val dst = file(item)
        dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
        marker(item).writeText(item.sha256)
    }

    override fun delete(item: LockedItem) {
        file(item).delete()
        marker(item).delete()
    }

    override fun deleteTemp(tempPath: String) {
        File(tempPath).delete()
    }

    override fun importedUnverifiedFiles(): List<String> = unverifiedDir.listFiles()?.map { it.name }.orEmpty().sorted()

    override fun keepUnverified(tempPath: String, fileName: String) {
        unverifiedDir.mkdirs()
        val dst = File(unverifiedDir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val src = File(tempPath)
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
        }
    }

    override suspend fun pickAndImport(): ImportOutcome {
        val chosen: File = suspendCancellableCoroutine { cont ->
            SwingUtilities.invokeLater {
                val dialog = FileDialog(null as Frame?, "Model dosyası seçin", FileDialog.LOAD)
                dialog.isVisible = true
                val f = dialog.files.firstOrNull()
                if (cont.isActive) cont.resume(f ?: File(""))
            }
        }
        if (chosen.path.isEmpty()) return ImportOutcome.Cancelled
        return withContext(Dispatchers.IO) {
            try {
                partialDir.mkdirs()
                val tmp = File(partialDir, "import-" + System.nanoTime())
                chosen.copyTo(tmp, overwrite = true)
                val (sha, size) = DesktopKapi.sha256(tmp)
                ImportOutcome.Imported(tmp.absolutePath, chosen.name, sha, size)
            } catch (e: Exception) {
                ImportOutcome.Failed(e.message ?: "İçe aktarma başarısız")
            }
        }
    }
}

/**
 * Windows SAPI voice through PowerShell (System.Speech), used only if a Turkish (tr-TR) voice is
 * installed. The text goes through stdin, never through the command line. Used until Piper is installed.
 */
class WindowsSapiSpeech : SpeechOutput {
    override val engineName = "Windows konuşma (SAPI, tr-TR)"
    @Volatile private var current: Process? = null

    override suspend fun speak(text: String, rate: Float) = withContext(Dispatchers.IO) {
        val sapiRate = ((rate - 1f) * 10f).toInt().coerceIn(-10, 10)
        val script = "Add-Type -AssemblyName System.Speech; " +
            "\$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
            "\$v = \$s.GetInstalledVoices() | Where-Object { \$_.VoiceInfo.Culture.Name -eq 'tr-TR' } | Select-Object -First 1; " +
            "if (-not \$v) { exit 3 }; \$s.SelectVoice(\$v.VoiceInfo.Name); \$s.Rate = $sapiRate; " +
            "[Console]::InputEncoding = [Text.Encoding]::UTF8; \$s.Speak([Console]::In.ReadToEnd())"
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)
            .redirectErrorStream(true)
            .start()
        current = p
        p.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        p.inputStream.readBytes()
        p.waitFor()
        current = null
    }

    override fun stop() {
        current?.destroy()
        current = null
    }

    companion object {
        fun availableOrNull(): SpeechOutput? =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) WindowsSapiSpeech() else null
    }
}

class DesktopBridge(override val appVersion: String) : PlatformBridge {
    private val root = dataDir()
    private val partial = File(root, "models/.partial")
    override val platform = Platform.DESKTOP
    override val files: AppFiles = DesktopFiles(File(root, "app"))
    override val models: ModelStorage = DesktopModelStorage(File(root, "models"), partial)
    /** The installed launcher (jpackage sets this); null when run from Gradle or in tests. */
    private val appExe: File? = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }
    private val kapiExe: File? = appExe?.let { File(it.parentFile, "jarvis-kapi.exe") }?.takeIf { it.isFile }
    private val gateDir = File(root, "gate")
    /** Windows firewall automation, only for the installed app (it needs the two launchers' paths). */
    val windowsFirewall: io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall? =
        if (io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall.isWindows && kapiExe != null) io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall() else null
    private val kapiRule get() = io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall.Rule("jarvis-kapi.exe", kapiExe!!)

    /** Every program Jarvis runs: the app itself, the download launcher and the unpacked servers. */
    fun firewallRules(): List<io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall.Rule> {
        val app = appExe ?: return emptyList()
        val kapi = kapiExe ?: return emptyList()
        val bins = File(root, "bin").walkTopDown().filter { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
            .map { io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall.Rule("bin " + it.relativeTo(File(root, "bin")).path.replace('\\', '/'), it) }
        return listOf(
            io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall.Rule("Jarvis.exe", app),
            io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall.Rule("jarvis-kapi.exe", kapi),
        ) + bins
    }

    override val downloader: io.github.ahmetdurmus1218.jarvis.ui.Downloader = if (kapiExe != null) {
        io.github.ahmetdurmus1218.jarvis.desktop.kapi.KapiProcessDownloader(kapiExe, partial) { installed ->
            // Unpack downloaded programs now, so the gate helper blocks them before it closes.
            val fw = windowsFirewall
            installed.filter { it.endsWith(".zip", ignoreCase = true) }.forEach { zip ->
                runCatching { io.github.ahmetdurmus1218.jarvis.desktop.llm.LlamaServerEngine.unpackOnce(File(zip), File(root, "bin")) }
            }
            if (fw != null) fw.closeGate(gateDir, firewallRules().drop(2))
        }
    } else {
        DesktopKapi(partial)
    }

    override val firewall: io.github.ahmetdurmus1218.jarvis.ui.FirewallControl? = windowsFirewall?.let { fw ->
        object : io.github.ahmetdurmus1218.jarvis.ui.FirewallControl {
            override fun status(): String {
                val state = fw.state()
                val rules = firewallRules()
                val on = rules.count { state[fw.displayName(it)] == true }
                return if (on == rules.size) "Açık: Jarvis ve kullandığı ${rules.size} programın internete çıkışı engelli." else "Eksik: ${rules.size} kuraldan $on tanesi etkin."
            }
            override fun installed(): Boolean = fw.isInstalled(firewallRules())
            override suspend fun install(): Boolean = fw.install(firewallRules())
            override suspend fun remove(): Boolean = fw.uninstall()
        }
    }

    /** First start of the installed app: one UAC prompt to put the block rules in place. */
    suspend fun firstRunFirewall() {
        val fw = firewall ?: return
        val asked = File(root, "app/firewall.asked")
        if (asked.isFile) return
        asked.parentFile.mkdirs()
        asked.writeText("1")
        if (!fw.installed()) fw.install()
    }
    override suspend fun pickDocument(): io.github.ahmetdurmus1218.jarvis.ui.DocumentPick = DesktopDocuments.pick()
    override suspend fun screenshotJpeg(): ByteArray? = DesktopVision.screenshot()
    override suspend fun pickImageJpeg(): ByteArray? = DesktopVision.pickImage()

    /** Offline Wikipedia through a local kiwix-serve, once the ZIM and kiwix-tools are installed. */
    override val zim = io.github.ahmetdurmus1218.jarvis.desktop.knowledge.KiwixServe.forInstalled(
        models, io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry.bundled, File(root, "bin"),
    )
    /** Jarvis's own Whisper; until its files are installed, the chat offers the download. */
    override val speechInput: SpeechInput = io.github.ahmetdurmus1218.jarvis.desktop.stt.DesktopWhisperSpeechInput(models, io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry.bundled)
    override val micStream = io.github.ahmetdurmus1218.jarvis.desktop.stt.DesktopMicStream()
    override val cipher = DesktopCipher(File(root, "app/key.dpapi"))
    /** Piper (the same Turkish voice as the phone) once installed; Windows SAPI tr-TR until then. */
    override val speechOutput: SpeechOutput = io.github.ahmetdurmus1218.jarvis.desktop.tts.DesktopPiperSpeech(
        models, io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry.bundled, WindowsSapiSpeech.availableOrNull(),
    ).also { it.warmUp() }

    override fun deviceInfo(): DeviceInfo {
        val os = ManagementFactory.getOperatingSystemMXBean()
        val total = (os as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: 0L
        return DeviceInfo(
            label = "${System.getProperty("os.name")} ${System.getProperty("os.version")} · ${Runtime.getRuntime().availableProcessors()} çekirdek",
            totalRamGb = total / 1_073_741_824.0,
            freeStorageBytes = root.usableSpace,
            notes = listOf(
                "Önerilen model: Qwen 3.5 9B (Q4_K_M), Vulkan ile GPU'da.",
                "Ses: Whisper (dinleme) ve Piper Türkçe ses (konuşma), ikisi de bilgisayarda çalışır.",
            ),
        )
    }

    /** v0.1: a native confirmation dialog. v0.5: UAC-elevated firewall rule for jarvis-kapi. */
    override val approver = object : Approver {
        override suspend fun confirm(title: String, subtitle: String): ApprovalResult {
            // With the firewall in place the approval IS the UAC prompt that opens the kapi gate.
            val fw = windowsFirewall
            if (fw != null && kapiExe != null && fw.state()[fw.displayName(kapiRule)] == true) {
                return if (fw.openGate(kapiRule, gateDir)) ApprovalResult.Approved(ApprovalMethod.UAC)
                else ApprovalResult.Rejected("UAC onayı verilmedi")
            }
            return dialog(title, subtitle)
        }

        private suspend fun dialog(title: String, subtitle: String): ApprovalResult = suspendCancellableCoroutine { cont ->
            SwingUtilities.invokeLater {
                val r = JOptionPane.showConfirmDialog(null, "$subtitle\n\nBu tek seferlik izni veriyor musunuz?", title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
                if (cont.isActive) cont.resume(if (r == JOptionPane.YES_OPTION) ApprovalResult.Approved(ApprovalMethod.DIALOG) else ApprovalResult.Rejected("Reddedildi"))
            }
        }
    }

    override suspend fun execute(command: QuickCommand): String? = when (command) {
        is QuickCommand.SetAlarm, is QuickCommand.StartTimer -> "Alarm ve zamanlayıcı bu sürümde yalnızca telefonda çalışıyor."
        is QuickCommand.OpenApp -> "Bilgisayarda uygulama açma henüz desteklenmiyor."
        else -> null
    }
}

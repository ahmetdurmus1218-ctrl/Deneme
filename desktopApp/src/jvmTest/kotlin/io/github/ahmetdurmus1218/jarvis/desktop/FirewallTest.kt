package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.desktop.firewall.WindowsFirewall
import io.github.ahmetdurmus1218.jarvis.desktop.kapi.KapiProcessDownloader
import io.github.ahmetdurmus1218.jarvis.ui.DownloadOutcome
import io.github.ahmetdurmus1218.jarvis.ui.DownloadProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirewallTest {
    @Test
    fun kapiOutputIsParsed() {
        val seen = mutableListOf<DownloadProgress>()
        assertEquals(null, KapiProcessDownloader.parseLine("P 10 100") { seen += it })
        assertEquals(DownloadProgress(10, 100), seen.single())
        assertEquals(DownloadOutcome.Done("C:\\Users\\a b\\x.part", 5, "ab12", "huggingface.co"), KapiProcessDownloader.parseLine("DONE 5 ab12 huggingface.co C:\\Users\\a b\\x.part") {})
        assertEquals(DownloadOutcome.Failed("HTTP 404 bulunamadı", 7), KapiProcessDownloader.parseLine("FAIL 7 HTTP 404 bulunamadı") {})
        assertEquals(null, KapiProcessDownloader.parseLine("random noise") {})
    }

    @Test
    fun scriptsQuotePathsSafely() {
        val fw = WindowsFirewall("Jarvis") { WindowsFirewall.ShellResult(0, "") }
        val s = fw.installScript(listOf(WindowsFirewall.Rule("x", File("C:\\Users\\O'Brien\\Jarvis.exe"))))
        assertTrue("O''Brien\\Jarvis.exe'" in s, s) // on Linux the Windows path is relative, so only its end is checked
        assertTrue("-Direction Outbound -Action Block" in s)
    }

    /**
     * The real thing on a Windows runner (JARVIS_FIREWALL_TEST=1): a copy of curl is blocked by a rule
     * made exactly as Jarvis makes them, the kapi gate lets it out, closing the gate blocks it again
     * (and also blocks a newly added program), and the time limit closes a forgotten gate by itself.
     */
    @Test
    fun blockedProgramsCannotReachTheInternet() = runBlocking {
        assumeTrue(WindowsFirewall.isWindows && System.getenv("JARVIS_FIREWALL_TEST") == "1")
        val dir = Files.createTempDirectory("jarvis-fw").toFile()
        val curl = File(dir, "blocked-curl.exe").also { File("C:\\Windows\\System32\\curl.exe").copyTo(it) }
        val curl2 = File(dir, "new-program.exe").also { File("C:\\Windows\\System32\\curl.exe").copyTo(it) }
        fun reach(exe: File): Boolean {
            val p = ProcessBuilder(exe.path, "-s", "-o", "NUL", "-w", "%{http_code}", "--max-time", "12", "https://github.com").redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().decodeToString()
            p.waitFor(30, TimeUnit.SECONDS)
            return p.exitValue() == 0 && out.trim().startsWith("200")
        }
        println("firewall profiles: " + WindowsFirewall.powershell("(Get-NetFirewallProfile | ForEach-Object { \$_.Name + '=' + \$_.Enabled }) -join ' '").out.trim())
        assertTrue(reach(curl), "baseline: the copy can connect before any rule")
        val fw = WindowsFirewall(prefix = "JarvisTest")
        val rule = WindowsFirewall.Rule("curl", curl)
        val gate = File(dir, "gate")
        try {
            assertTrue(fw.install(listOf(rule)), "rules installed")
            assertFalse(reach(curl), "blocked by the rule")

            assertTrue(fw.openGate(rule, gate), "gate opened")
            assertTrue(reach(curl), "allowed while the gate is open")
            assertTrue(fw.closeGate(gate, listOf(WindowsFirewall.Rule("new-program", curl2))), "gate closed")
            assertFalse(reach(curl), "blocked again after the session")
            assertFalse(reach(curl2), "the program added in the session is blocked too")

            assertTrue(fw.openGate(rule, gate, seconds = 6), "gate opened with a short limit")
            Thread.sleep(12_000)
            assertTrue(File(gate, "gate.closed").isFile, "the helper closed the gate by itself")
            assertFalse(reach(curl), "a forgotten gate closes after its time limit")
            println("firewall test: block, gate, re-block, new program, time limit all OK")
        } finally {
            fw.uninstall()
        }
        assertTrue(fw.state().isEmpty(), "rules removed")
    }
}

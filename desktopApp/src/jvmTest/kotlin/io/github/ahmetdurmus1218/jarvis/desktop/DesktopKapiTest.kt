package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.desktop.kapi.DesktopKapi
import io.github.ahmetdurmus1218.jarvis.desktop.kapi.PinningTrust
import io.github.ahmetdurmus1218.jarvis.ui.DownloadOutcome
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopKapiTest {
    private fun item(url: String, name: String, kind: String = "llm") =
        LockedItem("x", kind, listOf("desktop"), "X", "g", url, "h", name, 10, "a".repeat(64), "r", "o")

    @Test
    fun refusesBeforeAnyNetworkRequest() = runTest {
        val kapi = DesktopKapi(Files.createTempDirectory("kapi").toFile())
        assertIs<DownloadOutcome.Failed>(kapi.download(item("https://evil.example/x.gguf", "x.gguf")) {})
        assertIs<DownloadOutcome.Failed>(kapi.download(item("http://huggingface.co/x.gguf", "x.gguf")) {})
        assertIs<DownloadOutcome.Failed>(kapi.download(item("https://huggingface.co/x.exe", "x.exe", "binary")) {})
    }

    @Test
    fun everyAllowedHostHasPins() {
        listOf("huggingface.co", "us.aws.cdn.hf.co", "download.kiwix.org", "mirror.download.kiwix.org", "github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com").forEach {
            assertTrue(PinningTrust.pinsFor(it).isNotEmpty(), it)
        }
        assertEquals(emptySet(), PinningTrust.pinsFor("evil.example"))
    }

    @Test
    fun sha256OfFile() {
        val f = Files.createTempFile("h", ".txt").toFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" to 3L, DesktopKapi.sha256(f))
    }
}

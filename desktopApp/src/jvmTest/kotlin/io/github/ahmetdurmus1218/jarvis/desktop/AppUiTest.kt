package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.Platform
import io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand
import io.github.ahmetdurmus1218.jarvis.ui.AppFiles
import io.github.ahmetdurmus1218.jarvis.ui.AppGraph
import io.github.ahmetdurmus1218.jarvis.ui.ApprovalMethod
import io.github.ahmetdurmus1218.jarvis.ui.ApprovalResult
import io.github.ahmetdurmus1218.jarvis.ui.Approver
import io.github.ahmetdurmus1218.jarvis.ui.DeviceInfo
import io.github.ahmetdurmus1218.jarvis.ui.DownloadOutcome
import io.github.ahmetdurmus1218.jarvis.ui.DownloadProgress
import io.github.ahmetdurmus1218.jarvis.ui.DownloadState
import io.github.ahmetdurmus1218.jarvis.ui.Downloader
import io.github.ahmetdurmus1218.jarvis.ui.ImportOutcome
import io.github.ahmetdurmus1218.jarvis.ui.JarvisApp
import io.github.ahmetdurmus1218.jarvis.ui.ModelStorage
import io.github.ahmetdurmus1218.jarvis.ui.PlatformBridge
import io.github.ahmetdurmus1218.jarvis.ui.SpeechInput
import io.github.ahmetdurmus1218.jarvis.ui.SpeechOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * End-to-end walk through the shared UI on the desktop target, with the mock LLM:
 * wizard → chat (quick command, tool call, knowledge question) → models → approval → network log.
 * Screenshots go to build/ui-screens (CI prints them and uploads them as an artifact).
 */
class AppUiTest {
    @get:Rule
    val rule = createComposeRule()

    private class MemFiles : AppFiles {
        val m = mutableMapOf<String, String>()
        override fun read(name: String) = m[name]
        override fun write(name: String, text: String) { m[name] = text }
        override fun append(name: String, line: String) { m[name] = m[name].orEmpty() + line + "\n" }
    }

    private class UiTestBridge : PlatformBridge {
        override val platform = Platform.DESKTOP
        override val appVersion = "ui-test"
        override fun deviceInfo() = DeviceInfo("Test PC · Windows 11", 16.0, 500_000_000_000L, listOf("Önerilen model: Qwen 3.5 9B"))
        override val files: AppFiles = MemFiles()
        override val speechInput: SpeechInput? = null
        override val speechOutput: SpeechOutput? = null
        override val approver = object : Approver {
            override suspend fun confirm(title: String, subtitle: String) = ApprovalResult.Approved(ApprovalMethod.DIALOG)
        }
        override val downloader = object : Downloader {
            override val state: StateFlow<DownloadState> = MutableStateFlow(DownloadState.Ready("jarvis-kapi (test)"))
            override fun refresh() {}
            override suspend fun download(item: LockedItem, onProgress: (DownloadProgress) -> Unit) = DownloadOutcome.Failed("test")
            override fun cancel() {}
        }
        override val models = object : ModelStorage {
            override fun isInstalled(item: LockedItem) = false
            override fun pathOf(item: LockedItem) = ""
            override fun commit(item: LockedItem, tempPath: String) {}
            override fun delete(item: LockedItem) {}
            override fun deleteTemp(tempPath: String) {}
            override fun importedUnverifiedFiles() = emptyList<String>()
            override fun keepUnverified(tempPath: String, fileName: String) {}
            override suspend fun pickAndImport(): ImportOutcome = ImportOutcome.Cancelled
        }
        override suspend fun execute(command: QuickCommand): String? = null
    }

    private val outDir = File("build/ui-screens").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        val img = rule.onNodeWithTag("app").captureToImage().toAwtImage()
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        ImageIO.write(rgb, "jpg", File(outDir, "$name.jpg"))
    }

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ask(text: String) {
        rule.onNode(hasSetTextAction()).performTextInput(text)
        rule.onNodeWithText("Gönder").performClick()
    }

    @Test
    fun walkThroughTheApp() {
        // No artificial token delay: the test clock does not have to advance through the mock's pauses.
        val graph = AppGraph(UiTestBridge(), MockLlmEngine(tokenDelayMs = 0))
        rule.setContent {
            Box(Modifier.size(420.dp, 760.dp).testTag("app")) {
                JarvisApp(graph) { "2 Kas 2026 14:05" }
            }
        }

        // 1. First-run wizard
        waitForText("Kurulum sihirbazı")
        waitForText("1/4 · Cihaz kontrolü")
        shot("01-sihirbaz-cihaz")
        rule.onNodeWithText("Devam").performClick()
        waitForText("2/4 · Önerilen paket")
        waitForText("Qwen 3.5 9B Q4_K_M (GGUF)")
        shot("02-sihirbaz-paket")
        rule.onNodeWithText("İndirmeden devam et").performClick()
        waitForText("4/4 · Ses testi")
        rule.onNodeWithText("Bitir").performClick()
        rule.onNodeWithText("Sohbete geç").performClick()

        // 2. Chat: quick command, tool call with a verified number, knowledge question without sources
        waitForText("Gönder")
        ask("Saat kaç?")
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("Saat ", substring = true)).fetchSemanticsNodes().size >= 2
        }
        ask("12 çarpı 7 sonucunu söyler misin")
        waitForText("Sonuç 84 efendim.")
        waitForText("84")
        shot("03-sohbet-hesap")
        ask("Atatürk kimdir?")
        waitForText("Bu bilgi kütüphanemde yok.")
        waitForText("kaynak yok")
        shot("04-sohbet-kaynak-yok")

        // 3. Models → approval screen → reject
        rule.onNodeWithText("Modeller").performClick()
        waitForText("İndirme yolu: jarvis-kapi (test)")
        shot("05-modeller")
        rule.onAllNodesWithText("İndir")[0].performClick()
        waitForText("İnternet erişimi onayı")
        waitForText("huggingface.co", substring = true)
        shot("06-onay-ekrani")
        rule.onNodeWithText("Reddet").performClick()
        waitForText("Onay verilmedi: Onay ekranında reddedildi")

        // 4. Network log shows the refused request; nothing was downloaded
        rule.onNodeWithText("Ağ").performClick()
        waitForText("Sonuç: reddedildi", substring = true)
        shot("07-ag-kayit-defteri")

        // 5. Settings
        rule.onNodeWithText("Ayarlar").performClick()
        waitForText("Tam offline mod (tüm indirmeler kapalı)")
        shot("08-ayarlar")
    }
}

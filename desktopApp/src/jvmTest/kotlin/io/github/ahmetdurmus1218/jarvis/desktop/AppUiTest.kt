package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
/** The app's bundled files (assets/), from the test classpath. */
internal fun testImage(path: String): androidx.compose.ui.graphics.ImageBitmap? =
    AppUiTest::class.java.classLoader.getResourceAsStream(path)?.use {
        androidx.compose.ui.graphics.toComposeImageBitmap(org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()))
    }

internal fun testText(path: String): String? =
    AppUiTest::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

class AppUiTest {
    @get:Rule
    val rule = createComposeRule()

    private class MemFiles : AppFiles {
        val m = mutableMapOf<String, String>()
        override fun read(name: String) = m[name]
        override fun write(name: String, text: String) { m[name] = text }
        override fun append(name: String, line: String) { m[name] = m[name].orEmpty() + line + "\n" }
    }

    internal class UiTestBridge(private val mic: SpeechInput? = null) : PlatformBridge {
        override val platform = Platform.DESKTOP
        override val appVersion = "ui-test"
        override fun bundledImage(path: String) = testImage(path)
        override fun bundledText(path: String) = testText(path)
        override fun deviceInfo() = DeviceInfo("Test PC · Windows 11", 16.0, 500_000_000_000L, listOf("Önerilen model: Qwen 3.5 9B"))
        override val files: AppFiles = MemFiles()
        override val speechInput: SpeechInput? get() = mic
        override val speechOutput: SpeechOutput? = null
        override val approver = object : Approver {
            override suspend fun confirm(title: String, subtitle: String) = ApprovalResult.Approved(ApprovalMethod.DIALOG)
        }
        override val downloader = object : Downloader {
            override val state: StateFlow<DownloadState> = MutableStateFlow(DownloadState.Ready("rick-kapi (test)"))
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
        // Text version of the screen, readable straight from the CI log.
        val texts = mutableListOf<String>()
        fun collect(n: SemanticsNode) {
            n.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            n.config.getOrNull(SemanticsProperties.EditableText)?.let { texts += "[${it.text}]" }
            n.children.forEach(::collect)
        }
        collect(rule.onNodeWithTag("app").fetchSemanticsNode())
        File(outDir, "$name.txt").writeText(texts.joinToString(" | "))
    }

    private fun waitForText(text: String, substring: Boolean = false, timeoutMs: Long = 10_000) {
        rule.waitUntil(timeoutMs) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Scrolls the node into view first: long screens (bundle list, models) put buttons below the fold. */
    private fun click(node: SemanticsNodeInteraction) {
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun click(text: String) = click(rule.onNodeWithText(text))

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
        click("Devam")
        waitForText("2/4 · Önerilen paket")
        waitForText("Qwen 3.5 9B Q4_K_M (GGUF)")
        shot("02-sihirbaz-paket")
        click("İndirmeden devam et")
        waitForText("4/4 · Ses testi")
        click("Bitir")
        click("Sohbete geç")

        // 2. Chat: greeting, quick command, tool call with a verified number, knowledge question without sources
        waitForText("Gönder")
        waitForText("efendim.", substring = true)
        shot("02c-sohbet-karsilama")
        ask("Saat kaç?")
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasText("Saat ", substring = true)).fetchSemanticsNodes().size >= 2
        }
        ask("12 çarpı 7 sonucunu söyler misin")
        waitForText("Sonuç 84 efendim.")
        waitForText("84")
        shot("03-sohbet-hesap")
        // Not in the library and no gate to read the web with: Rick offers the browser and does nothing on "Hayır".
        ask("Atatürk kimdir?")
        waitForText("tarayıcıda Google'da \"Atatürk\" aramasını açayım", substring = true)
        shot("04-sohbet-kaynak-yok")
        click("Hayır")
        waitForText("Peki, yapmıyorum.")

        // 3. Models → approval screen → reject
        rule.onNodeWithText("Modeller").performClick()
        waitForText("İndirme yolu: rick-kapi (test)")
        shot("05-modeller")
        click(rule.onAllNodesWithText("İndir")[0])
        waitForText("İnternet erişimi onayı")
        waitForText("huggingface.co", substring = true)
        shot("06-onay-ekrani")
        click("Reddet")
        waitForText("Onay verilmedi: Onay ekranında reddedildi")

        // 4. Network log shows the refused request; nothing was downloaded
        rule.onNodeWithText("Ağ").performClick()
        waitForText("Sonuç: reddedildi", substring = true)
        shot("07-ag-kayit-defteri")

        // 5. Smart home by voice: the coffee machine starts, a notice appears; the Ev panel shows it.
        rule.onNodeWithText("Sohbet").performClick()
        waitForText("Gönder")
        ask("Rick, bana bol köpüklü bir Türk kahvesi yap")
        waitForText("Hemen efendim.", substring = true)
        waitForText("TÜRK KAHVESİ · PİŞİYOR", substring = true)
        shot("08a-ev-kahve")
        click(rule.onNodeWithContentDescription("Menü"))
        click("🏠 Ev")
        waitForText("SİMÜLASYON")
        waitForText("Hazırlanıyor…", substring = true)
        shot("08b-ev-paneli")
        click("KAPAT")

        // Library by voice, then the panel.
        ask("Rick, kitaplığıma Davranış'ı ekle")
        waitForText("Tamamdır efendim, Davranış kitaplığınıza eklendi.")
        click(rule.onNodeWithContentDescription("Menü"))
        click("📚 Kitaplık")
        waitForText("📚 KİTAPLIK")
        waitForText("okunacak", substring = true)
        shot("08c-kitaplik")
        click("KAPAT")

        // To-do by voice and the offline daily briefing.
        ask("Rick, not al: süt al")
        waitForText("Listeye ekledim: Süt al.")
        click(rule.onNodeWithContentDescription("Menü"))
        click("☀ Günlük brifing")
        waitForText("Hepsi bu kadar. İyi bir gün dilerim.", substring = true)
        shot("08d-brifing")

        // Themes: the whole app follows the chosen palette.
        graph.settings.update { it.copy(theme = "mark3") }
        rule.waitForIdle()
        shot("08e-tema-mark3")
        graph.settings.update { it.copy(theme = "gunisigi") }
        rule.waitForIdle()
        shot("08f-tema-gunisigi")
        graph.settings.update { it.copy(theme = "jarvis") }
        rule.waitForIdle()

        // 6. Hand control by voice command: the PC has no tracking camera, so it opens the demo
        // (the effects themselves are drawn in HandControlUiTest with a stepped clock).
        ask("Rick, el kontrolünü aç")
        waitForText("R.I.C.K · EL KONTROLÜ")
        waitForText("DEMO", substring = true)
        shot("09-el-kontrolu")
        click("⚙")
        waitForText("EFEKTLER")
        shot("16-el-efekt-ayarlari")
        click("← GERİ")
        waitForText("Gönder")

        // 7. Settings
        rule.onNodeWithText("Ayarlar").performClick()
        waitForText("Tam offline mod (tüm indirmeler kapalı)")
        shot("08-ayarlar")
    }
}

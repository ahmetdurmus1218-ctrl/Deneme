package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import io.github.ahmetdurmus1218.jarvis.ui.AppGraph
import io.github.ahmetdurmus1218.jarvis.ui.JarvisApp
import org.junit.Rule
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Mark 2 on a wide PC window: ruler, clock ring, system, sun ring, home, memory, session panels. */
class Mark2UiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun mark2ShowsRealPanels() {
        val graph = AppGraph(AppUiTest.UiTestBridge(), MockLlmEngine(tokenDelayMs = 0))
        graph.settings.update { it.copy(wizardDone = true, hudMark = 2, city = "Manisa") }
        rule.setContent {
            Box(Modifier.size(1280.dp, 800.dp).testTag("app")) { JarvisApp(graph) { "3 Kas 2026 14:05" } }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("SİSTEM").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Manisa ·", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("EV · SİMÜLASYON").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        val img = rule.onNodeWithTag("app").captureToImage().toAwtImage()
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        File("build/ui-screens").mkdirs()
        ImageIO.write(rgb, "jpg", File("build/ui-screens/17-mark2.jpg"))
    }
}

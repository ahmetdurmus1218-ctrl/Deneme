package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.ahmetdurmus1218.jarvis.ui.JarvisController
import io.github.ahmetdurmus1218.jarvis.ui.screens.CameraScreen
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisTheme
import org.junit.Rule
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hand control's camera-less demo, drawn frame by frame: the test clock is stepped by hand from the
 * start (UI tests otherwise stop endless animations), and each effect is captured as a screenshot.
 */
class HandControlUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theDemoDrawsEveryEffect() {
        val graph = AppGraph(AppUiTest.UiTestBridge(), MockLlmEngine(tokenDelayMs = 0))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JarvisTheme {
                Box(Modifier.size(420.dp, 760.dp).testTag("app")) {
                    val scope = rememberCoroutineScope()
                    val controller = remember { JarvisController(scope, graph.bridge, graph.assistant, graph.memory, graph.settings) }
                    CameraScreen(null, controller, graph.installer, emptyList(), graph.settings) {}
                }
            }
        }
        val out = File("build/ui-screens").apply { mkdirs() }
        val titles = mutableListOf<String>()
        for ((ms, name) in listOf(1_500L to "09-el-ates", 3_500L to "10-el-muhur", 4_000L to "11-el-gezegen", 3_500L to "12-el-iki-elle", 2_400L to "13-el-patlama", 2_600L to "14-el-simsek", 3_000L to "15-el-isik-izi")) {
            rule.mainClock.advanceTimeBy(ms)
            val img = rule.onNodeWithTag("app").captureToImage().toAwtImage()
            val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
            rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
            ImageIO.write(rgb, "jpg", File(out, "$name.jpg"))
            val demo = rule.onAllNodesWithText("DEMO ·", substring = true).fetchSemanticsNodes()
            titles += demo.firstOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
            File(out, "$name.txt").writeText(titles.last())
        }
        println("demo titles: $titles")
        // The demo moved through its scenes (the frame loop ran under the stepped clock).
        assertTrue(titles.distinct().size >= 5, titles.toString())
    }
}

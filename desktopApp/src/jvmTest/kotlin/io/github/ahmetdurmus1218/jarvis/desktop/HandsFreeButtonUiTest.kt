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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import io.github.ahmetdurmus1218.jarvis.ui.AppGraph
import io.github.ahmetdurmus1218.jarvis.ui.JarvisApp
import io.github.ahmetdurmus1218.jarvis.ui.SpeechInput
import org.junit.Rule
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** The on/off button for continuous listening in the chat screen's top bar, on a narrow phone. */
class HandsFreeButtonUiTest {
    @get:Rule
    val rule = createComposeRule()

    private class Mic : SpeechInput {
        override val engineName = "test"
        override fun isAvailable() = true
        override val canTranscribe = true
        override fun start(listener: SpeechInput.Listener) {}
        override fun stop() {}
        override fun cancel() {}
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val img = rule.onNodeWithTag("app").captureToImage().toAwtImage()
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        ImageIO.write(rgb, "jpg", File("build/ui-screens").apply { mkdirs() }.resolve("$name.jpg"))
    }

    private fun waitForText(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun oneTapTurnsContinuousListeningOffAndOnAgain() {
        val graph = AppGraph(AppUiTest.UiTestBridge(Mic()), MockLlmEngine(tokenDelayMs = 0))
        graph.settings.update { it.copy(wizardDone = true) }
        rule.setContent {
            Box(Modifier.size(335.dp, 700.dp).testTag("app")) {
                JarvisApp(graph) { "12 Kas 2026 18:30" }
            }
        }
        // The top bar's button (short labels on a phone): on, one tap off, one tap on again.
        waitForText("AÇIK")
        shot("31-surekli-dinleme-acik")
        rule.onNodeWithText("AÇIK").performClick()
        waitForText("KAPALI")
        assertTrue(!graph.settings.state.value.handsFree)
        shot("32-surekli-dinleme-kapali")
        rule.onNodeWithText("KAPALI").performClick()
        waitForText("AÇIK")
        assertTrue(graph.settings.state.value.handsFree)
    }
}

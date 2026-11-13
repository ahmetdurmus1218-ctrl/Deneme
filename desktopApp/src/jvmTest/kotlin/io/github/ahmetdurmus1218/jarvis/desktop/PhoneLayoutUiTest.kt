package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
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

/**
 * The home screen on real phone sizes: a small phone, common ones, a tall one, a phone on its side and the
 * phone's large-text setting, with each of the new looks. The layout follows the screen's width and the text
 * size (not the platform), so the PC test renders what a phone shows. Every main control must be on screen,
 * inside the window and big enough to tap.
 */
class PhoneLayoutUiTest {
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

    private data class Phone(val name: String, val w: Int, val h: Int, val fontScale: Float = 1f, val look: String = "glass")

    private val phones = listOf(
        Phone("kucuk-320x568", 320, 568),
        Phone("orta-360x640", 360, 640),
        Phone("yaygin-393x851", 393, 851, look = "robot"),
        Phone("uzun-412x915", 412, 915, look = "hud"),
        Phone("buyuk-yazi-360x740", 360, 740, fontScale = 1.3f, look = "wave"),
        Phone("en-buyuk-yazi-360x740", 360, 740, fontScale = 1.5f, look = "robot"),
        Phone("yatay-740x360", 740, 360),
    )

    private fun shot(name: String) {
        rule.waitForIdle()
        val img = rule.onNodeWithTag("app").captureToImage().toAwtImage()
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        ImageIO.write(rgb, "jpg", File("build/ui-screens").apply { mkdirs() }.resolve("$name.jpg"))
    }

    private fun SemanticsNodeInteraction.inside(phone: Phone, what: String, minSize: Int = 24) {
        val b: DpRect = getBoundsInRoot()
        val where = "${phone.name}: $what at $b"
        assertTrue(b.left >= (-1).dp && b.top >= (-1).dp, "$where starts outside the screen")
        assertTrue(b.right <= (phone.w + 1).dp && b.bottom <= (phone.h + 1).dp, "$where ends outside the screen")
        assertTrue(b.right - b.left >= minSize.dp && b.bottom - b.top >= minSize.dp, "$where is too small to tap")
    }

    @Test
    fun homeScreenFitsEveryPhone() {
        val graph = AppGraph(AppUiTest.UiTestBridge(Mic()), MockLlmEngine(tokenDelayMs = 0))
        var phone by mutableStateOf(phones.first())
        graph.settings.update { it.copy(wizardDone = true, look = phone.look) }
        rule.setContent {
            val p = phone
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, p.fontScale)) {
                Box(Modifier.size(p.w.dp, p.h.dp).testTag("app")) {
                    JarvisApp(graph) { "13 Kas 2026 09:30" }
                }
            }
        }
        for (p in phones) {
            phone = p
            graph.settings.update { it.copy(look = p.look) }
            rule.waitUntil(10_000) { rule.onAllNodesWithText("AÇIK", substring = true).fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Mikrofon").inside(p, "microphone")
            rule.onNodeWithContentDescription("Kamera").inside(p, "camera")
            rule.onNodeWithContentDescription("Menü").inside(p, "menu")
            rule.onNodeWithText("AÇIK", substring = true).inside(p, "listening switch", minSize = 10)
            rule.onNodeWithText("Gönder").inside(p, "send button", minSize = 10)
            for (tab in listOf("Sohbet", "Modeller", "Hafıza", "Ağ", "Ayarlar")) {
                val node = rule.onNodeWithText(tab, useUnmergedTree = true)
                node.inside(p, "tab $tab", minSize = 8)
                // One line: a name broken in two ("Modell / er") is twice as tall.
                val b = node.getBoundsInRoot()
                assertTrue(b.bottom - b.top < 28.dp, "${p.name}: tab name $tab is broken over two lines ($b)")
            }
            if (p.h > p.w) {
                // Upright phones: the core leaves room for its state and model lines above the strip below it.
                val strip = rule.onNodeWithText("beynini kur", substring = true).getBoundsInRoot()
                for (line in listOf("HAZIR", "Model:")) {
                    val b = rule.onNodeWithText(line, substring = true).getBoundsInRoot()
                    assertTrue(b.bottom <= strip.top, "${p.name}: '$line' ($b) is hidden under the strip below ($strip)")
                }
            }
            shot("40-telefon-${p.name}")
        }
    }
}

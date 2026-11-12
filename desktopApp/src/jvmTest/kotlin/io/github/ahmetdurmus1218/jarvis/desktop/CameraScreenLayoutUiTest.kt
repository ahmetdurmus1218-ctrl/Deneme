package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.core.llm.MockLlmEngine
import io.github.ahmetdurmus1218.jarvis.core.vision.DemoHands
import io.github.ahmetdurmus1218.jarvis.core.vision.Hand
import io.github.ahmetdurmus1218.jarvis.core.vision.HandShape
import io.github.ahmetdurmus1218.jarvis.ui.AppGraph
import io.github.ahmetdurmus1218.jarvis.ui.JarvisController
import io.github.ahmetdurmus1218.jarvis.ui.VisionCamera
import io.github.ahmetdurmus1218.jarvis.ui.VisionFrame
import io.github.ahmetdurmus1218.jarvis.ui.screens.CameraScreen
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisTheme
import org.junit.Rule
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hand-control screen on a narrow phone with a large font (335 dp wide, font ×1.3: the phone it
 * went wrong on). The title used to break into one letter per line and the effects settings were
 * drawn underneath the answer, the hint and the buttons.
 */
class CameraScreenLayoutUiTest {
    @get:Rule
    val rule = createComposeRule()

    /** A camera that shows a wall and "sees" the given hands. */
    private class FakeCamera(private val hands: () -> List<Hand>) : VisionCamera {
        override fun trackingReady() = true
        override val trackingGroup = "vision-tracking"

        @Composable
        override fun Preview(modifier: Modifier, frontCamera: Boolean, onFrame: (VisionFrame) -> Unit) {
            val now = hands()
            LaunchedEffect(now) { onFrame(VisionFrame(now, emptyList(), 480, 640)) }
            Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF8C8F96), Color(0xFF39513A)))))
        }

        override suspend fun snapshotJpeg(): ByteArray? = null
    }

    private fun shot(name: String) {
        val out = File("build/ui-screens").apply { mkdirs() }
        val img = rule.onNodeWithTag("app").captureToImage().toAwtImage()
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        ImageIO.write(rgb, "jpg", File(out, "$name.jpg"))
    }

    private fun count(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test
    fun nothingWrapsOrOverlapsOnANarrowPhoneWithALargeFont() {
        val graph = AppGraph(AppUiTest.UiTestBridge(), MockLlmEngine(tokenDelayMs = 0))
        var seen: List<Hand> = emptyList()
        val hands = androidx.compose.runtime.mutableStateOf(seen)
        val camera = FakeCamera { hands.value }
        lateinit var controller: JarvisController
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JarvisTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f)) {
                    Box(Modifier.size(335.dp, 700.dp).testTag("app")) {
                        val scope = rememberCoroutineScope()
                        controller = remember { JarvisController(scope, graph.bridge, graph.assistant, graph.memory, graph.settings) }
                        CameraScreen(camera, controller, graph.installer, emptyList(), graph.settings) {}
                    }
                }
            }
        }
        rule.mainClock.advanceTimeBy(600)

        // 1. Pointer mode, no hand in the picture: one title line, one status line, the hint, one row of buttons.
        val title = rule.onNodeWithText("R.I.C.K · EL KONTROLÜ").getUnclippedBoundsInRoot()
        assertTrue(title.height < 30.dp, "the title is one line (was ${title.height})")
        assertTrue(title.width > 150.dp, "the title has its width (was ${title.width})")
        assertEquals(1, count("El görünmüyor"))
        assertEquals(1, count("salla = çık"))
        val talk = rule.onNodeWithText("🎤 Konuş").getUnclippedBoundsInRoot()
        val what = rule.onNodeWithText("BU NE?").getUnclippedBoundsInRoot()
        assertTrue(kotlin.math.abs((talk.top - what.top).value) < 4f, "talk and the camera questions share one row")
        controller.showNotice("Bir şey duyamadım. Mikrofona dokunup konuşun; susunca kendisi durur.")
        rule.mainClock.advanceTimeBy(100)
        shot("27-el-kontrolu-dar")

        // 2. A hand comes up: the screen says so and the hint makes room.
        controller.showNotice("")
        seen = listOf(DemoHands.hand(HandShape.POINT, 0.5f, 0.55f))
        hands.value = seen
        rule.mainClock.advanceTimeBy(1_200)
        assertEquals(1, count("El görüldü"))
        assertEquals(0, count("salla = çık"))
        shot("28-el-kontrolu-el")

        // 3. Effects mode with its settings open: they cover the screen alone, the top bar stays usable.
        hands.value = emptyList()
        rule.onNodeWithText("✨").performClick()
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithText("⚙").performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals(1, count("EFEKTLER"))
        assertEquals(0, count("BU NE?"))
        assertEquals(0, count("basılı tut"))
        shot("29-el-efekt-ayarlari-dar")
        rule.onNodeWithText("Kapat ✕").performClick()
        rule.mainClock.advanceTimeBy(300)
        assertEquals(0, count("EFEKTLER"))
        assertEquals(1, count("BU NE?"))
        shot("30-el-efektler-dar")
    }
}

package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.components.EyeAssets
import io.github.ahmetdurmus1218.jarvis.ui.components.blinkAt
import io.github.ahmetdurmus1218.jarvis.ui.components.drawEye
import io.github.ahmetdurmus1218.jarvis.ui.components.lidForMood
import io.github.ahmetdurmus1218.jarvis.ui.components.pupilForLight
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisTheme
import org.junit.Rule
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Rick's real eye: the bundled photos load, and the pupil and lid states are drawn (screenshots for review). */
class RealEyeUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theEyeLoadsAndReactsToLightAndMood() {
        val eye = EyeAssets.load(::testImage, ::testText)
        assertNotNull(eye, "the eye's files are bundled")
        assertEquals(eye.geometry.lids.size, eye.lids.size)
        // Dark room: a wide pupil; sunlight: a small one; no sensor: about as photographed.
        assertTrue(pupilForLight(0f) > 1.6f)
        assertTrue(pupilForLight(20_000f) < 0.7f)
        assertTrue(pupilForLight(null) in 0.9f..1.2f)
        // A blink closes and opens within a third of a second; asleep the lid is shut, listening it is open.
        assertEquals(1f, blinkAt(100))
        assertEquals(0f, blinkAt(400))
        assertEquals(1f, lidForMood(CoreMode.IDLE, sleeping = true))
        assertEquals(0f, lidForMood(CoreMode.LISTENING, sleeping = false))

        val states = listOf(0.62f to 0f, 1.0f to 0f, 1.8f to 0f, 1.0f to 0.3f, 1.0f to 0.6f, 1.0f to 1f)
        rule.setContent {
            JarvisTheme {
                Column(Modifier.background(JarvisColors.Background).testTag("eyes")) {
                    states.chunked(3).forEach { row ->
                        Row {
                            row.forEach { (pupil, lid) ->
                                Box(Modifier.size(300.dp)) { Canvas(Modifier.size(300.dp)) { drawEye(eye, pupil, lid) } }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val img = rule.onNodeWithTag("eyes").captureToImage().toAwtImage()
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        ImageIO.write(rgb, "jpg", File(File("build/ui-screens").apply { mkdirs() }, "33-gercek-goz.jpg"))
    }
}

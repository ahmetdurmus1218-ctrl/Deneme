package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.components.RealEye
import io.github.ahmetdurmus1218.jarvis.ui.components.EyeAssets
import io.github.ahmetdurmus1218.jarvis.ui.components.blinkAt
import io.github.ahmetdurmus1218.jarvis.ui.components.drawEye
import io.github.ahmetdurmus1218.jarvis.ui.components.eyeLayer
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
                                Box(Modifier.size(330.dp, 254.dp)) { Canvas(Modifier.size(330.dp, 254.dp).eyeLayer()) { drawEye(eye, pupil, lid) } }
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
        // No frame around the photo: its sides and its top are exactly the background behind it
        // (three 330 dp cells fit the 1024 px test window).
        val bg = JarvisColors.Background
        val want = intArrayOf((bg.red * 255).toInt(), (bg.green * 255).toInt(), (bg.blue * 255).toInt())
        val cell = img.width / 3
        for ((px, py) in listOf(1 to img.height / 4, cell / 2 to 1, cell - 2 to img.height / 4)) {
            val c = rgb.getRGB(px, py)
            val got = intArrayOf((c shr 16) and 255, (c shr 8) and 255, c and 255)
            for (i in 0..2) assertTrue(kotlin.math.abs(got[i] - want[i]) <= 3, "edge pixel ($px,$py) ${got.toList()} vs background ${want.toList()}")
        }
        ImageIO.write(rgb, "jpg", File(File("build/ui-screens").apply { mkdirs() }, "33-gercek-goz.jpg"))
    }

    @Test
    fun twoEyesShowEveryFeeling() {
        val eye = EyeAssets.load(::testImage, ::testText)
        assertNotNull(eye, "the eye's files are bundled")
        assertTrue(eye.lows.isNotEmpty(), "the raised lower lid (smile) is bundled")
        val feelings = listOf(
            "Sakin" to Emotion.NONE, "Gülümseme" to Emotion.HAPPY, "Sevinç" to Emotion.JOY,
            "Hüzün" to Emotion.SAD, "Şaşkınlık" to Emotion.SURPRISED,
        )
        rule.setContent {
            JarvisTheme {
                Column(Modifier.background(JarvisColors.Background).testTag("feelings")) {
                    feelings.chunked(2).forEach { row ->
                        Row {
                            row.forEach { (name, e) ->
                                Column(Modifier.size(480.dp, 230.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    RealEye(eye, CoreMode.IDLE, 0f, size = 330.dp, emotion = e)
                                    Text(name, color = JarvisColors.Text)
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val img = rule.onNodeWithTag("feelings").captureToImage().toAwtImage()
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        ImageIO.write(rgb, "jpg", File(File("build/ui-screens").apply { mkdirs() }, "34-duygular.jpg"))
    }
}

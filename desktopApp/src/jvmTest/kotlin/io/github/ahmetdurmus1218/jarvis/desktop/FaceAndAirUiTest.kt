package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.components.AirTargets
import io.github.ahmetdurmus1218.jarvis.ui.components.RickFace
import io.github.ahmetdurmus1218.jarvis.ui.components.airTarget
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisTheme
import org.junit.Rule
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Rick's living face in every mood (screenshots for review) and the air-click registry. */
class FaceAndAirUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theFaceDrawsEveryMood() {
        val mode = mutableStateOf(CoreMode.IDLE)
        val asleep = mutableStateOf(false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JarvisTheme {
                Box(Modifier.size(300.dp).background(JarvisColors.Background).testTag("face")) {
                    RickFace(mode.value, 0.6f, size = 300.dp, sleeping = asleep.value)
                }
            }
        }
        val out = File("build/ui-screens").apply { mkdirs() }
        for ((name, m, sleep) in listOf(
            Triple("20-yuz-bekliyor", CoreMode.IDLE, false),
            Triple("21-yuz-dinliyor", CoreMode.LISTENING, false),
            Triple("22-yuz-dusunuyor", CoreMode.THINKING, false),
            Triple("23-yuz-konusuyor", CoreMode.SPEAKING, false),
            Triple("24-yuz-uyuyor", CoreMode.IDLE, true),
        )) {
            mode.value = m
            asleep.value = sleep
            rule.mainClock.advanceTimeBy(1_300)
            val img = rule.onNodeWithTag("face").captureToImage().toAwtImage()
            val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
            rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
            ImageIO.write(rgb, "jpg", File(out, "$name.jpg"))
            File(out, "$name.txt").writeText(name)
        }
    }

    @Test
    fun aClickInTheAirRunsTheButtonUnderTheCursor() {
        val targets = AirTargets()
        var clicked = ""
        rule.setContent {
            Column {
                Row {
                    Box(Modifier.size(100.dp).airTarget(targets) { clicked = "evet" }) { Text("Evet") }
                    Box(Modifier.size(100.dp).airTarget(targets) { clicked = "hayir" }) { Text("Hayır") }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(2, targets.size())
        val density = rule.density.density
        targets.at(150 * density, 50 * density)!!.invoke()
        assertEquals("hayir", clicked)
        targets.at(20 * density, 20 * density)!!.invoke()
        assertEquals("evet", clicked)
        assertNull(targets.at(20 * density, 180 * density))

        // The magnet: just below "Evet" still hits it (and shows its middle); far away nothing.
        val near = targets.near(50 * density, 120 * density, 36 * density)!!
        near.action()
        assertEquals("evet", clicked)
        assertEquals(50 * density, near.center.x, 0.5f)
        assertEquals(50 * density, near.center.y, 0.5f)
        assertNull(targets.near(50 * density, 200 * density, 36 * density))
    }

    @Test
    fun theBubbleFaceIsTheHeadOnly() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            JarvisTheme {
                Box(Modifier.size(96.dp).background(JarvisColors.Background).testTag("small")) {
                    RickFace(CoreMode.SPEAKING, 0f, size = 96.dp)
                }
            }
        }
        rule.mainClock.advanceTimeBy(900)
        val img = rule.onNodeWithTag("small").captureToImage().toAwtImage()
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        ImageIO.write(rgb, "jpg", File(File("build/ui-screens").apply { mkdirs() }, "25-yuz-kucuk.jpg"))
    }
}

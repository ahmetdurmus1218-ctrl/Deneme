package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import io.github.ahmetdurmus1218.jarvis.core.scene.Kaynak
import io.github.ahmetdurmus1218.jarvis.ui.components.SourceChips
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisTheme
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The sources under an answer with long titles (the day's news): they used to sit side by side, so
 * the last ones became one letter wide and hundreds of lines tall and the answer's cards ended up
 * far off the screen.
 */
class SourceChipsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun longTitlesStaySmall() {
        val sources = listOf(
            "İBB davasında ara karar: İmamoğlu'nun avukatı Mehmet Pehlivan dahil sekiz kişiye tahliye",
            "A Milli Takım'da Sergen Yalçın dönemi 2-1'lik Belçika yenilgisiyle başladı",
            "Jensen Huang'ın yükselişi ve düşüşü: Teknoloji yıldızının adı nasıl skandala karıştı?",
            "Malatya'da düşen helikopterde dört asker hayatını kaybetti",
            "Johnny Depp 'iptal' yılları sonrası ilk kez beyaz perdede",
        ).map { Kaynak(it, "web", "https://www.bbc.com/turkce/articles/x") }
        rule.setContent {
            JarvisTheme {
                Box(Modifier.width(335.dp).testTag("chips")) { SourceChips(sources) }
            }
        }
        val h = rule.onNodeWithTag("chips").getUnclippedBoundsInRoot().height
        assertTrue(h < 140.dp, "four sources take four short lines, not a tower (was $h)")
    }
}

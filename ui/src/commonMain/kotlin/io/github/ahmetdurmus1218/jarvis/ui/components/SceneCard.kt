package io.github.ahmetdurmus1218.jarvis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.scene.GorselTip
import io.github.ahmetdurmus1218.jarvis.core.scene.Kaynak
import io.github.ahmetdurmus1218.jarvis.core.scene.VerifiedScene
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors

@Composable
fun SceneCard(scene: VerifiedScene, modifier: Modifier = Modifier) {
    val s = scene.sahne
    val g = s.gorsel
    Column(
        modifier
            .fillMaxWidth()
            .background(JarvisColors.SurfaceHigh, RoundedCornerShape(14.dp))
            .border(1.dp, JarvisColors.CyanDim.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (g?.tip) {
            GorselTip.BASLIK -> Text(g.baslik.orEmpty(), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = JarvisColors.Cyan)
            GorselTip.SAYI -> {
                g.etiket?.let { Text(it, color = JarvisColors.TextDim, fontSize = 13.sp) }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(g.deger.orEmpty(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = JarvisColors.Cyan)
                    g.birim?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(it, fontSize = 18.sp, color = JarvisColors.TextDim)
                    }
                }
            }
            GorselTip.LISTE -> g.ogeler.orEmpty().forEach { Text("•  $it") }
            GorselTip.ADIMLAR -> g.ogeler.orEmpty().forEachIndexed { i, it -> Text("${i + 1}.  $it") }
            GorselTip.ALINTI -> {
                Text("“${g.metin.orEmpty()}”", fontStyle = FontStyle.Italic, fontSize = 17.sp)
                g.kaynak?.let { Text("— $it", color = JarvisColors.TextDim, fontSize = 13.sp) }
            }
            GorselTip.KAYNAK_KARTI -> {
                Text(g.baslik.orEmpty(), fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(g.kaynak, g.konum).joinToString(" · "), color = JarvisColors.TextDim, fontSize = 13.sp)
            }
            else -> {}
        }
        if (s.ses.isNotBlank()) Text(s.ses, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (scene.kaynakYok) Badge("kaynak yok", JarvisColors.Amber)
            if (scene.downgraded) Badge("doğrulanamayan değer kaldırıldı", JarvisColors.Red)
        }
    }
}

@Composable
fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun SourceChips(sources: List<Kaynak>) {
    if (sources.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        sources.take(4).forEach { k ->
            val label = listOfNotNull(k.tur?.let { tur -> tur.replaceFirstChar { it.uppercase() } }, k.baslik.ifBlank { k.konum }).joinToString(": ")
            Badge(label, JarvisColors.Cyan)
        }
    }
}

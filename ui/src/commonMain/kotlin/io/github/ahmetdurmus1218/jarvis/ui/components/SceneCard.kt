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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
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
            .background(
                Brush.verticalGradient(listOf(JarvisColors.Glass, JarvisColors.Surface.copy(alpha = 0.55f))),
                RoundedCornerShape(16.dp),
            )
            .border(
                1.dp,
                Brush.linearGradient(listOf(JarvisColors.GlassBorder, JarvisColors.Cyan.copy(alpha = 0.08f), JarvisColors.GlassBorder)),
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (g?.tip) {
            GorselTip.BASLIK -> Text(g.baslik.orEmpty().uppercase(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = JarvisColors.Cyan, letterSpacing = 2.sp)
            GorselTip.SAYI -> {
                g.etiket?.let { Text(it, color = JarvisColors.TextDim, fontSize = 13.sp) }
                Row(verticalAlignment = Alignment.Bottom) {
                    CountUp(g.deger.orEmpty(), fontSize = 40.sp, color = JarvisColors.CyanBright)
                    g.birim?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(it, fontSize = 18.sp, color = JarvisColors.TextDim)
                    }
                }
            }
            GorselTip.LISTE -> g.ogeler.orEmpty().forEach {
                Row { Text("◆  ", color = JarvisColors.Cyan, fontSize = 12.sp); Text(it) }
            }
            GorselTip.ADIMLAR -> g.ogeler.orEmpty().forEachIndexed { i, it ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("${i + 1}".padStart(2, '0') + "  ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace)
                    Text(it)
                }
            }
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

/**
 * Shows a number counting up to its final value (plain integers only). Formatted values such as
 * "5.910.320" or "8.848,86" are shown exactly as given: the verified text never changes.
 */
@Composable
fun CountUp(value: String, fontSize: androidx.compose.ui.unit.TextUnit, color: androidx.compose.ui.graphics.Color) {
    val target = value.takeIf { Regex("^\\d{1,9}$").matches(it) }?.toLong()
    val progress = remember(value) { Animatable(if (target == null) 1f else 0f) }
    LaunchedEffect(value) { if (target != null) progress.animateTo(1f, tween(900)) }
    val shown = if (target == null || progress.value >= 1f) value else (target * progress.value).toLong().toString()
    Text(shown, fontSize = fontSize, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace)
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

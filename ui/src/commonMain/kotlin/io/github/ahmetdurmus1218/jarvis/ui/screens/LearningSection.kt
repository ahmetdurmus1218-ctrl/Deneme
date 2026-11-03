package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.AssistantName
import io.github.ahmetdurmus1218.jarvis.core.learning.Learning
import io.github.ahmetdurmus1218.jarvis.ui.SettingsStore
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors

/**
 * "Kendini geliştirme" in the Memory screen: the switch, what Rick learned from the user's
 * corrections and likes, and what it read by itself while idle — each item can be deleted.
 */
@Composable
fun LearningSection(learning: Learning, settings: SettingsStore) {
    val s by settings.state.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val lessons = remember(refresh) { learning.lessons().asReversed() }
    val notes = remember(refresh) { learning.notes().asReversed() }

    Text("Kendini geliştirme", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
    Text(
        "${AssistantName.SHORT} düzelttiğiniz ve beğendiğiniz cevaplardan öğrenir; boştayken ilgi alanlarınızı (hafızası, kitaplarınız, " +
            "sorularınız) internetsiz Wikipedia'dan okuyup kaynaklı not alır. Hepsi cihazda, şifreli kalır. Düzeltmek için: " +
            "\"Hayır, doğrusu …\" deyin ya da cevabın altındaki 👎'ye basın.",
        color = JarvisColors.TextDim, fontSize = 12.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Kendini geliştirme açık", modifier = Modifier.weight(1f))
        Switch(checked = s.selfLearning, onCheckedChange = { v -> settings.update { it.copy(selfLearning = v) } })
    }

    Text("Öğrendiklerim (${lessons.size})", color = JarvisColors.Cyan, fontSize = 13.sp)
    if (lessons.isEmpty()) Text("Henüz yok.", color = JarvisColors.TextDim, fontSize = 12.sp)
    lessons.take(50).forEach { l ->
        Item(
            title = (if (l.liked) "👍 " else "✏️ ") + l.question,
            body = l.correction?.let { "Doğrusu: $it" } ?: l.answer,
        ) { learning.removeLesson(l.id); refresh++ }
    }

    Text("Boştayken okuduklarım (${notes.size})", color = JarvisColors.Cyan, fontSize = 13.sp)
    if (notes.isEmpty()) {
        Text(
            "Henüz yok. Wikipedia paketi kuruluyken ${AssistantName.SHORT} birkaç dakika boşta kalınca okumaya başlar.",
            color = JarvisColors.TextDim, fontSize = 12.sp,
        )
    }
    notes.take(50).forEach { n ->
        Item(title = "📖 ${n.title}", body = n.text.take(220) + if (n.text.length > 220) "…" else "") { learning.removeNote(n.id); refresh++ }
    }
    if (lessons.isNotEmpty() || notes.isNotEmpty()) {
        TextButton(onClick = { learning.clear(); refresh++ }) { Text("Öğrendiklerinin hepsini sil") }
    }
}

@Composable
private fun Item(title: String, body: String, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(JarvisColors.SurfaceHigh, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(body, color = JarvisColors.TextDim, fontSize = 12.sp)
        }
        TextButton(onClick = onDelete) { Text("Sil") }
    }
}

package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.todo.TodoList
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors

/** Yapılacaklar: the same list as "not al: …" / "yapılacaklarım neler?". */
@Composable
fun TodoPanel(list: TodoList, onClose: () -> Unit) {
    var items by remember { mutableStateOf(list.items()) }
    var text by remember { mutableStateOf("") }
    fun refresh() { items = list.items() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(16.dp)
                .background(JarvisColors.Surface, RoundedCornerShape(18.dp))
                .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✅ YAPILACAKLAR", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
                Text("${items.count { !it.done }} açık", color = JarvisColors.TextDim, fontSize = 11.sp)
                TextButton(onClick = onClose) { Text("KAPAT", color = JarvisColors.TextDim, fontSize = 11.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Yeni iş") }, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = text.isNotBlank(), onClick = { list.add(text); text = ""; refresh() }) { Text("Ekle") }
            }
            if (items.isEmpty()) Text("Liste boş. Sesle de olur: \"not al: süt al\".", color = JarvisColors.TextDim, fontSize = 12.sp)
            for (t in items.sortedBy { it.done }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = t.done, onCheckedChange = { list.setDone(t.id, it); refresh() })
                    Text(
                        t.text, modifier = Modifier.weight(1f), fontSize = 14.sp,
                        color = if (t.done) JarvisColors.TextDim else JarvisColors.Text,
                        textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    )
                    TextButton(onClick = { list.remove(t.id); refresh() }) { Text("sil", fontSize = 10.sp, color = JarvisColors.TextDim) }
                }
            }
            if (items.any { it.done }) OutlinedButton(onClick = { list.clearDone(); refresh() }) { Text("Tamamlananları temizle", fontSize = 12.sp) }
        }
    }
}

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.assistant.Assistant
import io.github.ahmetdurmus1218.jarvis.core.library.Book
import io.github.ahmetdurmus1218.jarvis.core.library.BookNotebook
import io.github.ahmetdurmus1218.jarvis.core.library.BookStatus
import io.github.ahmetdurmus1218.jarvis.core.library.Library
import io.github.ahmetdurmus1218.jarvis.core.library.ShelfReading
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlinx.coroutines.launch

/**
 * Kitaplık: the user's books with status, page, notes, quotes and Jarvis's notebook. Books can be
 * added by name, by voice ("kitaplığıma … ekle") or from a shelf photo read by the vision model.
 */
@Composable
fun LibraryPanel(library: Library, assistant: Assistant, pickPhoto: suspend () -> ByteArray?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf(library.books()) }
    var openId by remember { mutableStateOf<Long?>(null) }
    var newTitle by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun refresh() { books = library.books() }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().padding(16.dp)
                .background(JarvisColors.Surface, RoundedCornerShape(18.dp))
                .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📚 KİTAPLIK", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
                Text("${books.size} kitap", color = JarvisColors.TextDim, fontSize = 11.sp)
                TextButton(onClick = onClose) { Text("KAPAT", color = JarvisColors.TextDim, fontSize = 11.sp) }
            }
            val open = books.firstOrNull { it.id == openId }
            if (open != null) {
                BookPage(open, books, library, assistant, onBack = { openId = null; refresh() }) { refresh() }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(newTitle, { newTitle = it }, singleLine = true, label = { Text("Kitap adı") }, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = newTitle.isNotBlank(), onClick = { library.add(newTitle); newTitle = ""; refresh() }) { Text("Ekle") }
            }
            OutlinedButton(enabled = !busy, onClick = {
                scope.launch {
                    val jpeg = pickPhoto() ?: return@launch
                    busy = true
                    status = "Raf okunuyor…"
                    val text = assistant.visionText(ShelfReading.PROMPT, jpeg)
                    status = if (text == null) {
                        "Fotoğraftan okumak için görebilen bir model gerekiyor (Modeller ekranı)."
                    } else {
                        val found = ShelfReading.parse(text)
                        val added = library.addAll(found)
                        "${found.size} kitap okundu, $added tanesi yeni eklendi."
                    }
                    busy = false
                    refresh()
                }
            }) { Text("📷 Raf fotoğrafından ekle") }
            if (status.isNotEmpty()) Text(status, color = JarvisColors.Amber, fontSize = 12.sp)
            if (books.isEmpty()) Text("Henüz kitap yok. Sesle de olur: \"Kitaplığıma Davranış'ı ekle\".", color = JarvisColors.TextDim, fontSize = 12.sp)
            for (b in books.sortedWith(compareBy<Book> { it.status != BookStatus.READING }.thenBy { it.title })) {
                Row(
                    Modifier.fillMaxWidth().background(JarvisColors.SurfaceHigh, RoundedCornerShape(10.dp)).clickable { openId = b.id }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(b.title, color = JarvisColors.Text, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(b.author.ifBlank { null }, b.status.label, b.page?.let { "s. $it" }).joinToString(" · "), color = JarvisColors.TextDim, fontSize = 11.sp)
                    }
                    Text("›", color = JarvisColors.Cyan, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun BookPage(b: Book, shelf: List<Book>, library: Library, assistant: Assistant, onBack: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var note by remember(b.id) { mutableStateOf("") }
    var quote by remember(b.id) { mutableStateOf("") }
    var page by remember(b.id) { mutableStateOf(b.page?.toString().orEmpty()) }
    var writing by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    TextButton(onClick = onBack) { Text("← Kitaplar", color = JarvisColors.Cyan) }
    Text(b.title, color = JarvisColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    if (b.author.isNotBlank()) Text(b.author, color = JarvisColors.TextDim)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BookStatus.entries.forEach { st ->
            OutlinedButton(onClick = { library.update(b.id) { it.copy(status = st) }; onChanged() }) {
                Text(st.label, color = if (b.status == st) JarvisColors.Cyan else JarvisColors.TextDim, fontSize = 11.sp)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(page, { page = it.filter(Char::isDigit).take(5) }, singleLine = true, label = { Text("Sayfa") }, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { library.update(b.id) { it.copy(page = page.toIntOrNull()) }; onChanged() }) { Text("Kaydet") }
    }

    val nb = b.notebook
    Text("JARVIS'İN KİTAP DEFTERİ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp)
    when {
        nb == null -> Text("Henüz yazılmadı.", color = JarvisColors.TextDim, fontSize = 12.sp)
        nb.unknown -> Text("Jarvis bu kitabı tanımıyor; sayfa uydurmuyor.", color = JarvisColors.Amber, fontSize = 12.sp)
        else -> {
            Text(nb.summary, color = JarvisColors.Text, fontSize = 13.sp)
            nb.ideas.forEach { Text("• $it", color = JarvisColors.Text, fontSize = 12.sp) }
            if (nb.author.isNotBlank()) Text("Yazar: ${nb.author}", color = JarvisColors.TextDim, fontSize = 12.sp)
            if (nb.themes.isNotBlank()) Text("Temalar: ${nb.themes}", color = JarvisColors.TextDim, fontSize = 12.sp)
            if (nb.why.isNotBlank()) Text("Neden okumalı: ${nb.why}", color = JarvisColors.TextDim, fontSize = 12.sp)
            if (nb.forWhom.isNotBlank()) Text("Kime uygun: ${nb.forWhom}", color = JarvisColors.TextDim, fontSize = 12.sp)
            if (nb.similar.isNotEmpty()) Text("Rafınızdan benzerleri: ${nb.similar.joinToString()}", color = JarvisColors.Green, fontSize = 12.sp)
        }
    }
    OutlinedButton(enabled = !writing, onClick = {
        scope.launch {
            writing = true
            msg = "Jarvis yazıyor…"
            val text = runCatching { assistant.plainText(BookNotebook.SYSTEM, BookNotebook.prompt(b, shelf)) }.getOrNull()
            if (text == null) {
                msg = "Defter için gerçek bir model gerekiyor (Modeller ekranı)."
            } else {
                library.update(b.id) { it.copy(notebook = BookNotebook.parse(text, shelf)) }
                msg = ""
                onChanged()
            }
            writing = false
        }
    }) { Text(if (nb == null) "Jarvis doldursun" else "Yeniden yaz") }
    if (msg.isNotEmpty()) Text(msg, color = JarvisColors.Amber, fontSize = 12.sp)

    Text("NOTLARINIZ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp)
    b.notes.forEachIndexed { i, n ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("• $n", color = JarvisColors.Text, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { library.update(b.id) { it.copy(notes = it.notes.filterIndexed { j, _ -> j != i }) }; onChanged() }) { Text("sil", fontSize = 10.sp, color = JarvisColors.TextDim) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(note, { note = it }, label = { Text("Not") }, modifier = Modifier.weight(1f))
        OutlinedButton(enabled = note.isNotBlank(), onClick = { library.update(b.id) { it.copy(notes = it.notes + note.trim()) }; note = ""; onChanged() }) { Text("+") }
    }
    Text("ALINTILARINIZ", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp)
    b.quotes.forEach { Text("“$it”", color = JarvisColors.Text, fontSize = 12.sp) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(quote, { quote = it }, label = { Text("Alıntı") }, modifier = Modifier.weight(1f))
        OutlinedButton(enabled = quote.isNotBlank(), onClick = { library.update(b.id) { it.copy(quotes = it.quotes + quote.trim()) }; quote = ""; onChanged() }) { Text("+") }
    }
    TextButton(onClick = { library.remove(b.id); onBack() }) { Text("Kitabı kitaplıktan çıkar", color = JarvisColors.Red, fontSize = 11.sp) }
}

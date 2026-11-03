package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.memory.Fact
import io.github.ahmetdurmus1218.jarvis.core.memory.MemoryStore
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.formatBytes
import io.github.ahmetdurmus1218.jarvis.core.netlog.NetworkEvent
import io.github.ahmetdurmus1218.jarvis.core.knowledge.DocumentIndex
import io.github.ahmetdurmus1218.jarvis.ui.DocumentPick
import io.github.ahmetdurmus1218.jarvis.ui.DownloadState
import io.github.ahmetdurmus1218.jarvis.ui.ModelInstaller
import io.github.ahmetdurmus1218.jarvis.ui.PlatformBridge
import io.github.ahmetdurmus1218.jarvis.ui.SettingsStore
import io.github.ahmetdurmus1218.jarvis.ui.SpeechInput
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlinx.coroutines.launch

@Composable
private fun ScreenColumn(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(JarvisColors.Background).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = JarvisColors.Cyan)
        content()
    }
}

@Composable
fun ModelsScreen(bridge: PlatformBridge, registry: ModelRegistry, installer: ModelInstaller) {
    val scope = rememberCoroutineScope()
    val status by installer.status.collectAsState()
    val version by installer.installedVersion.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    val items = registry.forPlatform(bridge.platform)
    val gate by bridge.downloader.state.collectAsState()
    ScreenColumn("Modeller") {
        Text("Her dosyanın SHA-256 özeti models.lock.json ile karşılaştırılır. Kilit: ${registry.lock.generatedAt ?: "boş"}", color = JarvisColors.TextDim, fontSize = 12.sp)
        when (val g = gate) {
            is DownloadState.Ready -> Text("İndirme yolu: ${g.label}", color = JarvisColors.Green, fontSize = 13.sp)
            DownloadState.Unavailable -> Text(installer.gateMissingText(), color = JarvisColors.Amber, fontSize = 13.sp)
        }
        OutlinedButton(onClick = {
            scope.launch {
                msg = when (val r = installer.importFromFile()) {
                    is ModelInstaller.ImportResult.Installed -> "Eklendi ve doğrulandı: ${r.item.title}"
                    is ModelInstaller.ImportResult.KeptUnverified -> "Doğrulanmadan eklendi: ${r.fileName}"
                    is ModelInstaller.ImportResult.Discarded -> r.reason
                    ModelInstaller.ImportResult.Cancelled -> null
                }
            }
        }) { Text("Dosyadan yükle") }
        msg?.let { Text(it, color = JarvisColors.Amber) }
        if (items.isEmpty()) Text("Bu sürümün kilit dosyasında model yok.", color = JarvisColors.Amber)
        val busy by installer.busy.collectAsState()
        items.groupBy { it.group }.forEach { (_, group) ->
            // One button per group: Whisper needs encoder + decoder + tokens together.
            val installedFlags = group.map { item -> remember(version, item.id) { bridge.models.isInstalled(item) } }
            group.forEachIndexed { i, item -> ItemRow(item, status[item.id], installedFlags[i]) }
            val groupMissing = group.filterIndexed { i, _ -> !installedFlags[i] }
            // On the PC the program that runs the file (kiwix-tools, llama.cpp) comes along in the same download.
            val missing = if (groupMissing.isEmpty()) emptyList() else registry.withRuntime(group, bridge.platform).filter { !bridge.models.isInstalled(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (groupMissing.isEmpty()) {
                    TextButton(enabled = !busy, onClick = { group.forEach { installer.delete(it) } }) { Text("Sil") }
                } else {
                    val label = if (missing.size > 1) "İndir (${missing.size} dosya, ${formatBytes(missing.sumOf { it.size })})" else "İndir"
                    TextButton(enabled = !busy, onClick = { installer.installInBackground(missing, "Kullanıcı isteği: ${group.first().title}") }) { Text(label) }
                }
            }
        }
        if (busy) Text("Bir indirme sürüyor; bitene kadar diğer düğmeler bekler.", color = JarvisColors.TextDim, fontSize = 12.sp)
        val unverified = remember(version) { bridge.models.importedUnverifiedFiles() }
        if (unverified.isNotEmpty()) {
            Text("Doğrulanmamış dosyalar", color = JarvisColors.Red, fontWeight = FontWeight.SemiBold)
            unverified.forEach { Text("• $it", color = JarvisColors.Red) }
        }
    }
}

@Composable
fun MemoryScreen(memory: MemoryStore, settings: SettingsStore, documents: DocumentIndex, bridge: PlatformBridge) {
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf(documents.list()) }
    var docMsg by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var facts by remember { mutableStateOf<List<Fact>>(emptyList()) }
    var enabled by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        facts = memory.list()
        enabled = memory.enabled()
    }
    ScreenColumn("Hafıza") {
        Text("Jarvis'in sizin hakkınızda hatırladıkları. Bu sürümde hafıza yalnızca uygulama açıkken tutulur; şifreli kalıcı hafıza v0.4'te gelecek.", color = JarvisColors.TextDim, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hafıza açık", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { v ->
                scope.launch {
                    memory.setEnabled(v)
                    settings.update { it.copy(memoryEnabled = v) }
                    refresh++
                }
            })
        }
        if (facts.isEmpty()) Text("Kayıt yok.", color = JarvisColors.TextDim)
        facts.forEach { f ->
            Row(
                Modifier.fillMaxWidth().background(JarvisColors.SurfaceHigh, RoundedCornerShape(10.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(f.text, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { memory.remove(f.id); refresh++ } }) { Text("Sil") }
            }
        }
        if (facts.isNotEmpty()) OutlinedButton(onClick = { scope.launch { memory.clear(); refresh++ } }) { Text("Tümünü sil") }

        Text("Belgelerim", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(
            "PDF, TXT ve MD dosyalarınızı ekleyin; Jarvis sorularınızı bunlardan kaynak göstererek cevaplar. Belgeler cihazdan çıkmaz.",
            color = JarvisColors.TextDim, fontSize = 12.sp,
        )
        Button(enabled = !importing, onClick = {
            scope.launch {
                importing = true
                docMsg = when (val r = bridge.pickDocument()) {
                    is DocumentPick.Picked -> runCatching { documents.add(r.name, r.text) }.fold(
                        onSuccess = { "Eklendi: ${it.title} (${it.chunks} parça)" },
                        onFailure = { it.message ?: "Eklenemedi" },
                    )
                    is DocumentPick.Failed -> r.message
                    DocumentPick.Cancelled -> null
                }
                docs = documents.list()
                importing = false
            }
        }) { Text(if (importing) "Okunuyor…" else "Belge ekle (PDF / TXT / MD)") }
        docMsg?.let { Text(it, color = JarvisColors.Amber, fontSize = 13.sp) }
        if (docs.isEmpty()) Text("Henüz belge yok.", color = JarvisColors.TextDim)
        docs.forEach { d ->
            Row(
                Modifier.fillMaxWidth().background(JarvisColors.SurfaceHigh, RoundedCornerShape(10.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.title)
                    Text("${d.chunks} parça · ${d.chars} karakter", color = JarvisColors.TextDim, fontSize = 12.sp)
                }
                TextButton(onClick = { scope.launch { documents.remove(d.id); docs = documents.list() } }) { Text("Sil") }
            }
        }
    }
}

@Composable
fun NetworkLogScreen(events: List<NetworkEvent>, formatTime: (Long) -> String) {
    ScreenColumn("Ağ kayıt defteri") {
        Text("Jarvis'in yaptığı her internet isteği burada listelenir: zaman, alan adı, bayt ve onay.", color = JarvisColors.TextDim, fontSize = 12.sp)
        if (events.isEmpty()) Text("Henüz hiçbir ağ isteği yapılmadı.", color = JarvisColors.Green)
        events.asReversed().forEach { e ->
            Column(Modifier.fillMaxWidth().background(JarvisColors.SurfaceHigh, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text("${formatTime(e.timeMillis)} · ${e.host}", fontWeight = FontWeight.SemiBold)
                Text("${e.what} · ${formatBytes(e.bytes)}", fontSize = 13.sp)
                Text("Onay: ${e.approval} · Sonuç: ${e.result}", fontSize = 12.sp, color = if (e.result == "tamam") JarvisColors.Green else JarvisColors.Amber)
            }
        }
    }
}

@Composable
fun SettingsScreen(bridge: PlatformBridge, settings: SettingsStore, registry: ModelRegistry, installer: ModelInstaller, onRunWizard: () -> Unit) {
    val s by settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pack by remember { mutableStateOf<SpeechInput.LanguagePack?>(null) }
    var packNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { pack = bridge.speechInput?.languagePack() }
    ScreenColumn("Ayarlar") {
        Text("Model", fontWeight = FontWeight.SemiBold)
        val llms = registry.forPlatform(bridge.platform).filter { it.kind == "llm" }
        Text("Kurulu modellerden biri seçilmezse cihaza uygun olan kendiliğinden kullanılır. Hiç model kurulu değilse Jarvis deneme modunda çalışır.", color = JarvisColors.TextDim, fontSize = 12.sp)
        llms.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, modifier = Modifier.weight(1f))
                Switch(checked = s.llmId == m.id, onCheckedChange = { on -> settings.update { it.copy(llmId = if (on) m.id else null) } })
            }
        }
        Toggle("Sesli yanıt", s.voiceEnabled) { v -> settings.update { it.copy(voiceEnabled = v) } }
        Toggle("Sohbet modu (cevaptan sonra yeniden dinler)", s.conversationMode) { v -> settings.update { it.copy(conversationMode = v) } }
        Text("Konuşma hızı: ${(s.speechRate * 100).toInt()}%")
        Slider(value = s.speechRate, onValueChange = { v -> settings.update { it.copy(speechRate = v) } }, valueRange = 0.6f..1.6f)
        Toggle("Web araması (her sorguda onay istenir)", s.webSearch) { v -> settings.update { it.copy(webSearch = v) } }
        Toggle("Tam offline mod (tüm indirmeler kapalı)", s.fullOffline) { v -> settings.update { it.copy(fullOffline = v) } }
        Text("Ses tanıma: ${bridge.speechInput?.engineName ?: "yok"}", color = JarvisColors.TextDim, fontSize = 12.sp)
        when (pack) {
            SpeechInput.LanguagePack.READY -> Text("✓ Türkçe ses paketi telefonda yüklü", color = JarvisColors.Green, fontSize = 12.sp)
            SpeechInput.LanguagePack.DOWNLOADABLE -> {
                Text("Türkçe ses paketi yüklü değil: mikrofon bu yüzden çalışmıyor.", color = JarvisColors.Amber, fontSize = 12.sp)
                OutlinedButton(onClick = {
                    scope.launch {
                        packNote = installer.downloadSpeechPack()
                        pack = bridge.speechInput?.languagePack()
                    }
                }) { Text("Türkçe ses paketini indir") }
            }
            SpeechInput.LanguagePack.UNSUPPORTED ->
                Text("Telefonun cihaz içi ses tanıması Türkçe'yi desteklemiyor. Modeller ekranından Jarvis'in kendi ses tanımasını (Whisper) indirin; o zamana kadar yazarak kullanabilirsiniz.", color = JarvisColors.Amber, fontSize = 12.sp)
            else -> {}
        }
        packNote?.let { Text(it, fontSize = 12.sp) }
        Text("Ses sentezi: ${bridge.speechOutput?.engineName ?: "yok"}", color = JarvisColors.TextDim, fontSize = 12.sp)
        WakeSettings(bridge, settings)
        FirewallSettings(bridge)
        Button(onClick = onRunWizard) { Text("Kurulum sihirbazını aç") }
        Text("Jarvis ${bridge.appVersion}", color = JarvisColors.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

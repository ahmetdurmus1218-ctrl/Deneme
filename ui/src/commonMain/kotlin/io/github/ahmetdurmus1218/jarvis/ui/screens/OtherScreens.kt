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
import io.github.ahmetdurmus1218.jarvis.ui.VoiceChoice
import io.github.ahmetdurmus1218.jarvis.ui.VoiceGender
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
        OutlinedButton(onClick = {
            scope.launch { msg = foundText(installer.findLocal(items, askFolder = true)) }
        }) { Text("📂 Klasörde bul (indirmeden)") }
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
fun MemoryScreen(
    memory: MemoryStore,
    settings: SettingsStore,
    documents: DocumentIndex,
    bridge: PlatformBridge,
    learning: io.github.ahmetdurmus1218.jarvis.core.learning.Learning? = null,
) {
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
        Text("Rick'in sizin hakkınızda hatırladıkları. Cihazda şifreli tutulur; istediğinizi silebilir ya da hafızayı kapatabilirsiniz.", color = JarvisColors.TextDim, fontSize = 12.sp)
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

        learning?.let { LearningSection(it, settings) }

        Text("Belgelerim", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(
            "PDF, TXT ve MD dosyalarınızı ekleyin; Rick sorularınızı bunlardan kaynak göstererek cevaplar. Belgeler cihazdan çıkmaz.",
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
        Text("Rick'in yaptığı her internet isteği burada listelenir: zaman, alan adı, bayt ve onay.", color = JarvisColors.TextDim, fontSize = 12.sp)
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
        Text("Kurulu modellerden biri seçilmezse cihaza uygun olan kendiliğinden kullanılır. Dil modeli kurulu değilse yalnızca hazır komutlar ve kaynaklı Wikipedia / belge cevapları çalışır.", color = JarvisColors.TextDim, fontSize = 12.sp)
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
        Toggle("Kütüphanede yoksa internette bakmayı teklif et (her aramada onayınız istenir)", s.webOffer) { v -> settings.update { it.copy(webOffer = v) } }
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
                Text("Telefonun cihaz içi ses tanıması Türkçe'yi desteklemiyor. Modeller ekranından Rick'in kendi ses tanımasını (Whisper) indirin; o zamana kadar yazarak kullanabilirsiniz.", color = JarvisColors.Amber, fontSize = 12.sp)
            else -> {}
        }
        packNote?.let { Text(it, fontSize = 12.sp) }
        FloatingSettings(bridge, settings)
        VoicePicker(bridge, settings)
        Text("Ses sentezi: ${bridge.speechOutput?.engineName ?: "yok"}", color = JarvisColors.TextDim, fontSize = 12.sp)
        VoiceTest(bridge, s.speechRate)
        DisplaySettings(settings)
        WakeSettings(bridge, settings)
        FirewallSettings(bridge)
        Button(onClick = onRunWizard) { Text("Kurulum sihirbazını aç") }
        Text("Rick ${bridge.appVersion}", color = JarvisColors.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Result line of "Klasörde bul" (-1: cancelled). */
fun foundText(n: Int): String? = when {
    n < 0 -> null
    n == 0 -> "Eşleşen model dosyası bulunamadı (dosya adı, boyutu ve SHA-256 özeti kilit dosyasıyla aynı olmalı)."
    else -> "$n dosya bu cihazda bulundu ve doğrulandı; indirilmeyecek."
}

/**
 * "Ekranda Rick": the living face floating over every app. Tap it to talk, hold to open Rick;
 * asleep, two claps wake it. It cannot act inside other apps (no messages, no banking).
 */
@Composable
private fun FloatingSettings(bridge: PlatformBridge, settings: SettingsStore) {
    val floating = bridge.floating ?: return
    val s by settings.state.collectAsState()
    val running by floating.running.collectAsState()
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var allowed by remember { mutableStateOf(floating.allowed()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1_500); allowed = floating.allowed() } }
    Text("Ekranda Rick", fontWeight = FontWeight.SemiBold)
    Text(
        "Rick'in canlı yüzü her uygulamanın üstünde durur: dokunun konuşun, basılı tutun Rick açılsın, sürükleyip taşıyın. " +
            "Bir süre konuşmazsanız uyur; iki alkışla uyanır, günaydın der, haberleri sorar. Başka uygulamaların içinde işlem yapmaz (mesaj göndermez, bankaya girmez).",
        color = JarvisColors.TextDim, fontSize = 12.sp,
    )
    if (!allowed) {
        OutlinedButton(onClick = { floating.askPermission() }) { Text("İzin ver: diğer uygulamaların üzerinde göster") }
    }
    Toggle(if (running) "Ekranda Rick açık" else "Ekranda Rick'i göster", s.bubble && running) { on ->
        settings.update { it.copy(bubble = on) }
        scope.launch {
            note = if (on) floating.start() else { floating.stop(); null }
            if (note != null) settings.update { it.copy(bubble = false) }
        }
    }
    Toggle("Uyurken iki alkışla uyansın (mikrofon yalnızca telefonda dinler)", s.bubbleClapWake) { v -> settings.update { it.copy(bubbleClapWake = v) } }
    note?.let { Text(it, color = JarvisColors.Amber, fontSize = 12.sp) }
}

/**
 * "Rick'in sesi": a man's or a woman's voice. Rick's own Piper voice is a man's; the phone's own
 * offline Turkish voices (Google / Samsung) are often more natural and include women's voices.
 * Each one can be heard before it is chosen.
 */
@Composable
private fun VoicePicker(bridge: PlatformBridge, settings: SettingsStore) {
    val tts = bridge.speechOutput ?: return
    val s by settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf(tts.voices()) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // The phone's voice engine starts in the background: look again shortly.
        repeat(3) { kotlinx.coroutines.delay(1_000); voices = tts.voices() }
    }
    Text("Rick'in sesi", fontWeight = FontWeight.SemiBold)
    if (voices.size <= 1) {
        Text(
            if (bridge.platform == io.github.ahmetdurmus1218.jarvis.core.models.Platform.ANDROID) {
                "Şu an yalnızca bir ses var. Kadın sesi ve daha doğal sesler için telefona internetsiz Türkçe ses ekleyin."
            } else {
                "Bilgisayarda şimdilik Rick'in kendi (erkek) sesi var; açık kaynakta internetsiz Türkçe kadın sesi bulunmuyor."
            },
            color = JarvisColors.TextDim, fontSize = 12.sp,
        )
    }
    fun pick(v: VoiceChoice) {
        settings.update { it.copy(voiceId = v.id) }
        tts.choose(v.id)
        scope.launch { runCatching { tts.speak("Merhaba, artık bu sesle konuşacağım.", s.speechRate) } }
    }
    Row {
        listOf(VoiceGender.KADIN, VoiceGender.ERKEK).forEach { g ->
            OutlinedButton(onClick = {
                val v = voices.firstOrNull { it.gender == g && it.id == s.voiceId } ?: voices.firstOrNull { it.gender == g }
                if (v != null) { note = null; pick(v) } else {
                    note = "Hangisinin ${g.label.lowercase()} sesi olduğunu telefon söylemiyor: aşağıdaki sesleri ▶ ile dinleyip beğendiğinizi seçin." +
                        if (voices.size <= 1) " Liste boşsa önce \"Telefona ses ekle\"." else ""
                }
            }, modifier = Modifier.padding(end = 8.dp)) { Text("${g.label} sesi") }
        }
    }
    voices.forEach { v ->
        val selected = (s.voiceId ?: voices.firstOrNull()?.id) == v.id
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((if (selected) "● " else "○ ") + v.label + (v.gender?.let { " · ${it.label}" } ?: ""), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (v.note.isNotBlank()) Text(v.note, color = JarvisColors.TextDim, fontSize = 11.sp)
            }
            OutlinedButton(onClick = {
                val before = s.voiceId
                tts.choose(v.id)
                scope.launch {
                    runCatching { tts.speak("Merhaba, ben Rick. Sesim böyle.", s.speechRate) }
                    if (settings.state.value.voiceId != v.id) tts.choose(before)
                }
            }, modifier = Modifier.padding(end = 6.dp)) { Text("▶") }
            if (!selected) OutlinedButton(onClick = { pick(v) }) { Text("Seç") }
        }
    }
    if (bridge.platform == io.github.ahmetdurmus1218.jarvis.core.models.Platform.ANDROID) {
        OutlinedButton(onClick = {
            note = if (tts.openVoiceSettings()) {
                "Açılan ekranda: Tercih edilen motor → (Google) ayarlar → Ses verilerini yükle → Türkçe. İndirince buraya dönün; yeni sesler listede çıkar."
            } else {
                "Telefonun ses ayarları açılamadı: Ayarlar → Erişilebilirlik → Metin okuma çıkışı'ndan Türkçe ses ekleyebilirsiniz."
            }
            scope.launch { repeat(30) { kotlinx.coroutines.delay(2_000); voices = tts.voices() } }
        }) { Text("Telefona ses ekle") }
    }
    note?.let { Text(it, color = JarvisColors.Amber, fontSize = 12.sp) }
}

/** "🔊 Sesi dene": speaks the test sentence and says plainly why nothing was heard, if so. */
@Composable
private fun VoiceTest(bridge: PlatformBridge, rate: Float) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val tts = bridge.speechOutput
    OutlinedButton(enabled = tts != null && !busy, onClick = {
        scope.launch {
            busy = true
            note = "Konuşuyor…"
            runCatching { tts?.speak(TEST_SENTENCE, rate) }
            note = tts?.silentReason?.let { "🔇 $it" } ?: "Duymadıysanız telefonun medya sesini açın (yan ses tuşu, müzik sesi)."
            busy = false
        }
    }) { Text("🔊 Sesi dene") }
    note?.let { Text(it, color = JarvisColors.Amber, fontSize = 12.sp) }
}

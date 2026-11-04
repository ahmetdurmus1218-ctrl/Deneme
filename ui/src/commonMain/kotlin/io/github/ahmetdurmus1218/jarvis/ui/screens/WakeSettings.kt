package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.ahmetdurmus1218.jarvis.core.stt.ClapDetector
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import io.github.ahmetdurmus1218.jarvis.ui.PlatformBridge
import io.github.ahmetdurmus1218.jarvis.ui.SettingsStore
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Settings → Uyanış: when Rick sleeps, how it wakes, clap calibration and the secret rhythm. */
@Composable
fun WakeSettings(bridge: PlatformBridge, settings: SettingsStore) {
    val s by settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var calibrating by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0f) }
    var flash by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Uyanış", fontWeight = FontWeight.SemiBold)
        Text("Boşta kalınca uyku: " + if (s.sleepAfterMin == 0) "asla" else "${s.sleepAfterMin} dakika", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Asla", 5 to "5 dk", 15 to "15 dk", 30 to "30 dk").forEach { (m, label) ->
                OutlinedButton(onClick = { settings.update { it.copy(sleepAfterMin = m) } }) {
                    Text(label, color = if (s.sleepAfterMin == m) JarvisColors.Cyan else JarvisColors.TextDim, fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(WAKE_CLAP_AND_PHRASE to "Alkış + \"uyan Rick\"", WAKE_CLAP_ONLY to "Yalnızca alkış").forEach { (m, label) ->
                OutlinedButton(onClick = { settings.update { it.copy(wakeMode = m) } }) {
                    Text(label, color = if (s.wakeMode == m) JarvisColors.Cyan else JarvisColors.TextDim, fontSize = 12.sp)
                }
            }
        }
        if (bridge.micStream == null) {
            Text("Bu cihazda mikrofon yok: uykudan ekrana iki kez dokunarak çıkılır.", color = JarvisColors.TextDim, fontSize = 12.sp)
            return@Column
        }
        Text("Alkış eşiği: ×${s.clapThreshold.toInt()} (odanız gürültülüyse yükseltin)", fontSize = 13.sp)
        Slider(value = s.clapThreshold, onValueChange = { v -> settings.update { it.copy(clapThreshold = v) } }, valueRange = 4f..20f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { calibrating = !calibrating; recording = false }) { Text(if (calibrating) "Kalibrasyonu bitir" else "Kalibre et", fontSize = 12.sp) }
            OutlinedButton(onClick = { recording = !recording; calibrating = false }) { Text(if (recording) "İptal" else "Ritmini kaydet", fontSize = 12.sp) }
            if (s.clapRhythm != null) OutlinedButton(onClick = { settings.update { it.copy(clapRhythm = null) } }) { Text("Ritmi sil", fontSize = 12.sp) }
        }
        if (calibrating || recording) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(10.dp).background(JarvisColors.SurfaceHigh, RoundedCornerShape(5.dp))) {
                    Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).fillMaxHeight().background(if (level > 0.66f) JarvisColors.Amber else JarvisColors.Cyan, RoundedCornerShape(5.dp)))
                }
                Box(Modifier.size(16.dp).background(if (flash) JarvisColors.Green else JarvisColors.SurfaceHigh, CircleShape))
            }
            Text(
                status.ifEmpty { if (recording) "Ritminizi 3 kez çalın (en fazla 6 alkış)." else "Bir kez alkışlayın: nokta yanmalı. Konuşunca yanmamalı." },
                color = JarvisColors.TextDim, fontSize = 12.sp,
            )
        }
        s.clapRhythm?.let { Text("Gizli ritim açık: ${it.size + 1} alkış", color = JarvisColors.Green, fontSize = 12.sp) }
    }

    // Live microphone while calibrating or recording a rhythm (nothing is stored).
    val active = calibrating || recording
    DisposableEffect(active) {
        val mic = bridge.micStream
        if (!active || mic == null) return@DisposableEffect onDispose {}
        val single = ClapDetector(threshold = s.clapThreshold).apply { rhythm = listOf(-1L) } // never completes: only single claps
        val takes = mutableListOf<List<Long>>()
        val current = mutableListOf<Long>()
        status = ""
        val job = scope.launch {
            val ok = mic.start { buf, n ->
                single.threshold = settings.state.value.clapThreshold
                val before = single.clapsSeen
                single.feed(buf, n)
                level = single.level
                if (single.clapsSeen > before) {
                    flash = true
                    scope.launch { delay(250); flash = false }
                    if (recording) {
                        val now = NowProvider.System.now().epochMillis
                        if (current.isNotEmpty() && now - current.last() > 1_500) {
                            if (current.size >= 2) takes += current.toList()
                            current.clear()
                        }
                        current += now
                        status = "Kayıt ${takes.size + 1}/3 · ${current.size} alkış"
                    }
                }
                if (recording && current.size >= 2 && NowProvider.System.now().epochMillis - current.last() > 1_500) {
                    takes += current.toList()
                    current.clear()
                    if (takes.size >= 3) {
                        val gaps = takes.map(ClapDetector::rhythmOf)
                        val sameShape = gaps.all { it.size == gaps[0].size }
                        if (sameShape && gaps[0].size in 1..5) {
                            val avg = gaps[0].indices.map { i -> gaps.sumOf { it[i] } / gaps.size }
                            settings.update { it.copy(clapRhythm = avg) }
                            status = "Ritim kaydedildi."
                        } else {
                            status = "Üç çalış birbirini tutmadı, tekrar deneyin."
                        }
                        takes.clear()
                        recording = false
                    } else {
                        status = "Kayıt ${takes.size}/3 tamam."
                    }
                }
            }
            if (!ok) status = "Mikrofon açılamadı (izin gerekli olabilir)."
        }
        onDispose {
            job.cancel()
            mic.stop()
        }
    }
}

/** Settings → Güvenlik duvarı (installed Windows app): status, re-create the rules, remove them. */
@Composable
fun FirewallSettings(bridge: PlatformBridge) {
    val fw = bridge.firewall ?: return
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(fw.status()) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Güvenlik duvarı", fontWeight = FontWeight.SemiBold)
        Text(status, color = if (status.startsWith("Açık")) JarvisColors.Green else JarvisColors.Amber, fontSize = 12.sp)
        Text("İndirmeler yalnızca rick-kapi.exe ile, her seferinde UAC onayıyla ve en fazla 10 dakika açılan bir kapıdan yapılır.", color = JarvisColors.TextDim, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { scope.launch { busy = true; fw.install(); status = fw.status(); busy = false } }) { Text("Kuralları kur (UAC)", fontSize = 12.sp) }
            OutlinedButton(enabled = !busy, onClick = { scope.launch { busy = true; fw.remove(); status = fw.status(); busy = false } }) { Text("Kuralları kaldır", fontSize = 12.sp) }
        }
    }
}

/** Settings → Ekran: Mark 1 / Mark 2 and the city for the offline sun times. */
@Composable
fun DisplaySettings(settings: SettingsStore) {
    val s by settings.state.collectAsState()
    var cities by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Görünüm", fontWeight = FontWeight.SemiBold)
        Text("Tema", fontSize = 13.sp)
        io.github.ahmetdurmus1218.jarvis.ui.theme.Themes.all.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { p ->
                    val selected = s.theme == p.id
                    Row(
                        Modifier.weight(1f)
                            .background(p.background, RoundedCornerShape(10.dp))
                            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.glassBorder, RoundedCornerShape(10.dp))
                            .clickable { settings.update { it.copy(theme = p.id) } }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(p.accent, p.second, p.amber).forEach { c -> Box(Modifier.size(12.dp).background(c, CircleShape)) }
                        Text(p.label, color = p.text, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
        Text("Yazı boyutu: %${(s.fontScale * 100).toInt()}", fontSize = 13.sp)
        Slider(value = s.fontScale, onValueChange = { v -> settings.update { it.copy(fontScale = (v * 20).toInt() / 20f) } }, valueRange = 0.85f..1.4f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hareketi azalt (sakin çekirdek, sarsıntı ve ateş yok)", fontSize = 13.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = s.reduceMotion, onCheckedChange = { v -> settings.update { it.copy(reduceMotion = v) } })
        }
        Text("Rick'in görünüşü", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(true to "✳ Canlı simge", false to "◎ Çekirdek").forEach { (f, label) ->
                OutlinedButton(onClick = { settings.update { it.copy(face = f) } }) {
                    Text(label, color = if (s.face == f) JarvisColors.Cyan else JarvisColors.TextDim, fontSize = 12.sp)
                }
            }
        }
        Text("Ekran", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "Mark 1 · sade", 2 to "Mark 2 · yoğun HUD").forEach { (m, label) ->
                OutlinedButton(onClick = { settings.update { it.copy(hudMark = m) } }) {
                    Text(label, color = if (s.hudMark == m) JarvisColors.Cyan else JarvisColors.TextDim, fontSize = 12.sp)
                }
            }
        }
        Box {
            OutlinedButton(onClick = { cities = true }) { Text("Gün doğumu için konum: ${s.city} ▾", fontSize = 12.sp) }
            androidx.compose.material3.DropdownMenu(expanded = cities, onDismissRequest = { cities = false }) {
                io.github.ahmetdurmus1218.jarvis.core.time.Places.TR.forEach { p ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(p.name) }, onClick = { settings.update { it.copy(city = p.name) }; cities = false })
                }
            }
        }
    }
}

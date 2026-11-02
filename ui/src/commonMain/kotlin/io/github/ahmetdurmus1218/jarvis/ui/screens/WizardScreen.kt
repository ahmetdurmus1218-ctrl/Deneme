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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.models.LockedItem
import io.github.ahmetdurmus1218.jarvis.core.models.ModelRegistry
import io.github.ahmetdurmus1218.jarvis.core.models.formatBytes
import io.github.ahmetdurmus1218.jarvis.ui.DownloadState
import io.github.ahmetdurmus1218.jarvis.ui.ItemStatus
import io.github.ahmetdurmus1218.jarvis.ui.JarvisController
import io.github.ahmetdurmus1218.jarvis.ui.ModelInstaller
import io.github.ahmetdurmus1218.jarvis.ui.PlatformBridge
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlinx.coroutines.launch

const val TEST_SENTENCE = "Merhaba efendim, Jarvis hazır."

private enum class Step { CHECK, BUNDLE, DOWNLOAD, TEST, DONE }

/** First-run wizard: device check → bundle → one approval → download → hash check → test sentence. */
@Composable
fun WizardScreen(
    bridge: PlatformBridge,
    registry: ModelRegistry,
    installer: ModelInstaller,
    controller: JarvisController,
    onFinish: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.CHECK) }
    val device = remember { bridge.deviceInfo() }
    val bundle: List<LockedItem> = remember { registry.recommendedBundle(bridge.platform, device.totalRamGb) }
    val status by installer.status.collectAsState()
    val gate by bridge.downloader.state.collectAsState()
    var downloadOk by remember { mutableStateOf<Boolean?>(null) }
    var importMsg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(JarvisColors.Background).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Kurulum sihirbazı", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = JarvisColors.Cyan)
        when (step) {
            Step.CHECK -> {
                Text("1/4 · Cihaz kontrolü", color = JarvisColors.TextDim)
                Info("Cihaz", device.label)
                Info("Bellek (RAM)", "${(device.totalRamGb * 10).toInt() / 10.0} GB")
                Info("Boş alan", formatBytes(device.freeStorageBytes))
                Info("İndirme yolu", when (val g = gate) { is DownloadState.Ready -> g.label; DownloadState.Unavailable -> installer.gateMissingText() })
                device.notes.forEach { Text("• $it", color = JarvisColors.TextDim) }
                Button(onClick = { step = Step.BUNDLE }) { Text("Devam") }
                TextButton(onClick = onFinish) { Text("Şimdilik atla (deneme modu)") }
            }
            Step.BUNDLE -> {
                Text("2/4 · Önerilen paket", color = JarvisColors.TextDim)
                if (bundle.isEmpty()) {
                    Text("Kilit dosyasında bu platform için model yok.", color = JarvisColors.Amber)
                }
                bundle.forEach { ItemRow(it, status[it.id], bridge.models.isInstalled(it)) }
                val total = bundle.filterNot { bridge.models.isInstalled(it) }.sumOf { it.size }
                Text("Toplam indirme: ${formatBytes(total)}", fontWeight = FontWeight.SemiBold)
                if (total > device.freeStorageBytes) Text("Boş alan yetersiz görünüyor.", color = JarvisColors.Red)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = gate is DownloadState.Ready && bundle.isNotEmpty(),
                        onClick = {
                            step = Step.DOWNLOAD
                            installer.installInBackground(bundle, "İlk kurulum: dil modeli, ses tanıma, ses sentezi ve çevrimdışı Wikipedia.") {
                                downloadOk = it
                            }
                        },
                    ) { Text("Tek onayla indir") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            importMsg = when (val r = installer.importFromFile()) {
                                is ModelInstaller.ImportResult.Installed -> "Eklendi ve doğrulandı: ${r.item.title}"
                                is ModelInstaller.ImportResult.KeptUnverified -> "Doğrulanmadan eklendi: ${r.fileName}"
                                is ModelInstaller.ImportResult.Discarded -> r.reason
                                ModelInstaller.ImportResult.Cancelled -> null
                            }
                        }
                    }) { Text("Dosyadan yükle") }
                }
                importMsg?.let { Text(it, color = JarvisColors.Amber) }
                if (gate is DownloadState.Unavailable) Text(installer.gateMissingText(), color = JarvisColors.Amber)
                TextButton(onClick = { step = Step.TEST }) { Text("İndirmeden devam et") }
            }
            Step.DOWNLOAD -> {
                Text("3/4 · İndirme ve doğrulama", color = JarvisColors.TextDim)
                bundle.forEach { ItemRow(it, status[it.id], bridge.models.isInstalled(it)) }
                when (downloadOk) {
                    null -> Text("İndiriliyor… Başka ekrana geçebilirsiniz; indirme sürer.", color = JarvisColors.TextDim)
                    true -> Button(onClick = { step = Step.TEST }) { Text("Devam") }
                    false -> {
                        Text("Bazı dosyalar kurulamadı. Daha sonra Modeller ekranından tekrar deneyebilirsiniz.", color = JarvisColors.Amber)
                        Button(onClick = { step = Step.TEST }) { Text("Devam") }
                    }
                }
            }
            Step.TEST -> {
                Text("4/4 · Ses testi", color = JarvisColors.TextDim)
                Text("Test cümlesi: \"$TEST_SENTENCE\"")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { controller.speakTest(TEST_SENTENCE) }) { Text("Sesli oku") }
                    Button(onClick = { step = Step.DONE }) { Text("Bitir") }
                }
            }
            Step.DONE -> {
                Text(TEST_SENTENCE, fontSize = 22.sp, color = JarvisColors.Cyan)
                Button(onClick = onFinish) { Text("Sohbete geç") }
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = JarvisColors.TextDim)
        Text(value, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun ItemRow(item: LockedItem, status: ItemStatus?, installed: Boolean) {
    Column(
        Modifier.fillMaxWidth().background(JarvisColors.SurfaceHigh, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(item.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(formatBytes(item.size), color = JarvisColors.TextDim)
        }
        val (text, color) = when {
            installed || status is ItemStatus.Done -> "✓ Kurulu, SHA-256 doğrulandı" to JarvisColors.Green
            status is ItemStatus.Downloading -> "İndiriliyor ${formatBytes(status.bytes)} / ${formatBytes(status.total)}" to JarvisColors.Cyan
            status is ItemStatus.Verifying -> "SHA-256 doğrulanıyor…" to JarvisColors.Cyan
            status is ItemStatus.Failed -> (if (status.hashMismatch) "✗ " else "") + status.message to JarvisColors.Red
            status is ItemStatus.Waiting -> "Sırada" to JarvisColors.TextDim
            else -> "Kurulu değil · ${item.host}" to JarvisColors.TextDim
        }
        Text(text, color = color, fontSize = 13.sp)
        if (status is ItemStatus.Downloading && status.total > 0) {
            LinearProgressIndicator(progress = { (status.bytes.toFloat() / status.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

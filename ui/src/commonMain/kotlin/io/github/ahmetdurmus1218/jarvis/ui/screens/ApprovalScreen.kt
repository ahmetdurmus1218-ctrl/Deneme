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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.models.formatBytes
import io.github.ahmetdurmus1218.jarvis.ui.ApprovalRequest
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors

/**
 * Section 3.3: what will happen, target domains, size, why, expected SHA-256 (and for web search
 * the full outgoing text). One approval = one request; nothing is remembered.
 */
@Composable
fun ApprovalScreen(req: ApprovalRequest, authLabel: String, onAnswer: (Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(JarvisColors.Background).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(req.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (req.unverified) JarvisColors.Red else JarvisColors.Cyan)
        if (req.unverified) {
            Text(
                "⚠ Doğrulanamadı — bu dosya kilit listesinde yok. Onay ${req.step}/2.",
                color = JarvisColors.Red, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(JarvisColors.Red.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(10.dp),
            )
        }
        Field("Ne yapılacak", req.action)
        if (req.hosts.isNotEmpty()) Field("Hedef alan adı", req.hosts.joinToString("\n"))
        req.totalBytes?.let { Field("Boyut", formatBytes(it)) }
        Field("Neden gerekli", req.reason)
        req.outgoingText?.let { Field("Dışarı gidecek metnin tamamı", it) }
        Text("Dosyalar ve beklenen SHA-256", color = JarvisColors.TextDim, fontSize = 13.sp)
        req.lines.forEach { l ->
            Column(Modifier.fillMaxWidth().background(JarvisColors.SurfaceHigh, RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text(l.name + (l.size?.let { "  ·  " + formatBytes(it) } ?: ""), fontWeight = FontWeight.SemiBold)
                l.sha256?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = JarvisColors.TextDim) }
            }
        }
        Text(
            "Onay yalnızca bu istek için geçerlidir. Kalıcı izin verilmez. Onayladıktan sonra $authLabel istenecek.",
            color = JarvisColors.TextDim, fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onAnswer(false) }) { Text("Reddet") }
            Button(
                onClick = { onAnswer(true) },
                colors = if (req.unverified) ButtonDefaults.buttonColors(containerColor = JarvisColors.Red) else ButtonDefaults.buttonColors(),
            ) { Text("Onayla") }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, color = JarvisColors.TextDim, fontSize = 13.sp)
        Text(value, fontSize = 16.sp)
    }
}

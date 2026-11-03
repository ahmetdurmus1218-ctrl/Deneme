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
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.home.Brew
import io.github.ahmetdurmus1218.jarvis.core.home.Foam
import io.github.ahmetdurmus1218.jarvis.core.home.HomeAction
import io.github.ahmetdurmus1218.jarvis.core.home.HomeState
import io.github.ahmetdurmus1218.jarvis.core.home.Room
import io.github.ahmetdurmus1218.jarvis.core.home.SmartHome
import io.github.ahmetdurmus1218.jarvis.core.home.Sugar
import io.github.ahmetdurmus1218.jarvis.core.home.Tone
import io.github.ahmetdurmus1218.jarvis.core.time.NowProvider
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Short notice shown on every screen when Jarvis changes a device ("SALON IŞIĞI · AÇIK"). */
fun HomeAction.notice(): String? = when (this) {
    is HomeAction.SetLight -> "${room.label.uppercase()} IŞIĞI · " + when {
        on == false -> "KAPALI"
        brightness != null || tone != null -> listOfNotNull(brightness?.let { "%$it" }, tone?.label?.uppercase()).joinToString(" · ")
        else -> "AÇIK"
    }
    is HomeAction.AllLights -> if (on) "TÜM IŞIKLAR · AÇIK" else "TÜM IŞIKLAR · KAPALI"
    is HomeAction.MakeTurkishCoffee -> "TÜRK KAHVESİ · PİŞİYOR"
    is HomeAction.MakeFilterCoffee -> "FİLTRE KAHVE · $cups FİNCAN"
    else -> null
}

/** The simulated home: lights and coffee machines, live, with the same actions as the voice commands. */
@Composable
fun HomePanel(home: SmartHome, onClose: () -> Unit) {
    var state by remember { mutableStateOf(home.state(now())) }
    var at by remember { mutableStateOf(now()) }
    fun refresh() { at = now(); state = home.state(at) }
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                withInfiniteAnimationFrameMillis { }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!isActive) throw e
                return@LaunchedEffect // UI tests: refreshed on every tap instead
            }
            refresh()
            delay(250)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(16.dp)
                .background(JarvisColors.Surface, RoundedCornerShape(18.dp))
                .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("EV", color = JarvisColors.Cyan, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("SİMÜLASYON", color = JarvisColors.Amber, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    modifier = Modifier.border(1.dp, JarvisColors.Amber, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                TextButton(onClick = onClose) { Text("KAPAT", color = JarvisColors.TextDim, fontSize = 11.sp) }
            }
            Text("Cihazlar Jarvis'in içinde canlandırılır; ağa bağlanılmaz. Sesle de olur: \"salonun ışığını aç\", \"bana bol köpüklü bir Türk kahvesi yap\".",
                color = JarvisColors.TextDim, fontSize = 11.sp)

            for (room in Room.entries) {
                val l = state.light(room)
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (l.on) "💡 " else "○ ") + room.label, color = if (l.on) JarvisColors.Amber else JarvisColors.Text, modifier = Modifier.weight(1f))
                        Switch(checked = l.on, onCheckedChange = { home.apply(HomeAction.SetLight(room, on = it), now()); refresh() })
                    }
                    if (l.on) {
                        Slider(
                            value = l.brightness.toFloat(), valueRange = 1f..100f,
                            onValueChange = { v -> home.set { s -> s.copy(lights = s.lights.map { if (it.room == room) it.copy(brightness = v.toInt()) else it }) }; refresh() },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("%${l.brightness}", color = JarvisColors.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Tone.entries.forEach { t -> SmallChip(l.tone == t, t.label) { home.apply(HomeAction.SetLight(room, tone = t), now()); refresh() } }
                        }
                    }
                }
            }

            val tc = state.turkish
            Card {
                Text("☕ Türk kahve makinesi", color = JarvisColors.Text)
                var foam by remember { mutableStateOf(Foam.LOTS) }
                var sugar by remember { mutableStateOf(Sugar.MEDIUM) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Foam.entries.forEach { f -> SmallChip(foam == f, f.label) { foam = f } } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Sugar.entries.forEach { s -> SmallChip(sugar == s, s.label) { sugar = s } } }
                BrewRow(tc.state, tc.progress(at), "Pişir", "Hazır: ${tc.foam.label}, ${tc.sugar.label}") {
                    home.apply(HomeAction.MakeTurkishCoffee(foam, sugar), now()); refresh()
                }
            }

            val fc = state.filter
            Card {
                Text("☕ Filtre kahve makinesi", color = JarvisColors.Text)
                var cups by remember { mutableStateOf(2) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..4).forEach { c -> SmallChip(cups == c, "$c fincan") { cups = c } } }
                BrewRow(fc.state, fc.progress(at), "Demle", "Hazır, sıcak tutuluyor") {
                    home.apply(HomeAction.MakeFilterCoffee(cups), now()); refresh()
                }
            }
        }
    }
}

private fun now() = NowProvider.System.now().epochMillis

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(JarvisColors.SurfaceHigh, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun BrewRow(state: Brew, progress: Float, action: String, ready: String, start: () -> Unit) {
    when (state) {
        Brew.BREWING -> {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("Hazırlanıyor… %${(progress * 100).toInt()}", color = JarvisColors.Amber, fontSize = 12.sp)
        }
        Brew.READY -> {
            Text("✔ $ready", color = JarvisColors.Green, fontSize = 12.sp)
            Button(onClick = start) { Text("Yeniden $action".lowercase().replaceFirstChar { it.uppercase() }) }
        }
        Brew.IDLE -> Button(onClick = start) { Text(action) }
    }
}

@Composable
private fun SmallChip(selected: Boolean, label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 11.sp,
        color = if (selected) JarvisColors.Background else JarvisColors.Cyan,
        modifier = Modifier
            .background(if (selected) JarvisColors.Cyan else Color.Transparent, RoundedCornerShape(50))
            .border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

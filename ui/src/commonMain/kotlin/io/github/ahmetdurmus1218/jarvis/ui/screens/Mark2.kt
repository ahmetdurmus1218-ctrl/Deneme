package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.text.Turkish
import io.github.ahmetdurmus1218.jarvis.core.time.Now
import io.github.ahmetdurmus1218.jarvis.core.time.Places
import io.github.ahmetdurmus1218.jarvis.core.time.SunTimes
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What Mark 2 shows. Every value is real; what the device cannot tell is shown as "yok". */
data class HudStats(
    val brain: String,
    val voice: String,
    val wiki: Boolean,
    val facts: Int,
    val documents: Int,
    val books: Int,
    val lightsOn: Int,
    val coffee: String,
    val battery: Int?,
    val memoryMb: Long?,
)

/**
 * Mark 2: the dense HUD around the chat stage — month ruler on top; on wide screens a clock ring
 * and system panel on the left, the sun ring (offline sunrise/sunset), home and memory panels and
 * session metrics on the right. On a phone it folds into one status strip.
 */
@Composable
fun Mark2Frame(now: Now, city: String, stats: HudStats, content: @Composable () -> Unit) {
    var fps by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        var frames = 0
        var since = -1L
        while (isActive) {
            val t = try { withInfiniteAnimationFrameMillis { it } } catch (e: kotlinx.coroutines.CancellationException) {
                if (!isActive) throw e
                return@LaunchedEffect
            }
            if (since < 0) since = t
            frames++
            if (t - since >= 1_000) { fps = frames; frames = 0; since = t }
        }
    }
    val place = Places.byName(city)
    val sun = remember(now.year, now.month, now.day, city) { SunTimes.compute(now.year, now.month, now.day, place) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Column(Modifier.fillMaxSize()) {
            DayRuler(now)
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(220.dp).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClockRing(now, Modifier.size(170.dp).align(Alignment.CenterHorizontally))
                        Panel("SİSTEM") {
                            Line("Beyin", stats.brain)
                            Line("Ses", stats.voice)
                            Line("Wikipedia", if (stats.wiki) "hazır" else "kurulu değil")
                            Line("Ağ", "KAPALI · çevrimdışı")
                            Line("Pil", stats.battery?.let { "%$it" } ?: "yok")
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                    Column(Modifier.width(240.dp).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SunRing(now, sun, Modifier.size(170.dp).align(Alignment.CenterHorizontally))
                        Text("${place.name} · ☀ ${SunTimes.hhmm(sun.sunriseMin)} → ${SunTimes.hhmm(sun.sunsetMin)}", color = JarvisColors.TextDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Panel("EV · SİMÜLASYON") {
                            Line("Işıklar", "${stats.lightsOn}/2 açık")
                            Line("Kahve", stats.coffee)
                        }
                        Panel("HAFIZA") {
                            Line("Bilgi", "${stats.facts}")
                            Line("Belge", "${stats.documents}")
                            Line("Kitap", "${stats.books}")
                        }
                        Panel("OTURUM") {
                            Line("FPS", if (fps > 0) "$fps" else "yok")
                            Line("Bellek", stats.memoryMb?.let { "$it MB" } ?: "yok")
                        }
                    }
                }
            } else {
                Text(
                    "${now.hhmm()} · ☀ ${SunTimes.hhmm(sun.sunriseMin)}–${SunTimes.hhmm(sun.sunsetMin)} · pil ${stats.battery?.let { "%$it" } ?: "yok"} · " +
                        "${stats.facts} bilgi · ${stats.books} kitap · ışık ${stats.lightsOn}/2 · ${if (fps > 0) "$fps FPS" else "FPS yok"}",
                    color = JarvisColors.TextDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
        }
    }
}

@Composable
private fun DayRuler(now: Now) {
    val days = daysInMonth(now.year, now.month)
    Row(Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Turkish.monthName(now.month).uppercase().take(3), color = JarvisColors.Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(30.dp))
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val step = size.width / days
            for (d in 1..days) {
                val x = (d - 0.5f) * step
                val today = d == now.day
                val past = d < now.day
                drawLine(
                    if (today) JarvisColors.Cyan else JarvisColors.Cyan.copy(alpha = if (past) 0.45f else 0.18f),
                    Offset(x, size.height * (if (today) 0.1f else 0.45f)), Offset(x, size.height * 0.9f),
                    strokeWidth = if (today) 3f else 1.5f,
                )
            }
            val tx = (now.day - 0.5f) * step
            drawRect(JarvisColors.Cyan.copy(alpha = 0.12f), topLeft = Offset(tx - step / 2, 0f), size = Size(step, size.height))
        }
        Text("${now.day}/$days", color = JarvisColors.Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 6.dp))
    }
}

private fun daysInMonth(y: Int, m: Int): Int = when (m) {
    2 -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

@Composable
private fun ClockRing(now: Now, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 * 0.92f
            val c = Offset(size.width / 2, size.height / 2)
            for (i in 0 until 60) {
                val a = (i / 60f) * 2 * PI.toFloat() - PI.toFloat() / 2
                val long = i % 5 == 0
                val r1 = r * if (long) 0.86f else 0.92f
                drawLine(JarvisColors.Cyan.copy(alpha = if (long) 0.8f else 0.3f), Offset(c.x + r1 * cos(a), c.y + r1 * sin(a)), Offset(c.x + r * cos(a), c.y + r * sin(a)), strokeWidth = if (long) 2f else 1f)
            }
            val dayFrac = (now.hour * 60 + now.minute) / 1440f
            drawArc(JarvisColors.Cyan, -90f, 360f * dayFrac, false, topLeft = Offset(c.x - r * 0.75f, c.y - r * 0.75f), size = Size(r * 1.5f, r * 1.5f), style = Stroke(width = 4f, cap = StrokeCap.Round))
            drawArc(JarvisColors.Violet.copy(alpha = 0.7f), -90f, 360f * (now.minute / 60f), false, topLeft = Offset(c.x - r * 0.64f, c.y - r * 0.64f), size = Size(r * 1.28f, r * 1.28f), style = Stroke(width = 2f, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(now.hhmm(), color = JarvisColors.Text, fontSize = 26.sp, fontFamily = FontFamily.Monospace)
            Text("${now.day} ${Turkish.monthName(now.month)}", color = JarvisColors.TextDim, fontSize = 11.sp)
            Text(Turkish.dayName(now.isoDayOfWeek), color = JarvisColors.TextDim, fontSize = 11.sp)
        }
    }
}

/** A 24-hour dial: the daylight arc between sunrise and sunset and a marker for now. */
@Composable
private fun SunRing(now: Now, sun: SunTimes.Day, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 * 0.85f
            val c = Offset(size.width / 2, size.height / 2)
            fun ang(min: Int) = min / 1440f * 360f - 90f
            drawCircle(JarvisColors.CyanDim.copy(alpha = 0.5f), radius = r, center = c, style = Stroke(width = 2f))
            val rise = sun.sunriseMin
            val set = sun.sunsetMin
            if (rise != null && set != null) {
                drawArc(JarvisColors.Amber, ang(rise), (set - rise) / 1440f * 360f, false, topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r), style = Stroke(width = 6f, cap = StrokeCap.Round))
            }
            val nowMin = now.hour * 60 + now.minute
            val a = ang(nowMin) / 180f * PI.toFloat()
            drawCircle(Color.White, radius = 6f, center = Offset(c.x + r * cos(a), c.y + r * sin(a)))
            for (h in 0 until 24 step 6) {
                val aa = ang(h * 60) / 180f * PI.toFloat()
                drawLine(JarvisColors.TextDim, Offset(c.x + r * 0.88f * cos(aa), c.y + r * 0.88f * sin(aa)), Offset(c.x + r * 1.06f * cos(aa), c.y + r * 1.06f * sin(aa)), strokeWidth = 2f)
            }
        }
        val rise = sun.sunriseMin
        val set = sun.sunsetMin
        val nowMin = now.hour * 60 + now.minute
        val label = when {
            rise == null || set == null -> "güneş yok"
            nowMin < rise -> "doğuşa ${(rise - nowMin) / 60} sa ${(rise - nowMin) % 60} dk"
            nowMin < set -> "batışa ${(set - nowMin) / 60} sa ${(set - nowMin) % 60} dk"
            else -> "gün bitti"
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GÜNEŞ", color = JarvisColors.Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(label, color = JarvisColors.Text, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(JarvisColors.Glass, RoundedCornerShape(10.dp)).border(1.dp, JarvisColors.GlassBorder, RoundedCornerShape(10.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = JarvisColors.Cyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
        content()
    }
}

@Composable
private fun Line(k: String, v: String) {
    Row {
        Text(k, color = JarvisColors.TextDim, fontSize = 11.sp, modifier = Modifier.width(72.dp))
        Text(v, color = JarvisColors.Text, fontSize = 11.sp, maxLines = 1)
    }
}

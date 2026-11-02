package io.github.ahmetdurmus1218.jarvis.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The HUD core: a glowing orb that breathes when idle, pulses with the microphone level while
 * listening, spins while thinking and trembles while speaking.
 */
@Composable
fun HudCore(mode: CoreMode, level: Float, modifier: Modifier = Modifier, size: Dp = 180.dp) {
    val t = rememberInfiniteTransition()
    val breath by t.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(2600), RepeatMode.Reverse))
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (mode == CoreMode.THINKING) 1400 else 9000, easing = LinearEasing)))
    val tremble by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(420, easing = LinearEasing)))
    val levelAnim by animateFloatAsState(level)

    val accent = when (mode) {
        CoreMode.LISTENING -> JarvisColors.Amber
        CoreMode.THINKING -> JarvisColors.Cyan
        CoreMode.SPEAKING -> JarvisColors.Cyan
        CoreMode.IDLE -> JarvisColors.CyanDim
    }

    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f
        val speakingAmp = if (mode == CoreMode.SPEAKING) 0.06f * sin(tremble * 3) + 0.04f * sin(tremble * 7) else 0f
        val listenAmp = if (mode == CoreMode.LISTENING) 0.12f * levelAnim else 0f
        val scale = breath + speakingAmp + listenAmp

        // glow
        drawCircle(
            brush = Brush.radialGradient(
                listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.08f), Color.Transparent),
                center = c, radius = r,
            ),
            radius = r, center = c,
        )
        // core
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), accent, accent.copy(alpha = 0.2f)), center = c, radius = r * 0.42f * scale),
            radius = r * 0.42f * scale, center = c,
        )
        // rotating segmented rings
        rotate(spin, c) {
            for (i in 0 until 12) {
                val a0 = i * 30f
                drawArc(
                    color = accent.copy(alpha = if (i % 3 == 0) 0.9f else 0.35f),
                    startAngle = a0, sweepAngle = 18f, useCenter = false,
                    topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.62f),
                    size = androidx.compose.ui.geometry.Size(r * 1.24f, r * 1.24f),
                    style = Stroke(width = r * 0.025f),
                )
            }
        }
        rotate(-spin * 0.6f, c) {
            drawArc(
                color = accent.copy(alpha = 0.6f), startAngle = 0f, sweepAngle = 250f, useCenter = false,
                topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f),
                size = androidx.compose.ui.geometry.Size(r * 1.56f, r * 1.56f),
                style = Stroke(width = r * 0.012f),
            )
        }
        // orbiting dots
        for (i in 0 until 3) {
            val ang = Math2.rad(spin * (1 + i * 0.3f) + i * 120f)
            val rr = r * (0.88f + 0.04f * i)
            drawCircle(accent, radius = r * 0.025f, center = Offset(c.x + rr * cos(ang), c.y + rr * sin(ang)))
        }
    }
}

private object Math2 {
    fun rad(deg: Float): Float = (deg / 180f * PI).toFloat()
}

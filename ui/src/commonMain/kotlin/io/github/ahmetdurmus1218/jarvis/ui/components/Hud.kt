package io.github.ahmetdurmus1218.jarvis.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Accent colour of each state; the core blends between them. */
fun CoreMode.accent(): Color = when (this) {
    CoreMode.IDLE -> JarvisColors.Cyan
    CoreMode.LISTENING -> JarvisColors.Amber
    CoreMode.THINKING -> JarvisColors.Violet
    CoreMode.SPEAKING -> JarvisColors.CyanBright
}

/**
 * The living core of Rick. Layers, outside in: a slowly turning tick ring, counter-rotating
 * segment arcs, a scanner arc (fast while thinking), 72 bars that react to the voice (the
 * microphone while listening, a speech-like envelope while speaking, a travelling wave while
 * thinking, a soft shimmer when idle), the breathing inner orb, and orbiting particles.
 * Everything is drawn on one Canvas from a single animated clock.
 */
@Composable
fun HudCore(mode: CoreMode, level: Float, modifier: Modifier = Modifier, size: Dp = 260.dp) {
    val transition = rememberInfiniteTransition()
    // A seconds counter that wraps every 10 minutes; all motion is derived from it.
    val clock by transition.animateFloat(0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing)))
    val accent by animateColorAsState(mode.accent(), tween(500))
    val activity by animateFloatAsState(
        when (mode) {
            CoreMode.IDLE -> 0.15f
            CoreMode.LISTENING -> 0.55f + 0.45f * level
            CoreMode.THINKING -> 0.7f
            CoreMode.SPEAKING -> 0.85f
        },
        tween(250),
    )
    val levelAnim by animateFloatAsState(level, tween(90))

    val calm = io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi.reduceMotion
    Canvas(modifier.size(size)) {
        // "Hareketi azalt": the same core, turning six times slower.
        val t = if (calm) clock / 6f else clock
        val c = center
        val r = this.size.minDimension / 2f
        val spinBoost = if (mode == CoreMode.THINKING) 4f else 1f

        // 1. Halo
        drawCircle(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.10f + 0.20f * activity), accent.copy(alpha = 0.04f), Color.Transparent),
                center = c, radius = r,
            ),
            radius = r, center = c,
        )

        // 2. Tick ring
        rotate(t * 4f, c) {
            for (i in 0 until 120) {
                val major = i % 10 == 0
                val a = rad(i * 3f)
                val r0 = r * (if (major) 0.90f else 0.93f)
                val r1 = r * 0.97f
                drawLine(
                    accent.copy(alpha = if (major) 0.75f else 0.28f),
                    Offset(c.x + r0 * cos(a), c.y + r0 * sin(a)),
                    Offset(c.x + r1 * cos(a), c.y + r1 * sin(a)),
                    strokeWidth = if (major) r * 0.010f else r * 0.005f,
                )
            }
        }

        // 3. Counter-rotating segment arcs
        rotate(-t * 12f * spinBoost, c) {
            for (i in 0 until 3) arc(accent.copy(alpha = 0.85f), c, r * 0.84f, i * 120f, 70f, r * 0.022f)
        }
        rotate(t * 20f * spinBoost, c) {
            for (i in 0 until 6) arc(accent.copy(alpha = 0.40f), c, r * 0.78f, i * 60f + 10f, 34f, r * 0.010f)
        }

        // 4. Thin ring with a scanner sweep
        drawCircle(accent.copy(alpha = 0.18f), radius = r * 0.70f, center = c, style = Stroke(width = r * 0.006f))
        val sweep = (t * (if (mode == CoreMode.THINKING) 260f else 45f)) % 360f
        drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.9f)), center = c),
            startAngle = sweep - 50f, sweepAngle = 50f, useCenter = false,
            topLeft = Offset(c.x - r * 0.70f, c.y - r * 0.70f), size = Size(r * 1.40f, r * 1.40f),
            style = Stroke(width = r * 0.018f, cap = StrokeCap.Round),
        )

        // 5. Voice bars
        val bars = 72
        val speech = speechEnvelope(t)
        for (i in 0 until bars) {
            val ang = i * 360f / bars
            val a = rad(ang - 90f)
            val noise = 0.5f + 0.5f * sin(i * 1.7f + t * 7.3f) * cos(i * 0.9f - t * 4.1f)
            val amp = when (mode) {
                CoreMode.LISTENING -> (0.15f + 0.85f * levelAnim) * (0.45f + 0.55f * noise)
                CoreMode.SPEAKING -> speech * (0.35f + 0.65f * noise)
                CoreMode.THINKING -> max(0f, sin(rad(ang * 3f) - t * 6f)) * 0.55f
                CoreMode.IDLE -> 0.10f * (0.5f + 0.5f * sin(i * 0.7f + t * 1.5f))
            }
            val r0 = r * 0.50f
            val r1 = r0 + r * (0.025f + 0.15f * amp)
            drawLine(
                accent.copy(alpha = 0.35f + 0.6f * amp),
                Offset(c.x + r0 * cos(a), c.y + r0 * sin(a)),
                Offset(c.x + r1 * cos(a), c.y + r1 * sin(a)),
                strokeWidth = r * 0.016f, cap = StrokeCap.Round,
            )
        }

        // 6. Inner orb, breathing (and swelling with the voice)
        val breath = 1f + 0.035f * sin(t * 2.4f)
        val voice = when (mode) {
            CoreMode.LISTENING -> 0.10f * levelAnim
            CoreMode.SPEAKING -> 0.08f * speech
            else -> 0f
        }
        val orb = r * 0.34f * (breath + voice)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.95f), accent.copy(alpha = 0.95f), accent.copy(alpha = 0.25f), Color.Transparent),
                center = c, radius = orb * 1.25f,
            ),
            radius = orb * 1.25f, center = c,
        )
        drawCircle(accent.copy(alpha = 0.9f), radius = r * 0.42f, center = c, style = Stroke(width = r * 0.008f))
        rotate(-t * 30f * spinBoost, c) {
            for (i in 0 until 4) arc(Color.White.copy(alpha = 0.55f), c, r * 0.27f, i * 90f, 40f, r * 0.010f)
        }

        // 7. Orbiting particles
        for (i in 0 until 28) {
            val seed = i * 0.6180339f
            val speed = 8f + (seed * 97f) % 30f
            val ang = rad(seed * 360f * 7f + t * speed * (if (i % 2 == 0) 1f else -1f))
            val rr = r * (0.58f + (seed * 13f) % 0.40f) + r * 0.015f * sin(t * 3f + i)
            val twinkle = 0.25f + 0.75f * abs(sin(t * (1.3f + seed) + i))
            drawCircle(
                accent.copy(alpha = (0.25f + 0.55f * activity) * twinkle),
                radius = r * (0.006f + 0.006f * ((i * 7) % 3)),
                center = Offset(c.x + rr * cos(ang), c.y + rr * sin(ang)),
            )
        }
    }
}

/** A speech-like loudness curve (syllables and phrases) for the bars while Rick talks. */
private fun speechEnvelope(t: Float): Float {
    val syllables = abs(sin(t * 9.0f))
    val phrase = 0.6f + 0.4f * sin(t * 2.3f)
    return (0.25f + 0.75f * syllables * phrase).coerceIn(0f, 1f)
}

private fun DrawScope.arc(color: Color, c: Offset, radius: Float, start: Float, sweep: Float, width: Float) {
    drawArc(
        color, startAngle = start, sweepAngle = sweep, useCenter = false,
        topLeft = Offset(c.x - radius, c.y - radius), size = Size(radius * 2, radius * 2),
        style = Stroke(width = width, cap = StrokeCap.Round),
    )
}

private fun rad(deg: Float): Float = (deg / 180f * PI).toFloat()

/** Deep-space backdrop: a soft central glow, a faint grid and a vignette. */
fun Modifier.hudBackground(accent: Color = JarvisColors.Cyan): Modifier = drawBehind {
    drawRect(JarvisColors.Background)
    drawCircle(
        Brush.radialGradient(
            listOf(accent.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width / 2f, size.height * 0.36f), radius = size.maxDimension * 0.6f,
        ),
        radius = size.maxDimension * 0.6f, center = Offset(size.width / 2f, size.height * 0.36f),
    )
    val step = 28.dp.toPx()
    val grid = accent.copy(alpha = 0.035f)
    var x = 0f
    while (x < size.width) {
        drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
    drawRect(
        Brush.radialGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
            center = center, radius = size.maxDimension * 0.75f,
        ),
    )
}

package io.github.ahmetdurmus1218.jarvis.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** What the face is doing, for tests and the floating bubble. */
enum class FaceMood { SLEEPING, IDLE, LISTENING, THINKING, SPEAKING }

fun faceMood(mode: CoreMode, sleeping: Boolean): FaceMood = when {
    sleeping -> FaceMood.SLEEPING
    mode == CoreMode.LISTENING -> FaceMood.LISTENING
    mode == CoreMode.THINKING -> FaceMood.THINKING
    mode == CoreMode.SPEAKING -> FaceMood.SPEAKING
    else -> FaceMood.IDLE
}

/**
 * Rick's living face: a glowing head that breathes and sways, eyes that blink at their own pace
 * and look around while waiting, look straight at the user (pupils wide, brows up) while
 * listening, glance up while thinking, and a mouth that moves while Rick speaks. Asleep, the eyes
 * close and "z"s float up. [level] is the microphone level while listening (0…1). One Canvas, one
 * clock; the random glances and blinks come from two small loops.
 */
@Composable
fun RickFace(
    mode: CoreMode,
    level: Float,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    sleeping: Boolean = false,
    /** Where to look (-1…1 on both axes), e.g. at the user's hand; null: its own glances. */
    lookAt: Offset? = null,
) {
    val mood = faceMood(mode, sleeping)
    val transition = rememberInfiniteTransition()
    // Seconds, wrapping every 10 minutes; all continuous motion is derived from it.
    val clock by transition.animateFloat(0f, 600f, infiniteRepeatable(tween(600_000, easing = LinearEasing)))
    val accent by animateColorAsState(if (sleeping) JarvisColors.CyanDim else mode.accent(), tween(600))

    var gazeTarget by remember { mutableStateOf(Offset.Zero) }
    var tiltTarget by remember { mutableStateOf(0f) }
    var blink by remember { mutableStateOf(0f) }
    val calm = JarvisUi.reduceMotion

    // Where the eyes look and how the head tilts, by mood.
    LaunchedEffect(mood, calm) {
        while (true) {
            fun r(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
            when (mood) {
                FaceMood.SLEEPING -> { gazeTarget = Offset(0f, 0.3f); tiltTarget = -6f; delay(4_000) }
                FaceMood.LISTENING -> { gazeTarget = Offset(r(-0.08f, 0.08f), r(-0.05f, 0.08f)); tiltTarget = r(5f, 9f); delay(Random.nextLong(700, 1_500)) }
                FaceMood.THINKING -> { gazeTarget = Offset(r(0.45f, 0.75f), r(-0.75f, -0.5f)); tiltTarget = r(-7f, -3f); delay(Random.nextLong(600, 1_300)) }
                FaceMood.SPEAKING -> { gazeTarget = Offset(r(-0.18f, 0.18f), r(-0.1f, 0.12f)); tiltTarget = r(-3f, 3f); delay(Random.nextLong(500, 1_400)) }
                FaceMood.IDLE -> {
                    // Waiting: looks around now and then, and back at the user.
                    gazeTarget = if (calm || Random.nextFloat() < 0.4f) Offset.Zero else Offset(r(-0.85f, 0.85f), r(-0.5f, 0.45f))
                    tiltTarget = if (calm) 0f else r(-5f, 5f)
                    delay(Random.nextLong(1_200, 3_800))
                }
            }
        }
    }
    // Blinking: every few seconds, sometimes twice.
    LaunchedEffect(sleeping) {
        if (sleeping) { blink = 1f; return@LaunchedEffect }
        blink = 0f
        while (true) {
            delay(Random.nextLong(2_200, 5_500))
            repeat(if (Random.nextFloat() < 0.2f) 2 else 1) {
                for (i in 1..5) { blink = i / 5f; delay(14) }
                for (i in 4 downTo 0) { blink = i / 5f; delay(18) }
                delay(90)
            }
        }
    }
    val gaze by animateOffsetAsState(
        if (lookAt != null && !sleeping) Offset(lookAt.x.coerceIn(-1f, 1f), lookAt.y.coerceIn(-1f, 1f)) else gazeTarget,
        spring(dampingRatio = 0.72f, stiffness = 140f),
    )
    val tilt by animateFloatAsState(tiltTarget, spring(dampingRatio = 0.8f, stiffness = 30f))
    val listen by animateFloatAsState(if (mood == FaceMood.LISTENING) 1f else 0f, tween(300))
    val speak by animateFloatAsState(if (mood == FaceMood.SPEAKING) 1f else 0f, tween(180))
    val think by animateFloatAsState(if (mood == FaceMood.THINKING) 1f else 0f, tween(350))
    val asleep by animateFloatAsState(if (sleeping) 1f else 0f, tween(900))
    val smile by animateFloatAsState(
        when (mood) {
            FaceMood.IDLE -> 1f
            FaceMood.SPEAKING -> 0.7f
            FaceMood.LISTENING -> 0.35f
            FaceMood.THINKING -> 0.1f
            FaceMood.SLEEPING -> 0.4f
        },
        tween(400),
    )
    val mic by animateFloatAsState(level.coerceIn(0f, 1f), tween(90))
    val surface = JarvisColors.Surface
    val bright = JarvisColors.CyanBright

    Canvas(modifier.size(size)) {
        val t = clock
        val w = this.size.minDimension
        val c = center
        val r = w * 0.34f
        val breathPeriod = if (sleeping) 5.5f else 3.8f
        val breath = sin(t * 2f * PI.toFloat() / breathPeriod)
        val scale = 1f + 0.018f * breath
        val bob = w * 0.006f * breath

        // Halo (wider and brighter with the voice while listening).
        drawCircle(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.28f + 0.25f * listen * mic + 0.1f * speak), Color.Transparent),
                center = c, radius = r * (1.65f + 0.25f * listen * mic),
            ),
            radius = r * (1.65f + 0.25f * listen * mic), center = c,
        )
        if (listen > 0.01f) {
            for (k in 0..1) {
                val ripple = ((t * 0.9f + k * 0.5f) % 1f)
                drawCircle(accent.copy(alpha = listen * (1f - ripple) * (0.35f + 0.5f * mic)), r * (1.08f + 0.35f * ripple), c, style = Stroke(w * 0.008f))
            }
        }

        rotate(tilt, pivot = c) {
            val hc = Offset(c.x, c.y + bob)
            val hr = r * scale
            // Head: lit from the upper left.
            drawCircle(
                Brush.radialGradient(listOf(lerp(surface, accent, 0.55f), lerp(surface, Color.Black, 0.25f)), center = Offset(hc.x - hr * 0.35f, hc.y - hr * 0.4f), radius = hr * 1.6f),
                radius = hr, center = hc,
            )
            drawCircle(accent.copy(alpha = 0.85f), hr, hc, style = Stroke(w * 0.011f))
            // Cheeks when happy.
            if (smile > 0.5f && asleep < 0.5f) {
                for (side in listOf(-1f, 1f)) {
                    drawCircle(Color(0xFFFF8FA3).copy(alpha = 0.16f * (smile - 0.5f) * 2f), hr * 0.12f, Offset(hc.x + side * hr * 0.55f, hc.y + hr * 0.2f))
                }
            }

            // Eyes.
            val eyeW = hr * 0.27f
            val eyeHFull = hr * 0.36f * (1f + 0.14f * listen)
            for (side in listOf(-1f, 1f)) {
                val ex = hc.x + side * hr * 0.37f + gaze.x * hr * 0.09f
                val ey = hc.y - hr * 0.13f + gaze.y * hr * 0.07f
                val closed = maxOf(blink, asleep)
                if (closed > 0.92f) {
                    // Closed: a soft downward curve.
                    val p = Path().apply {
                        moveTo(ex - eyeW * 0.5f, ey)
                        quadraticTo(ex, ey + eyeHFull * 0.22f, ex + eyeW * 0.5f, ey)
                    }
                    drawPath(p, bright.copy(alpha = 0.9f), style = Stroke(w * 0.014f, cap = StrokeCap.Round))
                } else {
                    val eh = eyeHFull * (1f - closed * 0.95f)
                    val rect = Rect(Offset(ex - eyeW / 2, ey - eh / 2), Size(eyeW, eh))
                    val eyePath = Path().apply { addOval(rect) }
                    drawOval(Color(0xFFF4FBFF), rect.topLeft, rect.size)
                    clipPath(eyePath) {
                        val pr = eyeW * 0.36f * (1f + 0.22f * listen - 0.08f * think)
                        val pc = Offset(ex + gaze.x * eyeW * 0.3f, ey + gaze.y * eyeHFull * 0.26f)
                        drawCircle(lerp(Color(0xFF0B1A2A), accent, 0.35f), pr, pc)
                        drawCircle(Color(0xFF060B12), pr * 0.55f, pc)
                        drawCircle(Color.White.copy(alpha = 0.9f), pr * 0.28f, Offset(pc.x - pr * 0.35f, pc.y - pr * 0.38f))
                    }
                    drawOval(accent.copy(alpha = 0.5f), rect.topLeft, rect.size, style = Stroke(w * 0.005f))
                }
                // Brows: up while listening, one raised while thinking, relaxed asleep.
                val raise = hr * (0.05f * listen + (if (side > 0) 0.07f else 0.01f) * think - 0.02f * asleep)
                val by = ey - eyeHFull / 2 - hr * 0.09f - raise
                val inner = by + hr * 0.02f * (1f - listen) * (1f - think)
                drawLine(
                    bright.copy(alpha = 0.75f * (1f - 0.5f * asleep)),
                    Offset(ex - side * eyeW * 0.55f, inner), Offset(ex + side * eyeW * 0.55f, by - hr * 0.015f),
                    strokeWidth = w * 0.013f, cap = StrokeCap.Round,
                )
            }

            // Mouth.
            val mx = hc.x + gaze.x * hr * 0.04f
            val my = hc.y + hr * 0.4f
            val mw = hr * (0.42f - 0.12f * listen - 0.1f * asleep)
            val talk = speak * mouthWave(t)
            val open = maxOf(talk * hr * 0.24f, listen * hr * 0.05f)
            val curve = hr * (0.1f * smile - 0.03f * think)
            if (open > hr * 0.015f) {
                val p = Path().apply {
                    moveTo(mx - mw / 2, my)
                    quadraticTo(mx, my + curve * 0.4f - open * 0.15f, mx + mw / 2, my)
                    quadraticTo(mx, my + curve + open * 1.6f, mx - mw / 2, my)
                    close()
                }
                drawPath(p, Color(0xFF16060C))
                drawCircle(Color(0xFFE0566F).copy(alpha = 0.8f), mw * 0.18f, Offset(mx, my + curve * 0.5f + open * 0.9f))
                drawPath(p, bright.copy(alpha = 0.85f), style = Stroke(w * 0.01f))
            } else {
                val p = Path().apply {
                    moveTo(mx - mw / 2, my)
                    quadraticTo(mx, my + curve * 2f, mx + mw / 2, my)
                }
                drawPath(p, bright.copy(alpha = 0.9f), style = Stroke(w * 0.013f, cap = StrokeCap.Round))
            }

            // Thinking: three dots circling above.
            if (think > 0.02f) {
                for (k in 0..2) {
                    val a = t * 4f + k * 2.1f
                    val p = Offset(hc.x + hr * 0.95f + sin(a) * hr * 0.12f, hc.y - hr * 0.95f + sin(a + 1.3f) * hr * 0.08f - k * hr * 0.12f)
                    drawCircle(accent.copy(alpha = think * (0.5f + 0.5f * abs(sin(a)))), hr * (0.05f + 0.015f * k), p)
                }
            }
        }

        // Asleep: "z"s drifting up and fading.
        if (asleep > 0.02f) {
            for (k in 0..2) {
                val ph = ((t / 2.4f) + k / 3f) % 1f
                val zc = Offset(c.x + r * (0.85f + 0.35f * ph), c.y - r * (0.75f + 0.9f * ph))
                drawZ(zc, r * (0.1f + 0.08f * ph), accent.copy(alpha = asleep * (1f - ph)), w * 0.01f)
            }
        }
    }
}

/** A speech-like opening of the mouth: syllables of different lengths with short pauses. */
internal fun mouthWave(t: Float): Float {
    val tau = 2f * PI.toFloat()
    val syllable = abs(sin(t * tau * 3.3f)) * 0.6f + abs(sin(t * tau * 5.1f + 1.1f)) * 0.4f
    val phrase = 0.55f + 0.45f * sin(t * tau * 0.45f)
    val pause = if (sin(t * tau * 0.8f + 0.4f) > 0.93f) 0.15f else 1f
    return (syllable * phrase * pause).coerceIn(0f, 1f)
}

private fun DrawScope.drawZ(c: Offset, s: Float, color: Color, width: Float) {
    val p = Path().apply {
        moveTo(c.x - s, c.y - s)
        lineTo(c.x + s, c.y - s)
        lineTo(c.x - s, c.y + s)
        lineTo(c.x + s, c.y + s)
    }
    drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round))
}

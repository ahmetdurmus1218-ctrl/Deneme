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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
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
 * Rick's living face: a realistic human head and shoulders drawn as a hologram (proportions, lit
 * planes, almond eyes with irises and lids, a nose, lips), not a cartoon. It breathes and sways,
 * blinks at its own pace and looks around while waiting, looks straight at the user (eyes wider,
 * brows up) while listening, glances up while thinking (a thin arc scans around the head), and the
 * lips part and the jaw drops while Rick speaks. Asleep, the lids close and the head bows.
 * [level] is the microphone level while listening (0…1). Below 140 dp (the floating bubble) only
 * the head is shown, larger. One Canvas, one clock; the random glances and blinks come from two
 * small loops.
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
    val night = JarvisColors.Background

    Canvas(modifier.size(size)) {
        val t = clock
        val w = this.size.minDimension
        // Small (the floating bubble): the head fills the circle; large: head and shoulders.
        val close = size < 140.dp
        val u = w * if (close) 0.27f else 0.2f
        val cx = this.size.width / 2f
        val breathPeriod = if (sleeping) 5.5f else 3.8f
        val breath = sin(t * 2f * PI.toFloat() / breathPeriod)
        val cy = this.size.height * (if (close) 0.5f else 0.44f) + w * 0.004f * breath + asleep * u * 0.08f
        // A slight turn of the head toward where the eyes look (features shift, the outline stays).
        val turn = gaze.x * 0.05f
        fun p(x: Float, y: Float) = Offset(cx + x * u, cy + y * u)
        fun f(x: Float, y: Float) = Offset(cx + (x + turn) * u, cy + (y + gaze.y * 0.025f) * u)

        val skin = lerp(surface, accent, 0.42f)
        val faceBrush = Brush.radialGradient(
            0f to lerp(skin, Color.White, 0.12f), 0.55f to skin, 1f to lerp(surface, night, 0.2f),
            center = p(-0.35f, -0.45f), radius = u * 2.3f,
        )

        // Glow (wider and brighter with the voice while listening).
        val glow = u * (2.4f + 0.3f * listen * mic)
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = 0.18f + 0.22f * listen * mic + 0.08f * speak - 0.08f * asleep), Color.Transparent), center = Offset(cx, cy), radius = glow),
            radius = glow, center = Offset(cx, cy),
        )
        if (listen > 0.01f) {
            for (k in 0..1) {
                val ripple = ((t * 0.9f + k * 0.5f) % 1f)
                drawCircle(accent.copy(alpha = listen * (1f - ripple) * (0.3f + 0.45f * mic)), u * (1.9f + 0.6f * ripple), Offset(cx, cy - u * 0.1f), style = Stroke(w * 0.005f))
            }
        }

        // Shoulders and neck (they do not tilt with the head).
        if (!close) {
            val body = Path().apply {
                moveTo(p(-0.5f, 1.0f)); lineTo(p(-0.55f, 1.72f))
                cubicTo(p(-0.95f, 1.95f), p(-1.9f, 2.05f), p(-2.25f, 2.6f)); lineTo(p(2.25f, 2.6f))
                cubicTo(p(1.9f, 2.05f), p(0.95f, 1.95f), p(0.55f, 1.72f)); lineTo(p(0.5f, 1.0f)); close()
            }
            drawPath(body, Brush.verticalGradient(listOf(lerp(surface, accent, 0.3f), lerp(surface, night, 0.6f)), startY = p(0f, 1f).y, endY = p(0f, 2.6f).y))
            softOval(p(-0.5f, 1.05f), p(0.5f, 1.5f), Color.Black, 0.45f)
            drawPath(body, accent.copy(alpha = 0.5f), style = Stroke(w * 0.004f))
        }

        rotate(tilt, pivot = p(0f, 1.2f)) {
            // Ears.
            for (side in SIDES) {
                val tl = p(side * 0.98f - 0.13f, -0.12f)
                val br = p(side * 0.98f + 0.13f, 0.48f)
                drawOval(lerp(surface, accent, 0.32f), tl, Size(br.x - tl.x, br.y - tl.y))
                drawOval(accent.copy(alpha = 0.45f), tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(w * 0.003f))
            }
            val face = Path().apply {
                moveTo(p(0f, 1.42f))
                cubicTo(p(0.32f, 1.42f), p(0.62f, 1.25f), p(0.8f, 0.92f))
                cubicTo(p(0.93f, 0.62f), p(0.97f, 0.3f), p(0.96f, 0f))
                cubicTo(p(0.98f, -0.55f), p(0.92f, -1.2f), p(0f, -1.55f))
                cubicTo(p(-0.92f, -1.2f), p(-0.98f, -0.55f), p(-0.96f, 0f))
                cubicTo(p(-0.97f, 0.3f), p(-0.93f, 0.62f), p(-0.8f, 0.92f))
                cubicTo(p(-0.62f, 1.25f), p(-0.32f, 1.42f), p(0f, 1.42f))
                close()
            }
            drawPath(face, faceBrush)
            val closed = maxOf(blink, asleep)
            clipPath(face) {
                // Eyeballs, then the lids closing over them (in the skin's own shading).
                for (side in SIDES) {
                    val e = Eye(side, listen)
                    val eye = e.outline(::f)
                    clipPath(eye) {
                        drawRect(Color(0xFFC9DCE8).copy(alpha = 0.85f), p(-2f, -1f), Size(4f * u, 2f * u))
                        val ic = f(e.x + gaze.x * 0.07f, -0.02f + gaze.y * 0.04f)
                        val ir = u * 0.095f
                        drawCircle(
                            Brush.radialGradient(
                                0.2f to lerp(IRIS_DARK, accent, 0.2f), 0.7f to lerp(IRIS_DARK, accent, 0.75f), 1f to lerp(IRIS_DARK, accent, 0.3f),
                                center = ic, radius = ir,
                            ),
                            ir, ic,
                        )
                        for (k in 0 until 18) {
                            val a = k / 18f * 2f * PI.toFloat()
                            drawLine(bright.copy(alpha = 0.25f), ic + Offset(cos(a), sin(a)) * (ir * 0.45f), ic + Offset(cos(a), sin(a)) * (ir * 0.9f), strokeWidth = u * 0.006f)
                        }
                        drawCircle(Color(0xFF02060C).copy(alpha = 0.9f), ir, ic, style = Stroke(u * 0.014f))
                        drawCircle(Color(0xFF02050A), ir * (0.42f + 0.12f * listen - 0.06f * think), ic)
                        // The upper lid's shadow on the eyeball.
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent), startY = f(0f, e.top).y, endY = f(0f, e.top * 0.3f).y),
                            p(-2f, -1f), Size(4f * u, 2f * u),
                        )
                        if (closed > 0.01f) {
                            val ly = f(0f, e.top + (e.bottom - e.top + 0.02f) * closed).y
                            drawRect(faceBrush, Offset(cx - 2f * u, cy - u), Size(4f * u, ly - (cy - u)))
                            if (closed < 0.9f) drawLine(LASH.copy(alpha = 0.9f), Offset(cx - 2f * u, ly), Offset(cx + 2f * u, ly), strokeWidth = u * 0.02f)
                        }
                    }
                }

                // Light and shadow on the face's planes (over the eyes too: they sit in sockets).
                for (side in SIDES) {
                    softOval(f(side * 0.4f - 0.33f, -0.22f), f(side * 0.4f + 0.33f, 0.2f), Color.Black, 0.3f) // eye socket
                    softOval(p(side * 0.78f - 0.25f, 0.35f), p(side * 0.78f + 0.25f, 1.1f), Color.Black, 0.26f) // cheek hollow
                    softOval(f(side * 0.55f - 0.2f, 0.15f), f(side * 0.55f + 0.2f, 0.4f), bright, 0.14f) // cheekbone
                }
                softOval(p(0.6f, -1.2f), p(1.1f, 1.2f), Color.Black, 0.35f) // the side away from the light
                softOval(f(-0.45f, -1.1f), f(0.35f, -0.55f), bright, 0.16f) // forehead
                softOval(f(-0.2f, 1.12f), f(0.2f, 1.32f), bright, 0.14f) // chin

                // Lids, creases, brows.
                for (side in SIDES) {
                    val e = Eye(side, listen)
                    if (closed < 0.9f) {
                        drawPath(e.upper(::f), LASH.copy(alpha = 0.95f), style = Stroke(u * 0.028f, cap = StrokeCap.Round))
                        val ic = f(e.x + gaze.x * 0.07f, -0.02f + gaze.y * 0.04f)
                        if (closed < 0.4f) drawCircle(Color.White.copy(alpha = 0.85f), u * 0.017f, ic + Offset(-0.03f * u, -0.035f * u))
                    } else {
                        val shut = Path().apply {
                            moveTo(f(e.inner, 0.015f)); cubicTo(f(e.inner + side * 0.1f, e.bottom * 0.9f), f(e.outer - side * 0.1f, e.bottom * 0.9f), f(e.outer, -0.02f))
                        }
                        drawPath(shut, LASH.copy(alpha = 0.9f), style = Stroke(u * 0.026f, cap = StrokeCap.Round))
                    }
                    val ct = e.top - 0.07f - 0.04f * listen
                    val crease = Path().apply {
                        moveTo(f(e.inner + side * 0.02f, -0.04f)); cubicTo(f(e.inner + side * 0.1f, ct), f(e.outer - side * 0.1f, ct), f(e.outer + side * 0.02f, -0.05f))
                    }
                    drawPath(crease, Color.Black.copy(alpha = 0.3f), style = Stroke(u * 0.018f, cap = StrokeCap.Round))
                    val lower = Path().apply {
                        moveTo(f(e.inner + side * 0.03f, 0.03f)); cubicTo(f(e.inner + side * 0.1f, e.bottom + 0.02f), f(e.outer - side * 0.1f, e.bottom + 0.02f), f(e.outer, 0f))
                    }
                    drawPath(lower, bright.copy(alpha = 0.18f), style = Stroke(u * 0.012f))
                    // Brows: up while listening, one raised while thinking, relaxed asleep.
                    val by = -0.3f - (0.06f * listen + (if (side > 0) 0.08f else 0.02f) * think - 0.02f * asleep)
                    val brow = Path().apply {
                        moveTo(f(side * 0.12f, by + 0.05f))
                        cubicTo(f(side * 0.3f, by - 0.06f), f(side * 0.5f, by - 0.1f), f(side * 0.7f, by + 0.02f))
                        cubicTo(f(side * 0.5f, by - 0.04f), f(side * 0.3f, by), f(side * 0.12f, by + 0.1f))
                        close()
                    }
                    drawPath(brow, Color(0xFF040A12).copy(alpha = 0.85f))
                }

                // Nose: a lit bridge, a shadowed side, nostrils.
                softOval(f(-0.07f, -0.1f), f(0.04f, 0.45f), bright, 0.16f)
                val noseSide = Path().apply { moveTo(f(0.1f, -0.05f)); cubicTo(f(0.14f, 0.2f), f(0.17f, 0.38f), f(0.19f, 0.5f)) }
                drawPath(noseSide, Color.Black.copy(alpha = 0.22f), style = Stroke(u * 0.05f, cap = StrokeCap.Round))
                softOval(f(-0.2f, 0.56f), f(0.2f, 0.68f), Color.Black, 0.35f)
                for (side in SIDES) {
                    val wing = Path().apply { moveTo(f(side * 0.1f, 0.6f)); quadraticTo(f(side * 0.2f, 0.6f), f(side * 0.17f, 0.5f)) }
                    drawPath(wing, Color.Black.copy(alpha = 0.5f), style = Stroke(u * 0.022f, cap = StrokeCap.Round))
                    val n = f(side * 0.1f, 0.585f)
                    drawOval(Color.Black.copy(alpha = 0.5f), Offset(n.x - 0.04f * u, n.y - 0.02f * u), Size(0.08f * u, 0.04f * u))
                }
                val tip = Path().apply { moveTo(f(-0.08f, 0.55f)); quadraticTo(f(0f, 0.6f), f(0.08f, 0.55f)) }
                drawPath(tip, bright.copy(alpha = 0.22f), style = Stroke(u * 0.02f, cap = StrokeCap.Round))

                // Mouth: the lips part and the jaw drops while Rick speaks.
                val my = 0.86f
                val mw = 0.3f - 0.03f * listen
                val corner = -0.03f * smile + 0.01f * think
                val drop = speak * mouthWave(t) * 0.16f
                val lip = lerp(lerp(surface, accent, 0.3f), Color(0xFFE0607A), 0.18f)
                if (drop > 0.01f) {
                    val inside = Path().apply {
                        moveTo(f(-mw * 0.92f, my + corner)); cubicTo(f(-0.15f, my), f(0.15f, my), f(mw * 0.92f, my + corner))
                        cubicTo(f(0.15f, my + drop * 1.1f), f(-0.15f, my + drop * 1.1f), f(-mw * 0.92f, my + corner)); close()
                    }
                    drawPath(inside, Color(0xFF05020A))
                    clipPath(inside) {
                        val tl = f(-0.2f, my - 0.02f)
                        drawRect(Color(0xFFE8F2F8).copy(alpha = 0.7f), tl, Size(0.4f * u, 0.055f * u))
                    }
                }
                val upper = Path().apply {
                    moveTo(f(-mw, my + corner))
                    cubicTo(f(-0.16f, my - 0.07f), f(-0.06f, my - 0.1f), f(0f, my - 0.07f))
                    cubicTo(f(0.06f, my - 0.1f), f(0.16f, my - 0.07f), f(mw, my + corner))
                    cubicTo(f(0.15f, my), f(-0.15f, my), f(-mw, my + corner)); close()
                }
                drawPath(upper, lip)
                drawPath(upper, Color.Black.copy(alpha = 0.3f), style = Stroke(u * 0.01f))
                val ly = my + drop
                val lowerLip = Path().apply {
                    moveTo(f(-mw * 0.92f, my + corner)); cubicTo(f(-0.15f, ly + 0.005f), f(0.15f, ly + 0.005f), f(mw * 0.92f, my + corner))
                    cubicTo(f(0.18f, ly + 0.13f), f(-0.18f, ly + 0.13f), f(-mw * 0.92f, my + corner)); close()
                }
                drawPath(lowerLip, Brush.radialGradient(listOf(lerp(lip, Color.White, 0.15f), lip), center = f(-0.05f, ly + 0.05f), radius = u * 0.25f))
                softOval(f(-0.16f, ly + 0.14f), f(0.16f, ly + 0.22f), Color.Black, 0.3f)
                for (side in SIDES) drawCircle(Color.Black.copy(alpha = 0.4f), u * 0.018f, f(side * mw, my + corner))

                // A hologram's fine scan lines.
                var y = cy - 1.6f * u
                while (y < cy + 1.5f * u) {
                    drawLine(bright.copy(alpha = 0.05f), Offset(cx - u, y), Offset(cx + u, y), strokeWidth = 1f)
                    y += u * 0.035f
                }
            }
            drawPath(face, accent.copy(alpha = 0.55f), style = Stroke(w * 0.005f))
            // Short hair.
            val hair = Path().apply {
                moveTo(p(-0.99f, -0.2f))
                cubicTo(p(-1.1f, -0.95f), p(-0.85f, -1.68f), p(0f, -1.74f))
                cubicTo(p(0.85f, -1.68f), p(1.1f, -0.95f), p(0.99f, -0.2f))
                cubicTo(p(0.95f, -0.45f), p(0.9f, -0.7f), p(0.78f, -0.88f))
                cubicTo(p(0.6f, -1f), p(0.3f, -1.06f), p(0.05f, -1.05f))
                cubicTo(p(-0.3f, -1.04f), p(-0.62f, -0.98f), p(-0.8f, -0.86f))
                cubicTo(p(-0.9f, -0.7f), p(-0.95f, -0.45f), p(-0.99f, -0.2f))
                close()
            }
            drawPath(hair, Brush.radialGradient(listOf(lerp(surface, accent, 0.22f), lerp(surface, night, 0.55f)), center = p(-0.3f, -1.45f), radius = u * 1.7f))
            drawPath(hair, Color.Black.copy(alpha = 0.25f), style = Stroke(u * 0.03f))
        }

        // Thinking: a thin arc scanning around the head.
        if (think > 0.02f) {
            val r = u * 1.95f
            val c = Offset(cx, cy - u * 0.1f)
            drawArc(accent.copy(alpha = 0.7f * think), (t * 220f) % 360f, 70f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(w * 0.006f, cap = StrokeCap.Round))
            drawArc(accent.copy(alpha = 0.35f * think), (t * 220f + 180f) % 360f, 40f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(w * 0.004f, cap = StrokeCap.Round))
        }
    }
}

private val SIDES = floatArrayOf(-1f, 1f)
private val IRIS_DARK = Color(0xFF0A1420)
private val LASH = Color(0xFF02060C)

/** One eye's almond shape in face units ([side] -1: the left of the picture). */
private class Eye(val side: Float, listen: Float) {
    val x = side * 0.4f
    val inner = x - side * 0.2f
    val outer = x + side * 0.22f
    /** Wider open while listening. */
    val top = -0.13f * (1f + 0.25f * listen)
    val bottom = 0.085f

    fun upper(at: (Float, Float) -> Offset) = Path().apply {
        moveTo(at(inner, 0.015f)); cubicTo(at(inner + side * 0.08f, top), at(outer - side * 0.12f, top * 1.05f), at(outer, -0.02f))
    }

    fun outline(at: (Float, Float) -> Offset) = upper(at).apply {
        cubicTo(at(outer - side * 0.1f, bottom), at(inner + side * 0.08f, bottom), at(inner, 0.015f)); close()
    }
}

private fun Path.moveTo(o: Offset) = moveTo(o.x, o.y)
private fun Path.lineTo(o: Offset) = lineTo(o.x, o.y)
private fun Path.cubicTo(a: Offset, b: Offset, c: Offset) = cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
private fun Path.quadraticTo(a: Offset, b: Offset) = quadraticTo(a.x, a.y, b.x, b.y)

/** A soft-edged oval of light or shadow between the corners [a] and [b]. */
private fun DrawScope.softOval(a: Offset, b: Offset, color: Color, alpha: Float) {
    val l = minOf(a.x, b.x); val r = maxOf(a.x, b.x); val tp = minOf(a.y, b.y); val bt = maxOf(a.y, b.y)
    val h = (bt - tp).coerceAtLeast(0.5f)
    val c = Offset((l + r) / 2f, (tp + bt) / 2f)
    scale((r - l) / h, 1f, pivot = c) {
        drawCircle(Brush.radialGradient(0f to color.copy(alpha = alpha), 0.55f to color.copy(alpha = alpha * 0.6f), 1f to Color.Transparent, center = c, radius = h / 2f), h / 2f, c)
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

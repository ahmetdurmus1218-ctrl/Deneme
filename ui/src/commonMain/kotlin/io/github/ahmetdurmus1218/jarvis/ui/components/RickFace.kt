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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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
 * Rick as a living spark: a warm, hand-made looking starburst (no human face) that reacts like a
 * person. It breathes and sways while waiting, glances (leans) around and now and then "blinks"
 * with a quick squash; while listening it leans in, grows and its rays reach out with the voice;
 * while thinking it turns slowly and its rays ripple; while speaking the rays pulse with the
 * syllables and it nods; asleep it shrinks, dims and droops. [lookAt] makes it lean toward a point
 * (e.g. the user's hand). [level] is the microphone level while listening (0…1). One Canvas, one
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
    /** spark, eyes, orb, ring or particles (see [LOOKS]); by default the one chosen in Settings. */
    look: String = JarvisUi.look,
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
    val mic by animateFloatAsState(level.coerceIn(0f, 1f), tween(90))
    val spark = lerp(SPARK, accent, 0.12f)

    Canvas(modifier.size(size)) {
        val t = clock
        val w = this.size.minDimension
        val c = center
        val r = w * 0.36f
        val tau = 2f * PI.toFloat()
        val breath = sin(t * tau / (if (sleeping) 5.5f else 3.6f))
        val st = LookState(t, listen, speak, think, asleep, blink, gaze, mic, accent)
        when (look) {
            "orb" -> { drawOrb(st); return@Canvas }
            "ring" -> { drawRing(st); return@Canvas }
            "particles" -> { drawParticles(st); return@Canvas }
        }

        // Glow behind, wider with the voice while listening.
        val glow = r * (1.55f + 0.3f * listen * mic)
        drawCircle(
            Brush.radialGradient(listOf(spark.copy(alpha = 0.22f + 0.2f * listen * mic + 0.08f * speak - 0.12f * asleep), Color.Transparent), center = c, radius = glow),
            radius = glow, center = c,
        )
        if (listen > 0.01f) {
            for (k in 0..1) {
                val ripple = ((t * 0.9f + k * 0.5f) % 1f)
                drawCircle(spark.copy(alpha = listen * (1f - ripple) * (0.25f + 0.45f * mic)), r * (1.05f + 0.45f * ripple), c, style = Stroke(w * 0.006f))
            }
        }

        // Body language: leans toward where it looks, nods while talking, sinks and droops asleep.
        val nod = speak * sin(t * tau * 1.6f) * r * 0.035f
        val lean = Offset(gaze.x * r * 0.12f, gaze.y * r * 0.1f + nod + asleep * r * 0.08f - listen * r * 0.03f)
        // A "blink": a quick squash, like someone settling; asleep: flattened and smaller.
        val squash = 1f - 0.16f * blink
        val grow = (1f + 0.1f * listen + 0.015f * breath) * (1f - 0.22f * asleep)
        val spin = think * ((t * 70f) % 360f)
        // Dimmed toward the background (not see-through: overlapping rays would show seams).
        val body = lerp(spark, JarvisColors.Background, 0.45f * asleep)
        withTransform({
            translate(lean.x, lean.y)
            rotate(tilt * 1.6f + spin, pivot = c)
            scale(grow * (1f + 0.04f * blink), grow * squash * (1f - 0.08f * asleep), pivot = c)
        }) {
            val n = RAYS.size
            for (i in 0 until n) {
                val (len, width) = RAYS[i]
                val a = i / n.toFloat() * tau - tau / 4f
                // Each ray moves on its own: with the syllables, in a wave while thinking, reaching out to listen.
                val reach = 0.12f * speak * mouthWave(t + i * 0.137f) +
                    0.08f * think * sin(t * 5f - i * 0.9f) +
                    0.1f * listen * (0.6f + 0.4f * mic) +
                    0.02f * breath * (if (i % 2 == 0) 1f else -1f)
                val dir = Offset(cos(a), sin(a))
                drawLine(
                    body,
                    c + dir * (r * 0.18f), c + dir * (r * len * (0.92f + reach)),
                    strokeWidth = r * 0.17f * width, cap = StrokeCap.Round,
                )
            }
            if (look == "eyes") {
                // A face in the middle: two dark eyes that look, blink, widen and close.
                drawCircle(body, r * 0.42f, c)
                for (side in floatArrayOf(-1f, 1f)) {
                    val e = c + Offset(side * r * 0.15f + gaze.x * r * 0.05f, -r * 0.03f + gaze.y * r * 0.05f)
                    val closed = maxOf(blink, asleep)
                    if (closed > 0.85f) {
                        drawLine(EYE_DARK, e - Offset(r * 0.07f, 0f), e + Offset(r * 0.07f, 0f), strokeWidth = r * 0.04f, cap = StrokeCap.Round)
                    } else {
                        val eh = r * (0.13f + 0.04f * listen) * (1f - closed)
                        val ew = r * 0.09f
                        drawRoundRect(EYE_DARK, Offset(e.x - ew / 2f, e.y - eh / 2f), androidx.compose.ui.geometry.Size(ew, eh), androidx.compose.ui.geometry.CornerRadius(ew / 2f))
                    }
                }
            } else {
                drawCircle(body, r * 0.24f, c)
            }
        }
    }
}

/** The spark's rays: length and thickness of each, a little uneven like something hand-made. */
private val RAYS = listOf(
    1.00f to 1.0f, 0.78f to 0.9f, 0.92f to 1.05f, 0.70f to 0.85f, 0.95f to 1.0f, 0.80f to 0.95f,
    0.98f to 1.05f, 0.72f to 0.9f, 0.90f to 1.0f, 0.76f to 0.88f, 0.94f to 1.02f, 0.74f to 0.9f,
)

/** The spark's warm colour (a little of the theme's accent is mixed in). */
private val SPARK = Color(0xFFD97757)
private val EYE_DARK = Color(0xFF1A0E0A)

/** The looks to choose from (Settings → Görünüm): id to name. */
val LOOKS = listOf(
    "spark" to "Kıvılcım",
    "eyes" to "Gözlü kıvılcım",
    "orb" to "Cam küre",
    "ring" to "Ses halkası",
    "particles" to "Parçacık küre",
)

/** Everything a look needs from the clock and the mood. */
private class LookState(
    val t: Float, val listen: Float, val speak: Float, val think: Float, val asleep: Float,
    val blink: Float, val gaze: Offset, val mic: Float, val accent: Color,
)

/** A dark glass sphere with two glowing eyes (they look, blink, widen, close) and a voice bar when speaking. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawOrb(s: LookState) {
    val w = size.minDimension
    val c = center
    val r = w * 0.33f
    val col = lerp(s.accent, Color(0xFF203040), 0.6f * s.asleep)
    val g = r * (1.7f + 0.3f * s.listen * s.mic)
    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.3f - 0.2f * s.asleep), Color.Transparent), c, g), g, c)
    val bob = Offset(0f, sin(s.t * 1.7f) * r * 0.02f + s.asleep * r * 0.05f)
    val o = c + bob
    drawCircle(Brush.radialGradient(0f to Color(0xFF1E2A3E), 0.6f to Color(0xFF0A1220), 1f to Color(0xFF04070D), center = o - Offset(r * 0.35f, r * 0.4f), radius = r * 1.6f), r, o)
    drawCircle(col.copy(alpha = 0.6f), r, o, style = Stroke(w * 0.006f))
    drawOval(Color.White.copy(alpha = 0.07f), o - Offset(r * 0.55f, r * 0.85f), androidx.compose.ui.geometry.Size(r * 0.7f, r * 0.3f))
    val closed = maxOf(s.blink, s.asleep)
    for (side in floatArrayOf(-1f, 1f)) {
        val e = o + Offset(side * r * 0.32f + s.gaze.x * r * 0.12f, -r * 0.05f + s.gaze.y * r * 0.1f)
        if (closed > 0.85f) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(e.x - r * 0.13f, e.y); quadraticTo(e.x, e.y + r * 0.08f, e.x + r * 0.13f, e.y)
            }
            drawPath(p, col.copy(alpha = 0.8f), style = Stroke(r * 0.05f, cap = StrokeCap.Round))
        } else {
            val eh = r * (0.42f + 0.1f * s.listen - 0.08f * s.think) * (1f - closed)
            val ew = r * 0.2f
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.45f), Color.Transparent), e, r * 0.35f), r * 0.35f, e)
            drawRoundRect(col, Offset(e.x - ew / 2f, e.y - eh / 2f), androidx.compose.ui.geometry.Size(ew, eh), androidx.compose.ui.geometry.CornerRadius(ew / 2f))
        }
    }
    if (s.speak > 0.02f) {
        for (i in 0 until 9) {
            val x = o.x + (i - 4) * r * 0.07f
            val hh = r * (0.03f + 0.12f * mouthWave(s.t + i * 0.11f)) * s.speak
            drawLine(col.copy(alpha = 0.9f), Offset(x, o.y + r * 0.45f - hh), Offset(x, o.y + r * 0.45f + hh), strokeWidth = r * 0.035f, cap = StrokeCap.Round)
        }
    }
    if (s.think > 0.02f) {
        val a = (s.t * 200f) % 360f
        drawArc(col.copy(alpha = 0.7f * s.think), a, 60f, false, o - Offset(r * 1.15f, r * 1.15f), androidx.compose.ui.geometry.Size(r * 2.3f, r * 2.3f), style = Stroke(w * 0.006f, cap = StrokeCap.Round))
    }
}

/** Three glowing rings that wobble with the voice: calm when waiting, lively when listening or speaking. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRing(s: LookState) {
    val w = size.minDimension
    val c = center + Offset(s.gaze.x * w * 0.03f, s.gaze.y * w * 0.03f)
    val r = w * 0.3f * (1f - 0.15f * s.asleep) * (1f + 0.04f * sin(s.t * 1.7f))
    val cols = listOf(Color(0xFF22D3EE), Color(0xFF8B7CF6), Color(0xFFF472B6)).map { lerp(lerp(it, s.accent, 0.25f), Color(0xFF1A2230), 0.7f * s.asleep) }
    val amp = 0.03f + 0.13f * s.listen * (0.5f + s.mic) + 0.1f * s.speak * mouthWave(s.t) + 0.04f * s.think - 0.025f * s.asleep
    drawCircle(Brush.radialGradient(listOf(cols[0].copy(alpha = 0.2f), Color.Transparent), c, r * 1.7f), r * 1.7f, c)
    cols.forEachIndexed { k, col ->
        val path = androidx.compose.ui.graphics.Path()
        val spin = s.t * (0.5f + 0.25f * k) * (if (k % 2 == 0) 1f else -1f) + s.think * s.t * 2f
        for (i in 0..120) {
            val a = i / 120f * 2f * PI.toFloat()
            val rr = r * (1f + amp * sin(a * (3 + k) + spin + k * 1.7f) * sin(a * 2f - spin * 0.7f + k))
            val x = c.x + cos(a) * rr
            val y = c.y + sin(a) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, col.copy(alpha = 0.3f), style = Stroke(w * 0.03f))
        drawPath(path, col.copy(alpha = 0.9f), style = Stroke(w * 0.011f))
    }
}

/** A turning sphere of light points that swells and ripples with the voice. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawParticles(s: LookState) {
    val w = size.minDimension
    val c = center + Offset(s.gaze.x * w * 0.03f, s.gaze.y * w * 0.03f)
    val r = w * 0.31f * (1f - 0.2f * s.asleep) * (1f + 0.03f * sin(s.t * 1.7f))
    val col = lerp(s.accent, Color(0xFF203040), 0.6f * s.asleep)
    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.18f), Color.Transparent), c, r * 1.6f), r * 1.6f, c)
    val n = 320
    val turn = s.t * (0.35f + 1.2f * s.think)
    val ripple = 0.12f * s.listen * (0.5f + s.mic) + 0.09f * s.speak * mouthWave(s.t)
    for (i in 0 until n) {
        val y = 1f - 2f * (i + 0.5f) / n
        val rr = kotlin.math.sqrt(1f - y * y)
        val th = i * 2.39996f + turn
        val x = cos(th) * rr
        val z = sin(th) * rr
        val d = 1f + ripple * sin(th * 3f + y * 5f + s.t * 6f)
        val p = Offset(c.x + x * r * d, c.y + y * r * d * (1f - 0.1f * s.asleep))
        val depth = (z + 1f) / 2f
        drawCircle(col.copy(alpha = 0.2f + 0.8f * depth), w * 0.006f * (0.6f + 0.6f * depth), p)
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

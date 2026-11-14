package io.github.ahmetdurmus1218.jarvis.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Where things are in the eye photo (assets/rick-eye/eye.json, written by the export script). */
@Serializable
data class EyeGeometry(
    val width: Int,
    val height: Int,
    @kotlinx.serialization.SerialName("pupil_x") val pupilX: Float,
    @kotlinx.serialization.SerialName("pupil_y") val pupilY: Float,
    @kotlinx.serialization.SerialName("pupil_r") val pupilR: Float,
    @kotlinx.serialization.SerialName("warp_r") val warpR: Float,
    val lids: List<Float>,
    val vignette: Vignette,
    val credits: List<String> = emptyList(),
) {
    @Serializable
    data class Vignette(val cx: Float, val cy: Float, val rx: Float, val ry: Float)
}

/**
 * Rick's eye: a real photograph (public domain) whose pupil is resized live, its corneal reflection
 * kept in place, and real eyelid frames (from a CC BY-SA photo of a closed eye) laid over it.
 */
class EyeAssets(
    val geometry: EyeGeometry,
    val base: ImageBitmap,
    val reflection: ImageBitmap,
    val lids: List<ImageBitmap>,
) {
    companion object {
        const val DIR = "rick-eye"
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads the eye from the app's bundled files; null if anything is missing (Rick then uses the glass sphere). */
        fun load(image: (String) -> ImageBitmap?, text: (String) -> String?): EyeAssets? = runCatching {
            val geo = json.decodeFromString(EyeGeometry.serializer(), text("$DIR/eye.json") ?: return null)
            val base = image("$DIR/eye_base.jpg") ?: return null
            val reflection = image("$DIR/eye_reflect.webp") ?: return null
            val lids = geo.lids.indices.map { i -> image("$DIR/eye_lid_${i.toString().padStart(2, '0')}.webp") ?: return null }
            EyeAssets(geo, base, reflection, lids)
        }.getOrNull()
    }
}

/** Pupil size (1 = as photographed) for the light around the device: wide in the dark, small in sunlight. */
fun pupilForLight(lux: Float?): Float {
    if (lux == null) return 1.05f
    val t = (ln(1f + max(lux, 0f)) / ln(1f + 10_000f)).coerceIn(0f, 1f)
    return 1.8f - 1.18f * t
}

/** The lid's resting position for a mood (0 open … 1 closed). */
fun lidForMood(mode: CoreMode, sleeping: Boolean): Float = when {
    sleeping -> 1f
    mode == CoreMode.LISTENING -> 0f
    mode == CoreMode.THINKING -> 0.17f
    mode == CoreMode.SPEAKING -> 0.05f
    else -> 0.05f
}

/**
 * How far a blink has the lid down, [t] ms after it began: down fast (~90 ms), a moment closed,
 * up slower (~170 ms), as a real blink. 0 once it is over.
 */
fun blinkAt(t: Long): Float = when {
    t < 0 -> 0f
    t < 90 -> (t / 90f).let { it * it * (3 - 2 * it) }
    t < 120 -> 1f
    t < 290 -> 1f - ((t - 120) / 170f).let { it * it * (3 - 2 * it) }
    else -> 0f
}

@Composable
fun RealEye(
    eye: EyeAssets,
    mode: CoreMode,
    level: Float,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    sleeping: Boolean = false,
) {
    val calm = JarvisUi.reduceMotion
    // Pupil: the room's light, wider while listening (interest), a slow living unrest (hippus).
    val lux = JarvisUi.ambientLux
    val pupilTarget = pupilForLight(lux) * (if (mode == CoreMode.LISTENING) 1.12f else 1f)
    val pupil by animateFloatAsState(pupilTarget.coerceIn(0.6f, 1.85f), tween(if (pupilTarget < 1f) 450 else 1100))
    // Lid: where the mood keeps it, slowly; asleep it closes over two seconds.
    val rest by animateFloatAsState(lidForMood(mode, sleeping), tween(if (sleeping) 2200 else 500))
    // The eye is costly to draw (a photo, its pupil re-sampled in rings, lid layers), so it is redrawn only as
    // often as something visibly moves: every frame during a blink, ~15 times a second while speaking, 8 while
    // the pupil merely lives, and not at all while asleep or with reduced motion between blinks.
    var shown by remember { mutableStateOf(EyeTick(0L, -10_000L)) }
    val speaking = mode == CoreMode.SPEAKING
    val wave = { t: Long -> pupilWave(t, calm, sleeping, speaking) }
    LaunchedEffect(sleeping, calm, speaking) {
        var next = -1L
        var second = -1L
        var blinkStart = shown.blinkStart
        var drawn = -1L
        while (isActive) {
            // An "infinite animation" frame, so UI tests (which stop those) can go idle.
            val t = try { withInfiniteAnimationFrameMillis { it } } catch (e: kotlinx.coroutines.CancellationException) {
                if (!isActive) throw e
                return@LaunchedEffect
            }
            if (!sleeping) {
                if (next < 0) next = t + Random.nextLong(2_400, 6_200)
                if (second in 0..t) { blinkStart = t; second = -1L }
                if (t >= next) {
                    blinkStart = t
                    second = if (Random.nextFloat() < 0.18f) t + 380 else -1L
                    next = t + Random.nextLong(2_400, 6_200)
                }
            }
            val blinking = t - blinkStart < BLINK_MS
            val every = when {
                blinking -> 0L
                sleeping || calm -> Long.MAX_VALUE
                speaking -> 66L
                else -> 125L
            }
            val due = drawn < 0 || (every != Long.MAX_VALUE && t - drawn >= every) || (!blinking && shown.blinking(drawn))
            // Between blinks a redraw that would look the same (the pupil's unrest moved less than a step) is skipped.
            if (due && (blinking || drawn < 0 || shown.blinking(drawn) || wave(t) != wave(shown.now))) {
                shown = EyeTick(t, blinkStart)
            }
            if (due) drawn = t
        }
    }
    // Wider than tall, so it reads as an eye, not a photo in a box; drawn in its own layer so its edges can fade
    // to transparent (whatever is behind Rick shows through, with no visible frame).
    val cache = remember(eye) { PupilCache() }
    Canvas(modifier.size(size * 1.3f, size).eyeLayer()) {
        val tick = shown
        val scale = (pupil * (1f + wave(tick.now))).coerceIn(0.58f, 1.9f)
        val lid = max(rest, if (sleeping) 0f else blinkAt(tick.now - tick.blinkStart)).coerceIn(0f, 1f)
        drawEye(eye, scale, lid, cache)
    }
}

/** The pupil's own movement at [t] ms: slow unrest (hippus), a flicker with the voice; in steps of 1 %. */
private fun pupilWave(t: Long, calm: Boolean, sleeping: Boolean, speaking: Boolean): Float {
    if (calm || sleeping) return 0f
    val clock = t / 1000f
    val hippus = 0.025f * sin(clock * 0.9f) + 0.015f * sin(clock * 2.3f + 1f)
    val flicker = if (speaking) 0.04f * mouthWave(clock) else 0f
    return ((hippus + flicker) * 100f).roundToInt() / 100f
}

/**
 * The eye with its pupil at a given size (photo, re-sampled iris, reflection), kept as a picture: the costly
 * ring-by-ring pupil is drawn once per size, not on every frame. A few recent sizes are kept.
 */
class PupilCache {
    private val kept = ArrayList<Triple<Int, IntSize, ImageBitmap>>()

    fun get(key: Int, size: IntSize, render: () -> ImageBitmap): ImageBitmap {
        val i = kept.indexOfFirst { it.first == key && it.second == size }
        if (i >= 0) return kept.removeAt(i).also { kept.add(it) }.third
        val made = render()
        kept.add(Triple(key, size, made))
        if (kept.size > 8) kept.removeAt(0)
        return made
    }
}

/** What the eye was last drawn for: the frame time and when the current blink began. */
private class EyeTick(val now: Long, val blinkStart: Long) {
    fun blinking(at: Long) = at - blinkStart < BLINK_MS
}

private const val BLINK_MS = 300L

/** The layer the eye must be drawn in: its edges are faded by erasing, which needs a layer of its own. */
fun Modifier.eyeLayer(): Modifier = graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)

/**
 * The eye at pupil [scale] with the lid at [lid] (0 open … 1 closed), fitted to the width of the draw area.
 * Draw it in a [eyeLayer]: the photo's edges are erased, not painted over.
 */
fun DrawScope.drawEye(eye: EyeAssets, scale: Float, lid: Float, cache: PupilCache? = null) {
    val g = eye.geometry
    val w = size.width
    val k = w / g.width
    val h = g.height * k
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    val dstOff = IntOffset(left.toInt(), top.toInt())
    val dstSize = IntSize(w.toInt(), h.toInt())
    val src = IntSize(g.width, g.height)
    fun img(bitmap: ImageBitmap, alpha: Float = 1f) =
        drawImage(bitmap, IntOffset.Zero, src, dstOff, dstSize, alpha = alpha, filterQuality = FilterQuality.High)

    // 1–2. The eye with its pupil at the wanted size and the window reflected in the cornea.
    if (cache == null || dstSize.width <= 0 || dstSize.height <= 0) {
        drawPupil(eye, scale, Offset(left, top), w)
    } else {
        val key = (scale / PUPIL_STEP).roundToInt()
        val picture = cache.get(key, dstSize) {
            ImageBitmap(dstSize.width, dstSize.height).also { bmp ->
                CanvasDrawScope().draw(this, layoutDirection, Canvas(bmp), Size(dstSize.width.toFloat(), dstSize.height.toFloat())) {
                    drawPupil(eye, key * PUPIL_STEP, Offset.Zero, dstSize.width.toFloat())
                }
            }
        }
        drawImage(picture, dstOffset = dstOff)
    }
    // 3. The lid, between its two nearest frames. The lower frame goes first, fully, so the strip between
    //    the two lid edges is always covered (a plain cross-fade would let the eye show through the lid).
    val stops = g.lids
    val j = stops.indexOfLast { it <= lid }.coerceIn(0, stops.size - 1)
    val t = if (j < stops.size - 1) ((lid - stops[j]) / (stops[j + 1] - stops[j])).coerceIn(0f, 1f) else 0f
    if (t > 0.01f) img(eye.lids[j + 1])
    img(eye.lids[j])
    if (t > 0.01f) img(eye.lids[j + 1], t)
    // 4. The photo's edges fade out: erased (not painted in a colour), so no frame shows on any background.
    val gone = Color.Black
    val v = g.vignette
    val vc = Offset(left + v.cx * k, top + v.cy * k)
    val rx = v.rx * k * 1.25f
    // The photo lands on whole pixels (dstOff/dstSize); erase over exactly that, a pixel wider, so no edge row survives.
    val ex0 = dstOff.x - 1f
    val ey0 = dstOff.y - 1f
    val ew = dstSize.width + 2f
    val eh = dstSize.height + 2f
    clipRect(ex0, ey0, ex0 + ew, ey0 + eh) {
        withTransform({ scale(1f, v.ry / v.rx, vc) }) {
            drawCircle(
                Brush.radialGradient(
                    0f to gone.copy(alpha = 0f), 0.20f to gone.copy(alpha = 0f), 0.40f to gone.copy(alpha = 0.37f),
                    0.60f to gone.copy(alpha = 0.67f), 0.80f to gone.copy(alpha = 0.89f), 1f to gone,
                    center = vc, radius = rx,
                ),
                radius = rx * 3f, center = vc, blendMode = BlendMode.DstOut,
            )
        }
        // All four sides of the photo vanish completely.
        val fade = eh * 0.14f
        val side = ew * 0.10f
        val clear = gone.copy(alpha = 0f)
        drawRect(Brush.verticalGradient(listOf(gone, clear), ey0, ey0 + fade), Offset(ex0, ey0), Size(ew, fade), blendMode = BlendMode.DstOut)
        drawRect(Brush.verticalGradient(listOf(clear, gone), ey0 + eh - fade, ey0 + eh), Offset(ex0, ey0 + eh - fade), Size(ew, fade), blendMode = BlendMode.DstOut)
        drawRect(Brush.horizontalGradient(listOf(gone, clear), ex0, ex0 + side), Offset(ex0, ey0), Size(side, eh), blendMode = BlendMode.DstOut)
        drawRect(Brush.horizontalGradient(listOf(clear, gone), ex0 + ew - side, ex0 + ew), Offset(ex0 + ew - side, ey0), Size(side, eh), blendMode = BlendMode.DstOut)
    }
}

/** Steps in which the pupil's size is drawn (finer than the eye can tell). */
private const val PUPIL_STEP = 0.012f

/** The photo of width [w] at [origin], its iris re-sampled ring by ring for a pupil at [scale], and the reflection. */
private fun DrawScope.drawPupil(eye: EyeAssets, scale: Float, origin: Offset, w: Float) {
    val g = eye.geometry
    val k = w / g.width
    val h = g.height * k
    val left = origin.x
    val top = origin.y
    val dstOff = IntOffset(left.toInt(), top.toInt())
    val dstSize = IntSize(w.toInt(), h.toInt())
    val src = IntSize(g.width, g.height)
    fun img(bitmap: ImageBitmap) = drawImage(bitmap, IntOffset.Zero, src, dstOff, dstSize, filterQuality = FilterQuality.High)

    // 1. The eye, then the iris redrawn ring by ring so the pupil has the wanted size.
    img(eye.base)
    val c = Offset(left + g.pupilX * k, top + g.pupilY * k)
    val pr = g.pupilR * k
    val rw = g.warpR * k
    val r1 = (pr * scale).coerceIn(pr * 0.4f, rw * 0.85f)
    fun source(r: Float): Float = when {
        r < r1 -> r * pr / r1
        r < rw -> pr + (r - r1) * (rw - pr) / (rw - r1)
        else -> r
    }
    val rings = 30
    val edges = buildList {
        add(0f); add(r1)
        for (i in 1..rings) add(r1 + (rw - r1) * i / rings)
    }
    for (i in 0 until edges.size - 1) {
        val ra = edges[i]
        val rb = edges[i + 1]
        val rm = if (ra == 0f) r1 / 2f else (ra + rb) / 2f
        val f = source(rm) / max(rm, 1e-3f)
        val ring = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(Rect(c, rb + 0.6f))
            if (ra > 0f) addOval(Rect(c, ra))
        }
        clipPath(ring) {
            withTransform({ scale(1f / f, 1f / f, c) }) { img(eye.base) }
        }
    }
    // The window reflected in the cornea does not move with the pupil.
    img(eye.reflection)
}

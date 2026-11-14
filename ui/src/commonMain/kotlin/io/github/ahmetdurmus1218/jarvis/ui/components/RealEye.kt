package io.github.ahmetdurmus1218.jarvis.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
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
import io.github.ahmetdurmus1218.jarvis.core.assistant.Emotion
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
    /** Positions of the raised lower lid frames (a smile reaching the eyes); the first is "not raised". */
    val lows: List<Float> = emptyList(),
    /** The lid layers already carry the fade of the photo's edges (bake_fade.py): no erasing per frame. */
    val faded: Boolean = false,
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
    /** The lower lid raised by geometry.lows[1…] (empty if the app has none). */
    val lows: List<ImageBitmap> = emptyList(),
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
            val lows = (1 until geo.lows.size).map { i -> image("$DIR/eye_low_$i.webp") ?: return null }
            EyeAssets(geo, base, reflection, lids, lows)
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

/** How the eyes show a feeling: lids, tilt, pupil, size, height. */
private class EyeFeel(
    /** Upper lid at least this far down (0 open … 1 closed); null: as the mood has it. */
    val upper: Float?,
    /** Lower lid raised (0 … 1): the smile that reaches the eyes. */
    val lower: Float,
    /** Degrees the outer corners go down (sad) or up (negative). */
    val tilt: Float,
    val pupil: Float,
    val grow: Float,
    /** Up (negative) or down, as a part of the eye's height. */
    val drop: Float,
)

private fun feelOf(e: Emotion): EyeFeel = when (e) {
    Emotion.HAPPY -> EyeFeel(0.10f, 0.6f, 0f, 1.08f, 1f, 0f)
    Emotion.JOY -> EyeFeel(0.12f, 1f, -2f, 1.15f, 1.02f, -0.03f)
    Emotion.SAD -> EyeFeel(0.36f, 0f, 7f, 1.12f, 1f, 0.04f)
    Emotion.SURPRISED -> EyeFeel(0f, 0f, 0f, 0.82f, 1.07f, -0.03f)
    Emotion.NONE -> EyeFeel(null, 0f, 0f, 1f, 1f, 0f)
}

/**
 * Rick's real eyes: two by default (the photo and its mirror image, as a face has them), or one ([pair] false).
 * They live (pupil with the light, blinks, a living unrest) and show a feeling for a moment after a turn:
 * a smile lifts the lower lids, sadness droops the upper lids and lowers the outer corners, surprise opens wide.
 */
@Composable
fun RealEye(
    eye: EyeAssets,
    mode: CoreMode,
    level: Float,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    sleeping: Boolean = false,
    emotion: Emotion = JarvisUi.emotion,
    pair: Boolean = true,
) {
    val calm = JarvisUi.reduceMotion
    val feel = feelOf(if (sleeping) Emotion.NONE else emotion)
    // Pupil: the room's light, wider while listening (interest) and with some feelings, a slow living unrest.
    val lux = JarvisUi.ambientLux
    val pupilTarget = pupilForLight(lux) * (if (mode == CoreMode.LISTENING) 1.12f else 1f) * feel.pupil
    val pupil by animateFloatAsState(pupilTarget.coerceIn(0.6f, 1.85f), tween(if (pupilTarget < 1f) 450 else 1100))
    // Lid: where the mood (or the feeling) keeps it, slowly; asleep it closes over two seconds.
    val restTarget = when {
        sleeping -> 1f
        feel.upper == 0f -> 0f
        else -> max(lidForMood(mode, false), feel.upper ?: 0f)
    }
    val rest by animateFloatAsState(restTarget, tween(if (sleeping) 2200 else 500))
    val low by animateFloatAsState(feel.lower, tween(450))
    val tilt by animateFloatAsState(if (calm) 0f else feel.tilt, tween(600))
    val grow by animateFloatAsState(if (calm) 1f else feel.grow, tween(350))
    val drop by animateFloatAsState(if (calm) 0f else feel.drop, tween(600))
    // The eye is costly to draw (a photo, its pupil re-sampled in rings, lid layers), so it is redrawn only as
    // often as something visibly moves: every frame during a blink, ~15 times a second while speaking, at most 4
    // while the pupil merely lives, and not at all while asleep or with reduced motion between blinks.
    var shown by remember { mutableStateOf(EyeTick(0L, -10_000L)) }
    val cache = remember(eye) { PupilCache() }
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
                else -> 250L
            }
            val due = drawn < 0 || (every != Long.MAX_VALUE && t - drawn >= every) || (!blinking && shown.blinking(drawn))
            // Between blinks a redraw that would look the same (the pupil's unrest moved less than a step) is skipped.
            if (due && (blinking || drawn < 0 || shown.blinking(drawn) || cache.behind || wave(t) != wave(shown.now))) {
                shown = EyeTick(t, blinkStart)
            }
            if (due) drawn = t
        }
    }
    // One eye as drawn on its own layer (its edges fade to transparent, so whatever is behind Rick shows through):
    // tilted with the feeling, the left one the mirror image of the photo.
    @Composable
    fun one(m: Modifier, mirror: Boolean) {
        Canvas(
            m.graphicsLayer {
                // Lid layers with the edge fade baked in need no layer of their own (much cheaper to draw).
                if (!eye.geometry.faded) compositingStrategy = CompositingStrategy.Offscreen
                rotationZ = if (mirror) -tilt else tilt
                scaleX = grow
                scaleY = grow
                translationY = drop * this.size.height
            },
        ) {
            val tick = shown
            val scale = (pupil * (1f + wave(tick.now))).coerceIn(0.58f, 1.9f)
            val lid = max(rest, if (sleeping) 0f else blinkAt(tick.now - tick.blinkStart)).coerceIn(0f, 1f)
            drawEye(eye, scale, lid, cache, low = low, mirror = mirror)
        }
    }
    if (!pair) {
        one(modifier.size(size * 1.3f, size), mirror = false)
        return
    }
    // Two eyes about one eye apart, as on a face (the photo is mostly the eye itself, its skin fades out).
    val total = size * 1.28f
    val eyeW = total * 0.42f
    val eyeH = eyeW * 0.74f
    Box(modifier.size(total, eyeH)) {
        one(Modifier.size(eyeW, eyeH), mirror = true)
        one(Modifier.size(eyeW, eyeH).offset(x = total - eyeW), mirror = false)
    }
}

/** The pupil's own movement at [t] ms: slow unrest (hippus), a flicker with the voice; in steps of 2 %. */
private fun pupilWave(t: Long, calm: Boolean, sleeping: Boolean, speaking: Boolean): Float {
    if (calm || sleeping) return 0f
    val clock = t / 1000f
    val hippus = 0.025f * sin(clock * 0.9f) + 0.015f * sin(clock * 2.3f + 1f)
    val flicker = if (speaking) 0.04f * mouthWave(clock) else 0f
    return ((hippus + flicker) * 50f).roundToInt() / 50f
}

/**
 * The eye with its pupil at a given size (photo, re-sampled iris), kept as a picture: the costly
 * ring-by-ring pupil is drawn once per size, not on every frame. A few recent sizes are kept.
 */
class PupilCache {
    private val kept = ArrayList<Triple<Int, IntSize, ImageBitmap>>()
    private var lastRender: kotlin.time.TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * True when a picture was stood in for (a new one was made only moments ago): the eye should be drawn
     * again shortly, when the right one can be made.
     */
    var behind = false
        private set

    /**
     * The picture for [key]: kept, or made now. While the pupil changes size (a mood, the light) at most one
     * new picture is made every [MIN_RENDER_MS]; in between the nearest kept one stands in. Making one costs
     * the processor a moment, and a picture for every frame of a pupil animation would stall the app.
     */
    fun get(key: Int, size: IntSize, render: () -> ImageBitmap): ImageBitmap {
        val i = kept.indexOfFirst { it.first == key && it.second == size }
        if (i >= 0) {
            behind = false
            return kept.removeAt(i).also { kept.add(it) }.third
        }
        val recent = lastRender?.let { it.elapsedNow().inWholeMilliseconds < MIN_RENDER_MS } == true
        val near = kept.filter { it.second == size }.minByOrNull { kotlin.math.abs(it.first - key) }
        if (recent && near != null) {
            behind = true
            return near.third
        }
        val made = render()
        lastRender = kotlin.time.TimeSource.Monotonic.markNow()
        behind = false
        kept.add(Triple(key, size, made))
        if (kept.size > 10) kept.removeAt(0)
        return made
    }

    private companion object {
        const val MIN_RENDER_MS = 140L
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
fun DrawScope.drawEye(
    eye: EyeAssets,
    scale: Float,
    lid: Float,
    cache: PupilCache? = null,
    /** The lower lid raised (0 … 1), a smile. */
    low: Float = 0f,
    /** Drawn as the mirror image (the other eye of the pair). */
    mirror: Boolean = false,
) {
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

    val center = Offset(size.width / 2f, size.height / 2f)
    fun mirrored(block: DrawScope.() -> Unit) =
        if (mirror) withTransform({ scale(-1f, 1f, center) }) { block() } else block()

    mirrored {
        // 1–2. The eye with its pupil at the wanted size and the window reflected in the cornea (it stays where it
        //      is while the pupil changes; the mirrored eye has it mirrored too), its edges already faded out.
        drawPupilPicture(eye, scale, cache ?: PupilCache(), dstOff, dstSize)
        drawLids(eye, lid, low, ::img)
        // Older lid layers without the fade: erase the edges here (needs the eye's own layer, see eyeLayer).
        if (!g.faded) drawFade(eye, left, top, k, dstOff, dstSize)
    }
}

/** The photo with its pupil re-sampled, the reflection and the faded edges, as a picture kept in [cache]. */
private fun DrawScope.drawPupilPicture(eye: EyeAssets, scale: Float, cache: PupilCache, dstOff: IntOffset, dstSize: IntSize) {
    if (dstSize.width <= 0 || dstSize.height <= 0) return
    val g = eye.geometry
    val key = (scale / PUPIL_STEP).roundToInt()
    val picture = cache.get(key, dstSize) {
        ImageBitmap(dstSize.width, dstSize.height).also { bmp ->
            val w = dstSize.width.toFloat()
            CanvasDrawScope().draw(this, layoutDirection, Canvas(bmp), Size(w, dstSize.height.toFloat())) {
                drawPupil(eye, key * PUPIL_STEP, Offset.Zero, w)
                drawImage(eye.reflection, IntOffset.Zero, IntSize(g.width, g.height), IntOffset.Zero, dstSize, filterQuality = FilterQuality.High)
                // A picture of its own, so its edges can be erased here once instead of on every frame.
                drawFade(eye, 0f, 0f, w / g.width, IntOffset.Zero, dstSize)
            }
        }
    }
    drawImage(picture, dstOffset = dstOff)
}

/** The raised lower lid, then the upper lid, each between its two nearest frames. */
private fun DrawScope.drawLids(eye: EyeAssets, lid: Float, low: Float, img: (ImageBitmap, Float) -> Unit) {
    val g = eye.geometry
    // The lower lid (a smile): frame 0 is "not raised", so the first step fades in.
    if (low > 0.005f && eye.lows.isNotEmpty()) {
        val ls = g.lows
        val lj = ls.indexOfLast { it <= low }.coerceIn(0, ls.size - 1)
        val lt = if (lj < ls.size - 1) ((low - ls[lj]) / (ls[lj + 1] - ls[lj])).coerceIn(0f, 1f) else 0f
        if (lj == 0) {
            img(eye.lows[0], lt)
        } else {
            if (lt > 0.01f) img(eye.lows[lj], 1f)
            img(eye.lows[lj - 1], 1f)
            if (lt > 0.01f) img(eye.lows[lj], lt)
        }
    }
    // 3. The upper lid, between its two nearest frames. The lower frame goes first, fully, so the strip between
    //    the two lid edges is always covered (a plain cross-fade would let the eye show through the lid).
    val stops = g.lids
    val j = stops.indexOfLast { it <= lid }.coerceIn(0, stops.size - 1)
    val t = if (j < stops.size - 1) ((lid - stops[j]) / (stops[j + 1] - stops[j])).coerceIn(0f, 1f) else 0f
    if (t > 0.01f) img(eye.lids[j + 1], 1f)
    img(eye.lids[j], 1f)
    if (t > 0.01f) img(eye.lids[j + 1], t)
}

/** 4. The photo's edges fade out: erased (not painted in a colour), so no frame shows on any background. */
private fun DrawScope.drawFade(eye: EyeAssets, left: Float, top: Float, k: Float, dstOff: IntOffset, dstSize: IntSize) {
    val g = eye.geometry
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

/** Steps in which the pupil's size is drawn (2.5 %: under a pixel at the size Rick is shown). */
private const val PUPIL_STEP = 0.025f

/** The photo of width [w] at [origin], its iris re-sampled ring by ring for a pupil at [scale]. */
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
    val rings = 22
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
}

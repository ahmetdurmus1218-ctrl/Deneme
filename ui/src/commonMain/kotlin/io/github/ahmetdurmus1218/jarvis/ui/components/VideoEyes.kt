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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.ahmetdurmus1218.jarvis.ui.CoreMode
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Which of the eye frames (assets/rick-eyes, written by tools/rick-eye/video_eyes.py) are what. */
@Serializable
data class VideoEyesMeta(
    val width: Int,
    val height: Int,
    val fps: Float = 15f,
    @SerialName("idle_fps") val idleFps: Float = 7.5f,
    /** The living loop, played forth and back. */
    val idle: List<Int>,
    /** A real blink: the lid comes down, closes, opens. */
    val blink: List<Int>,
    /** The frame with the eyes shut (sleep). */
    val closed: Int,
    val credits: List<String> = emptyList(),
)

/**
 * Rick's eyes as real video frames of a man's eyes (Mixkit free licence): nothing is drawn or warped, the
 * frames themselves are shown — a gentle living loop, real blinks, the real closed lids for sleep.
 */
class VideoEyesAssets(val meta: VideoEyesMeta, val frames: List<ImageBitmap>) {
    companion object {
        const val DIR = "rick-eyes"
        private val json = Json { ignoreUnknownKeys = true }

        /** Loads the frames bundled with the app; null if anything is missing (Rick then uses the glass sphere). */
        fun load(image: (String) -> ImageBitmap?, text: (String) -> String?): VideoEyesAssets? = runCatching {
            val meta = json.decodeFromString(VideoEyesMeta.serializer(), text("$DIR/eyes.json") ?: return null)
            val count = (meta.idle + meta.blink + meta.closed).max() + 1
            val frames = (0 until count).map { i -> image("$DIR/eyes_${i.toString().padStart(3, '0')}.webp") ?: return null }
            VideoEyesAssets(meta, frames)
        }.getOrNull()
    }
}

/**
 * The frames to show and for how long ([ms] each), from what Rick is doing: asleep the lids come down slowly
 * and stay shut; waking they open; awake the eyes live and blink every few seconds.
 */
class EyePlayer(private val meta: VideoEyesMeta, private val random: Random = Random.Default) {
    private val queue = ArrayDeque<Pair<Int, Long>>()
    private var idlePos = 0
    private var dir = 1
    private var nextBlink = -1L
    var shown = meta.idle.first()
        private set

    private val closedAt = meta.blink.indexOf(meta.closed).coerceAtLeast(0)

    fun fallAsleep() {
        queue.clear()
        meta.blink.take(closedAt + 1).forEach { queue.add(it to 160L) }
    }

    fun wakeUp() {
        queue.clear()
        if (shown == meta.closed) meta.blink.drop(closedAt).forEach { queue.add(it to 90L) }
    }

    /** Advances to time [t] (ms); returns how long until the next change. */
    fun step(t: Long, sleeping: Boolean): Long {
        queue.removeFirstOrNull()?.let { (frame, ms) ->
            shown = frame
            return ms
        }
        if (sleeping) {
            shown = meta.closed
            return 1_000L
        }
        if (nextBlink < 0) nextBlink = t + random.nextLong(2_400, 6_200)
        if (t >= nextBlink) {
            val frameMs = (1_000f / meta.fps).toLong()
            meta.blink.forEach { queue.add(it to frameMs) }
            // Now and then twice in a row, as people do.
            if (random.nextFloat() < 0.15f) meta.blink.forEach { queue.add(it to frameMs) }
            nextBlink = t + random.nextLong(2_400, 6_200)
            return 0L
        }
        if (meta.idle.size > 1) {
            idlePos += dir
            if (idlePos >= meta.idle.size - 1 || idlePos <= 0) dir = -dir
            idlePos = idlePos.coerceIn(0, meta.idle.size - 1)
        }
        shown = meta.idle[idlePos]
        return (1_000f / meta.idleFps).toLong()
    }
}

@Composable
fun VideoEyes(
    eyes: VideoEyesAssets,
    mode: CoreMode,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    sleeping: Boolean = false,
) {
    val player = remember(eyes) { EyePlayer(eyes.meta) }
    var frame by remember { mutableStateOf(player.shown) }
    LaunchedEffect(eyes, sleeping) {
        if (sleeping) player.fallAsleep() else player.wakeUp()
        var until = -1L
        while (isActive) {
            // An "infinite animation" frame, so UI tests (which stop those) can go idle.
            val t = try { withInfiniteAnimationFrameMillis { it } } catch (e: kotlinx.coroutines.CancellationException) {
                if (!isActive) throw e
                return@LaunchedEffect
            }
            if (t < until) continue
            until = t + player.step(t, sleeping)
            frame = player.shown
        }
    }
    // Listening, Rick leans in a little; asleep it sinks back.
    val lean by animateFloatAsState(
        when {
            sleeping -> 0.97f
            mode == CoreMode.LISTENING && !JarvisUi.reduceMotion -> 1.05f
            else -> 1f
        },
        tween(500),
    )
    val m = eyes.meta
    val w = size * 1.32f
    Canvas(modifier.size(w, w * (m.height.toFloat() / m.width)).graphicsLayer { scaleX = lean; scaleY = lean }) {
        val bmp = eyes.frames.getOrNull(frame) ?: return@Canvas
        drawImage(
            bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height),
            IntOffset.Zero, IntSize(this.size.width.toInt(), this.size.height.toInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

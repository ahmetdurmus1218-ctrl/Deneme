package io.github.ahmetdurmus1218.jarvis.core.vision

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Hand shapes for synthetic hands (camera-less demo and tests). */
enum class HandShape { OPEN, FIST, PINCH, POINT, THUMB, VICTORY }

/**
 * Builds plausible 21-point hands and plays a looping choreography through every effect, so the
 * effects can be tried without a camera (and shown on the PC). No command is sent in the demo.
 */
object DemoHands {
    /** A right hand with its wrist at ([cx], [cy]) (0..1), [s] = size, [mirror] = left hand. */
    fun hand(shape: HandShape, cx: Float, cy: Float, s: Float = 1f, mirror: Boolean = false): Hand {
        val pts = MutableList(21) { Point(0.5f, 0.8f) }
        pts[1] = Point(0.45f, 0.72f)
        when (shape) {
            HandShape.OPEN -> { pts[2] = Point(0.40f, 0.68f); pts[3] = Point(0.35f, 0.65f); pts[4] = Point(0.28f, 0.58f) }
            HandShape.THUMB -> { pts[1] = Point(0.45f, 0.70f); pts[2] = Point(0.43f, 0.60f); pts[3] = Point(0.42f, 0.52f); pts[4] = Point(0.42f, 0.45f) }
            HandShape.PINCH -> { pts[2] = Point(0.40f, 0.66f); pts[3] = Point(0.35f, 0.60f); pts[4] = Point(0.32f, 0.54f) }
            else -> { pts[2] = Point(0.43f, 0.68f); pts[3] = Point(0.44f, 0.66f); pts[4] = Point(0.46f, 0.64f) }
        }
        val mcp = listOf(Point(0.44f, 0.60f), Point(0.50f, 0.58f), Point(0.55f, 0.60f), Point(0.60f, 0.62f))
        val ext = when (shape) {
            HandShape.OPEN -> listOf(true, true, true, true)
            HandShape.POINT -> listOf(true, false, false, false)
            HandShape.VICTORY -> listOf(true, true, false, false)
            else -> listOf(false, false, false, false)
        }
        for (i in 0 until 4) {
            val b = 5 + 4 * i
            val m = mcp[i]
            pts[b] = m
            if (ext[i]) {
                pts[b + 1] = Point(m.x, m.y - 0.07f); pts[b + 2] = Point(m.x, m.y - 0.12f); pts[b + 3] = Point(m.x, m.y - 0.17f)
            } else {
                pts[b + 1] = Point(m.x, m.y - 0.05f); pts[b + 2] = Point(m.x, m.y - 0.02f); pts[b + 3] = Point(m.x, m.y + 0.03f)
            }
        }
        if (shape == HandShape.PINCH) {
            pts[6] = Point(0.40f, 0.52f); pts[7] = Point(0.36f, 0.50f); pts[8] = Point(0.33f, 0.52f)
        }
        return Hand(pts.map { p ->
            val dx = (p.x - 0.5f) * s * (if (mirror) -1f else 1f)
            Point(cx + dx, cy + (p.y - 0.8f) * s)
        })
    }

    /** One scene of the loop: what it shows and the hands at time [t] (ms) into the scene. */
    class Scene(val title: String, val durationMs: Long, val hands: (Long) -> List<Hand>)

    val scenes: List<Scene> = listOf(
        Scene("✋ Açık el: ateş", 3_000) { t -> listOf(hand(HandShape.OPEN, 0.5f, 0.85f + 0.02f * sin(t / 300f), 1.6f)) },
        Scene("✋✋ İki el: mühür halkaları", 3_500) { _ ->
            listOf(hand(HandShape.OPEN, 0.3f, 0.85f, 1.3f, mirror = true), hand(HandShape.OPEN, 0.7f, 0.85f, 1.3f))
        },
        Scene("🤏 Çimdik: gezegen", 4_000) { t ->
            val a = t / 4_000f * 2f * PI.toFloat()
            listOf(hand(HandShape.PINCH, 0.55f + 0.18f * cos(a), 0.85f + 0.08f * sin(a), 1.5f))
        },
        Scene("🤏🤏 İki elle büyüt", 3_000) { t ->
            val spread = 0.12f + 0.18f * (t / 3_000f)
            listOf(hand(HandShape.PINCH, 0.55f - spread, 0.85f, 1.3f, mirror = true), hand(HandShape.PINCH, 0.55f + spread, 0.85f, 1.3f))
        },
        Scene("✊ → ✋ Patlat", 2_500) { t ->
            listOf(hand(if (t < 1_200) HandShape.FIST else HandShape.OPEN, 0.55f, 0.85f, 1.5f))
        },
        Scene("☝️☝️ Şimşek", 3_000) { t ->
            val w = 0.02f * sin(t / 150f)
            listOf(hand(HandShape.POINT, 0.28f, 0.88f + w, 1.3f, mirror = true), hand(HandShape.POINT, 0.72f, 0.88f - w, 1.3f))
        },
        Scene("☝️ Işık izi", 3_500) { t ->
            val a = t / 1_100f * 2f * PI.toFloat()
            listOf(hand(HandShape.POINT, 0.5f + 0.2f * cos(a), 0.9f + 0.12f * sin(2 * a), 1.3f))
        },
        Scene("👍 Basılı tut: komut", 2_500) { _ -> listOf(hand(HandShape.THUMB, 0.5f, 0.88f, 1.5f)) },
    )

    val loopMs: Long = scenes.sumOf { it.durationMs }

    /** The scene and hands at time [t] (ms) since the demo started. */
    fun at(t: Long): Pair<Scene, List<Hand>> {
        var r = ((t % loopMs) + loopMs) % loopMs
        for (sc in scenes) {
            if (r < sc.durationMs) return sc to sc.hands(r)
            r -= sc.durationMs
        }
        return scenes.last() to emptyList()
    }
}

package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer
import io.github.ahmetdurmus1218.jarvis.core.vision.DemoHands
import io.github.ahmetdurmus1218.jarvis.core.vision.Hand
import io.github.ahmetdurmus1218.jarvis.core.vision.HandShape
import io.github.ahmetdurmus1218.jarvis.core.vision.Point
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AirPointerTest {
    private val w = 1000f
    private val h = 1000f

    /** A synthetic hand in screen pixels (1000 × 1000). */
    private fun hand(shape: HandShape, cx: Float, cy: Float, turn: Float = 0f): Hand {
        val n = DemoHands.hand(shape, cx, cy, 1.4f)
        val w0 = n.points[0]
        return Hand(n.points.map { p ->
            val dx = p.x - w0.x
            val dy = p.y - w0.y
            val c = cos(turn)
            val s = sin(turn)
            Point((w0.x + dx * c - dy * s) * w, (w0.y + dx * s + dy * c) * h)
        })
    }

    private fun AirPointer.run(from: Long, ms: Long, f: (Long) -> Hand?, clickable: (Float, Float) -> Boolean = { _, _ -> true }): List<AirPointer.Event> {
        val out = mutableListOf<AirPointer.Event>()
        var t = from
        while (t < from + ms) {
            out += update(t, f(t), w, h, clickable)
            t += 33
        }
        return out
    }

    @Test
    fun holdingTheFingerStillOverAButtonClicksOnce() {
        val p = AirPointer()
        val events = p.run(0, 2_000, { hand(HandShape.POINT, 0.5f, 0.8f) })
        assertEquals(1, events.count { it is AirPointer.Event.Click })
        val click = events.filterIsInstance<AirPointer.Event.Click>().single()
        val tip = hand(HandShape.POINT, 0.5f, 0.8f).points[8]
        assertTrue(kotlin.math.abs(click.x - tip.x) < 5f && kotlin.math.abs(click.y - tip.y) < 5f, "$click vs $tip")
        // Over nothing clickable: no click.
        assertTrue(AirPointer().run(0, 2_000, { hand(HandShape.POINT, 0.5f, 0.8f) }, { _, _ -> false }).none { it is AirPointer.Event.Click })
    }

    @Test
    fun aQuickPinchClicksWhereItWas() {
        val p = AirPointer()
        val events = p.run(0, 900, { t -> if (t in 300..500) hand(HandShape.PINCH, 0.5f, 0.8f) else hand(HandShape.OPEN, 0.5f, 0.8f) })
        assertEquals(1, events.count { it is AirPointer.Event.Click }, events.toString())
        // A long pinch is not a click.
        val long = AirPointer().run(0, 2_000, { t -> if (t in 200..1_600) hand(HandShape.PINCH, 0.5f, 0.8f) else hand(HandShape.OPEN, 0.5f, 0.8f) })
        assertTrue(long.none { it is AirPointer.Event.Click }, long.toString())
    }

    @Test
    fun pinchAndMoveScrollsInsteadOfClicking() {
        val events = AirPointer().run(0, 1_200, { t -> hand(HandShape.PINCH, 0.5f, 0.6f + 0.25f * (t / 1_200f)) })
        val scroll = events.filterIsInstance<AirPointer.Event.Scroll>().sumOf { it.dy.toDouble() }
        assertTrue(scroll > 150, "scrolled $scroll")
        assertTrue(events.none { it is AirPointer.Event.Click })
    }

    @Test
    fun pinchAndTurnRotates() {
        val events = AirPointer().run(0, 1_000, { t -> hand(HandShape.PINCH, 0.5f, 0.8f, turn = 0.8f * t / 1_000f) })
        val turned = events.filterIsInstance<AirPointer.Event.Rotate>().sumOf { it.degrees.toDouble() }
        assertTrue(turned > 25, "turned $turned degrees")
    }

    @Test
    fun aFastOpenHandSwipes() {
        val right = AirPointer().run(0, 400, { t -> hand(HandShape.OPEN, 0.3f + 0.4f * (t / 300f).coerceAtMost(1f), 0.8f) })
        assertEquals(listOf(AirPointer.Direction.RIGHT), right.filterIsInstance<AirPointer.Event.Swipe>().map { it.direction })
        val up = AirPointer().run(0, 400, { t -> hand(HandShape.OPEN, 0.5f, 0.9f - 0.4f * (t / 300f).coerceAtMost(1f)) })
        assertEquals(listOf(AirPointer.Direction.UP), up.filterIsInstance<AirPointer.Event.Swipe>().map { it.direction })
        // A slow drift is not a swipe.
        val slow = AirPointer().run(0, 3_000, { t -> hand(HandShape.OPEN, 0.3f + 0.4f * (t / 3_000f), 0.8f) })
        assertTrue(slow.none { it is AirPointer.Event.Swipe })
    }
}

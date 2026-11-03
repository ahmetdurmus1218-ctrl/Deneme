package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.vision.Gesture
import io.github.ahmetdurmus1218.jarvis.core.vision.GestureClassifier
import io.github.ahmetdurmus1218.jarvis.core.vision.GestureTrigger
import io.github.ahmetdurmus1218.jarvis.core.vision.Hand
import io.github.ahmetdurmus1218.jarvis.core.vision.Point
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class GesturesTest {
    enum class Thumb { OUT, IN, UP }

    /**
     * A right hand seen by the camera, palm up the image: wrist at the bottom, fingers pointing up.
     * [fingers] = extended flags for index, middle, ring, pinky.
     */
    private fun hand(fingers: List<Boolean>, thumb: Thumb, scale: Float = 1f, dx: Float = 0f, dy: Float = 0f, angleDeg: Float = 0f): Hand {
        val wrist = Point(0.5f, 0.8f)
        val pts = MutableList(21) { wrist }
        pts[1] = Point(0.45f, 0.72f)
        when (thumb) {
            Thumb.OUT -> { pts[2] = Point(0.40f, 0.68f); pts[3] = Point(0.35f, 0.65f); pts[4] = Point(0.28f, 0.58f) }
            Thumb.IN -> { pts[2] = Point(0.43f, 0.68f); pts[3] = Point(0.44f, 0.66f); pts[4] = Point(0.46f, 0.64f) }
            Thumb.UP -> { pts[1] = Point(0.45f, 0.70f); pts[2] = Point(0.43f, 0.60f); pts[3] = Point(0.42f, 0.52f); pts[4] = Point(0.42f, 0.45f) }
        }
        val mcp = listOf(Point(0.44f, 0.60f), Point(0.50f, 0.58f), Point(0.55f, 0.60f), Point(0.60f, 0.62f))
        for (i in 0 until 4) {
            val base = 5 + 4 * i
            val m = mcp[i]
            pts[base] = m
            if (fingers[i]) {
                pts[base + 1] = Point(m.x, m.y - 0.07f); pts[base + 2] = Point(m.x, m.y - 0.12f); pts[base + 3] = Point(m.x, m.y - 0.17f)
            } else {
                pts[base + 1] = Point(m.x, m.y - 0.05f); pts[base + 2] = Point(m.x, m.y - 0.02f); pts[base + 3] = Point(m.x, m.y + 0.03f)
            }
        }
        val a = angleDeg / 180f * kotlin.math.PI.toFloat()
        return Hand(pts.map { p ->
            val x = (p.x - wrist.x) * scale
            val y = (p.y - wrist.y) * scale
            Point(wrist.x + dx + x * cos(a) - y * sin(a), wrist.y + dy + x * sin(a) + y * cos(a))
        })
    }

    private val all = listOf(true, true, true, true)
    private val none = listOf(false, false, false, false)

    @Test
    fun shapesAreRecognized() {
        assertEquals(Gesture.OPEN_PALM, GestureClassifier.classify(hand(all, Thumb.OUT)))
        assertEquals(Gesture.FIST, GestureClassifier.classify(hand(none, Thumb.IN)))
        assertEquals(Gesture.THUMBS_UP, GestureClassifier.classify(hand(none, Thumb.UP)))
        assertEquals(Gesture.VICTORY, GestureClassifier.classify(hand(listOf(true, true, false, false), Thumb.IN)))
        assertEquals(Gesture.POINTING, GestureClassifier.classify(hand(listOf(true, false, false, false), Thumb.IN)))
        assertEquals(Gesture.NONE, GestureClassifier.classify(hand(listOf(true, false, true, false), Thumb.IN)))
    }

    @Test
    fun distanceAndPositionDoNotMatter() {
        for (s in listOf(0.4f, 0.7f, 1.3f)) {
            assertEquals(Gesture.OPEN_PALM, GestureClassifier.classify(hand(all, Thumb.OUT, scale = s, dx = -0.1f)), "scale $s")
            assertEquals(Gesture.VICTORY, GestureClassifier.classify(hand(listOf(true, true, false, false), Thumb.IN, scale = s, dy = 0.05f)), "scale $s")
            assertEquals(Gesture.THUMBS_UP, GestureClassifier.classify(hand(none, Thumb.UP, scale = s)), "scale $s")
        }
    }

    @Test
    fun aTiltedHandStillCounts() {
        assertEquals(Gesture.OPEN_PALM, GestureClassifier.classify(hand(all, Thumb.OUT, angleDeg = 35f)))
        assertEquals(Gesture.POINTING, GestureClassifier.classify(hand(listOf(true, false, false, false), Thumb.IN, angleDeg = -40f)))
        // Thumbs-up means up: the same shape upside down is not one.
        assertNotEquals(Gesture.THUMBS_UP, GestureClassifier.classify(hand(none, Thumb.UP, angleDeg = 180f)))
    }

    @Test
    fun gesturesFireOnceWhenHeld() {
        val t = GestureTrigger(holdFrames = 5, cooldownMs = 2_000)
        var now = 0L
        fun step(g: Gesture) = t.feed(g, now).also { now += 33 }
        repeat(4) { assertNull(step(Gesture.THUMBS_UP)) }
        assertEquals(Gesture.THUMBS_UP, step(Gesture.THUMBS_UP), "fires on the 5th frame")
        repeat(20) { assertNull(step(Gesture.THUMBS_UP)) } // held on: no repeats (about 0.7 s)
        repeat(3) { assertNull(step(Gesture.NONE)) }
        repeat(4) { assertNull(step(Gesture.THUMBS_UP)) }
        assertNull(step(Gesture.THUMBS_UP), "still inside the cooldown")
        now += 3_000
        repeat(4) { step(Gesture.NONE) }
        repeat(4) { assertNull(step(Gesture.THUMBS_UP)) }
        assertEquals(Gesture.THUMBS_UP, step(Gesture.THUMBS_UP), "fires again after the cooldown")
        repeat(4) { assertNull(step(Gesture.VICTORY)) }
        assertEquals(Gesture.VICTORY, step(Gesture.VICTORY), "another gesture is not blocked")
    }

    @Test
    fun flickeringDoesNotFire() {
        val t = GestureTrigger(holdFrames = 5)
        val seq = listOf(Gesture.OPEN_PALM, Gesture.NONE, Gesture.OPEN_PALM, Gesture.OPEN_PALM, Gesture.FIST, Gesture.OPEN_PALM, Gesture.OPEN_PALM)
        seq.forEachIndexed { i, g -> assertNull(t.feed(g, i * 33L)) }
    }
}

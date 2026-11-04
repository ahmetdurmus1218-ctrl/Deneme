package io.github.ahmetdurmus1218.jarvis.core.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The hand as a pointer in the air (hand control with the camera). Coordinates are screen pixels,
 * already mapped from the camera image the way the hand is drawn, so the cursor sits on the
 * fingertip the user sees.
 *
 * - **Cursor:** the index fingertip, smoothed.
 * - **Click:** a quick pinch (thumb and index tips touch and part) at the cursor, or holding the
 *   pointing finger still over something clickable for [dwellMs].
 * - **Scroll:** pinch and move up / down (the content follows the hand).
 * - **Rotate:** pinch and turn the hand (wrist → middle finger angle).
 * - **Swipe:** an open hand moved quickly left / right / up / down.
 *
 * Pure logic, one call per camera frame; nothing is recorded.
 */
class AirPointer(
    private val dwellMs: Long = 900,
    private val clickMaxMs: Long = 600,
    /** A swipe covers this share of the screen's width (or height) … */
    private val swipeShare: Float = 0.22f,
    /** … within this time. */
    private val swipeWindowMs: Long = 350,
    private val swipeCooldownMs: Long = 700,
) {
    enum class Direction { LEFT, RIGHT, UP, DOWN }

    sealed interface Event {
        data class Click(val x: Float, val y: Float) : Event
        data class Scroll(val dy: Float) : Event
        data class Rotate(val degrees: Float) : Event
        data class Swipe(val direction: Direction) : Event
    }

    /** Cursor position (null without a hand), dwell progress 0…1, and whether the hand pinches. */
    data class State(val x: Float, val y: Float, val dwell: Float, val pinching: Boolean)

    var state: State? = null
        private set

    private var cx = Float.NaN
    private var cy = Float.NaN
    private var pinching = false
    private var pinchOff = 0
    private var pinchStart = 0L
    private var pinchX = 0f
    private var pinchY = 0f
    private var pinchMoved = false
    private var lastPinchY = 0f
    private var lastAngle = Float.NaN
    private var dwellStart = -1L
    private var dwellX = 0f
    private var dwellY = 0f
    private var dwellDone = false
    private val palmTrail = ArrayDeque<Triple<Long, Float, Float>>()
    private var lastSwipe = Long.MIN_VALUE / 2

    fun reset() {
        state = null
        cx = Float.NaN
        cy = Float.NaN
        pinching = false
        pinchOff = 0
        lastAngle = Float.NaN
        dwellStart = -1
        dwellDone = false
        palmTrail.clear()
    }

    /**
     * One frame. [hand] holds screen-pixel points (x, y in pixels), [width] × [height] is the screen;
     * [clickable] says whether something can be clicked at a point (for the dwell click).
     */
    fun update(now: Long, hand: Hand?, width: Float, height: Float, clickable: (Float, Float) -> Boolean = { _, _ -> true }): List<Event> {
        if (hand == null) {
            reset()
            return emptyList()
        }
        val out = mutableListOf<Event>()
        val p = hand.points
        val gesture = GestureClassifier.classify(hand)
        val palm = hypot(p[0].x - p[9].x, p[0].y - p[9].y).coerceAtLeast(1f)

        // Pinch with a little hysteresis (one missed frame does not end it).
        val touching = GestureClassifier.isPinch(hand) || hypot(p[4].x - p[8].x, p[4].y - p[8].y) < palm * 0.28f
        // Cursor: the index fingertip (while pinching: where thumb and index meet), smoothed against jitter.
        val tx = if (touching) (p[4].x + p[8].x) / 2f else p[8].x
        val ty = if (touching) (p[4].y + p[8].y) / 2f else p[8].y
        if (cx.isNaN() || touching != pinching) { cx = tx; cy = ty } else { cx += (tx - cx) * 0.45f; cy += (ty - cy) * 0.45f }
        if (touching) {
            pinchOff = 0
            if (!pinching) {
                pinching = true
                pinchStart = now
                pinchX = cx
                pinchY = cy
                lastPinchY = cy
                pinchMoved = false
                lastAngle = Float.NaN
            }
        } else if (pinching && ++pinchOff >= 2) {
            pinching = false
            if (!pinchMoved && now - pinchStart <= clickMaxMs) out += Event.Click(pinchX, pinchY)
            lastAngle = Float.NaN
        }
        if (pinching && touching) {
            // Drag to scroll: the content follows the hand.
            val dy = cy - lastPinchY
            if (abs(cy - pinchY) > height * 0.05f) pinchMoved = true
            if (pinchMoved && abs(dy) > 0.5f) out += Event.Scroll(dy)
            lastPinchY = cy
            // Turn the hand to rotate.
            val angle = (atan2(p[9].y - p[0].y, p[9].x - p[0].x) * 180.0 / kotlin.math.PI).toFloat()
            if (!lastAngle.isNaN()) {
                var d = angle - lastAngle
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                if (abs(d) > 1.5f) {
                    out += Event.Rotate(d)
                    if (abs(d) > 4f) pinchMoved = true
                }
            }
            lastAngle = angle
        }

        // Dwell: the pointing finger held still over something clickable.
        var dwell = 0f
        if (gesture == Gesture.POINTING && !pinching && clickable(cx, cy)) {
            if (dwellStart < 0 || hypot(cx - dwellX, cy - dwellY) > minOf(width, height) * 0.035f) {
                dwellStart = now
                dwellX = cx
                dwellY = cy
                dwellDone = false
            }
            dwell = ((now - dwellStart).toFloat() / dwellMs).coerceIn(0f, 1f)
            if (dwell >= 1f && !dwellDone) {
                dwellDone = true
                out += Event.Click(dwellX, dwellY)
            }
        } else {
            dwellStart = -1
            dwellDone = false
        }

        // Swipe: an open hand moving fast.
        if (gesture == Gesture.OPEN_PALM) {
            val px = (p[0].x + p[5].x + p[9].x + p[13].x + p[17].x) / 5f
            val py = (p[0].y + p[5].y + p[9].y + p[13].y + p[17].y) / 5f
            palmTrail.addLast(Triple(now, px, py))
            while (palmTrail.isNotEmpty() && now - palmTrail.first().first > swipeWindowMs) palmTrail.removeFirst()
            val first = palmTrail.first()
            val dx = px - first.second
            val dy = py - first.third
            if (now - lastSwipe > swipeCooldownMs) {
                val dir = when {
                    abs(dx) > width * swipeShare && abs(dx) > abs(dy) * 1.5f -> if (dx > 0) Direction.RIGHT else Direction.LEFT
                    abs(dy) > height * swipeShare && abs(dy) > abs(dx) * 1.5f -> if (dy > 0) Direction.DOWN else Direction.UP
                    else -> null
                }
                if (dir != null) {
                    lastSwipe = now
                    palmTrail.clear()
                    out += Event.Swipe(dir)
                }
            }
        } else {
            palmTrail.clear()
        }

        state = State(cx, cy, dwell, pinching)
        return out
    }
}

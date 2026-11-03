package io.github.ahmetdurmus1218.jarvis.core.vision

import kotlin.math.sqrt

/** A hand landmark in image coordinates normalized to 0..1 (y grows downwards), as MediaPipe gives them. */
data class Point(val x: Float, val y: Float, val z: Float = 0f)

/** 21 landmarks: 0 wrist; thumb 1–4; index 5–8; middle 9–12; ring 13–16; pinky 17–20 (MCP, PIP, DIP, TIP). */
data class Hand(val points: List<Point>) {
    init {
        require(points.size == 21) { "a hand has 21 landmarks" }
    }
}

/** A detected face as a box in normalized image coordinates. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float)

enum class Gesture(val label: String) {
    NONE(""),
    OPEN_PALM("Açık el"),
    FIST("Yumruk"),
    THUMBS_UP("Başparmak yukarı"),
    VICTORY("Zafer işareti"),
    POINTING("İşaret parmağı"),
    PINCH("Çimdik"),
}

/**
 * Recognizes hand shapes from landmarks, independent of hand size and distance: a finger counts as
 * extended when its tip is clearly farther from the wrist than its middle joint.
 */
object GestureClassifier {
    private fun d(a: Point, b: Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** Index, middle, ring, pinky: tip farther from the wrist than the PIP joint. */
    fun fingersExtended(h: Hand): BooleanArray {
        val p = h.points
        val wrist = p[0]
        return BooleanArray(4) { i ->
            val pip = p[6 + 4 * i]
            val tip = p[8 + 4 * i]
            d(wrist, tip) > d(wrist, pip) * 1.15f
        }
    }

    /** Thumb: its tip is far from the index finger's knuckle compared with the palm size. */
    fun thumbExtended(h: Hand): Boolean {
        val p = h.points
        val palm = d(p[0], p[9]).coerceAtLeast(1e-4f)
        return d(p[4], p[5]) > palm * 0.55f && d(p[4], p[0]) > d(p[3], p[0])
    }

    /** Thumb and index fingertips touching, with the index finger reaching out (not curled into a fist). */
    fun isPinch(h: Hand): Boolean {
        val p = h.points
        val palm = d(p[0], p[9]).coerceAtLeast(1e-4f)
        return d(p[4], p[8]) < palm * 0.3f && d(p[0], p[8]) > d(p[0], p[5]) * 1.1f
    }

    fun classify(h: Hand): Gesture {
        val f = fingersExtended(h)
        val thumb = thumbExtended(h)
        val p = h.points
        val count = f.count { it }
        return when {
            isPinch(h) && !(count == 4 && thumb) -> Gesture.PINCH
            count == 4 && thumb -> Gesture.OPEN_PALM
            count == 0 && thumb && p[4].y < p[5].y && p[4].y < p[0].y - d(p[0], p[9]) * 0.3f -> Gesture.THUMBS_UP
            count == 0 && !thumb -> Gesture.FIST
            f[0] && f[1] && !f[2] && !f[3] -> Gesture.VICTORY
            f[0] && !f[1] && !f[2] && !f[3] -> Gesture.POINTING
            else -> Gesture.NONE
        }
    }
}

/**
 * Turns per-frame gestures into commands: a gesture fires once it has been held for [holdFrames]
 * consecutive frames; holding it longer does not repeat it, and the same gesture cannot fire again
 * within [cooldownMs] (a flickering detection must not trigger twice). Keeps a waving hand from
 * triggering anything by accident.
 */
class GestureTrigger(private val holdFrames: Int = 8, private val cooldownMs: Long = 2_000) {
    private var current = Gesture.NONE
    private var run = 0
    private var lastFired = Gesture.NONE
    private var lastFiredAt = Long.MIN_VALUE / 2

    /** Returns the gesture to act on now, or null. */
    fun feed(g: Gesture, nowMs: Long): Gesture? {
        if (g != current) {
            current = g
            run = 0
        }
        run++
        if (g == Gesture.NONE || run != holdFrames) return null
        if (g == lastFired && nowMs - lastFiredAt < cooldownMs) return null
        lastFired = g
        lastFiredAt = nowMs
        return g
    }
}

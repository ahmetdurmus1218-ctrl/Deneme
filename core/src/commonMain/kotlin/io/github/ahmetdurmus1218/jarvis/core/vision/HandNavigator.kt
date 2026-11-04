package io.github.ahmetdurmus1218.jarvis.core.vision

import kotlin.math.abs
import kotlin.math.hypot

/** What a hand movement asks for, whatever is on the screen; the app decides what it means right now. */
enum class HandCommand(val label: String) {
    NEXT("⏭ İleri"),
    PREVIOUS("⏮ Geri"),
    SCROLL_DOWN("⬇ Aşağı"),
    SCROLL_UP("⬆ Yukarı"),
    PAUSE_PLAY("⏯ Dur / devam"),
    LISTEN("🎤 Dinliyorum"),
    QUIET("🤫 Sus"),
    VOLUME_UP("🔊 Ses +"),
    VOLUME_DOWN("🔉 Ses −"),
}

/**
 * Hands as a remote control while Rick is used normally (not only on the effects screen):
 *
 * - ✋ open hand swiped left / right → NEXT / PREVIOUS (the next song, the next page);
 *   swiped up / down → SCROLL_DOWN / SCROLL_UP (like pushing a page).
 * - ✋ open hand held still (a "stop" sign) → PAUSE_PLAY.
 * - 👍 held → LISTEN; ✊ held → QUIET.
 * - 🤏 pinch and turn the wrist → VOLUME_UP / VOLUME_DOWN (one step per [volumeStepDeg]).
 * - ☝️ / 🤏 also drive the air cursor (clicks and drags: see [AirPointer]).
 *
 * Hands are in screen pixels. Pure logic, one call per camera frame.
 */
class HandNavigator(
    private val holdPalmMs: Long = 1_200,
    private val holdThumbMs: Long = 1_000,
    private val holdFistMs: Long = 1_300,
    private val volumeStepDeg: Float = 25f,
    private val cooldownMs: Long = 1_500,
) {
    val pointer = AirPointer()

    private var held = Gesture.NONE
    private var heldSince = 0L
    private var heldX = 0f
    private var heldY = 0f
    private var heldFired = false
    private var turn = 0f
    private val lastFired = HashMap<HandCommand, Long>()

    data class Result(val commands: List<HandCommand>, val pointerEvents: List<AirPointer.Event>)

    fun reset() {
        pointer.reset()
        held = Gesture.NONE
        heldFired = false
        turn = 0f
    }

    fun update(now: Long, hand: Hand?, width: Float, height: Float, clickable: (Float, Float) -> Boolean = { _, _ -> false }): Result {
        if (hand == null) {
            reset()
            return Result(emptyList(), emptyList())
        }
        val events = pointer.update(now, hand, width, height, clickable)
        val out = mutableListOf<HandCommand>()
        fun fire(c: HandCommand) {
            if (now - (lastFired[c] ?: Long.MIN_VALUE / 2) < cooldownMs && c != HandCommand.VOLUME_UP && c != HandCommand.VOLUME_DOWN) return
            lastFired[c] = now
            out += c
        }
        for (e in events) when (e) {
            is AirPointer.Event.Swipe -> fire(
                when (e.direction) {
                    AirPointer.Direction.LEFT -> HandCommand.NEXT
                    AirPointer.Direction.RIGHT -> HandCommand.PREVIOUS
                    AirPointer.Direction.UP -> HandCommand.SCROLL_DOWN
                    AirPointer.Direction.DOWN -> HandCommand.SCROLL_UP
                },
            )
            is AirPointer.Event.Rotate -> {
                turn += e.degrees
                while (turn >= volumeStepDeg) { turn -= volumeStepDeg; fire(HandCommand.VOLUME_UP) }
                while (turn <= -volumeStepDeg) { turn += volumeStepDeg; fire(HandCommand.VOLUME_DOWN) }
            }
            else -> {}
        }
        if (pointer.state?.pinching != true) turn = 0f

        // Held shapes: the hand must stay roughly in place (a swipe is not a hold).
        val g = GestureClassifier.classify(hand)
        val p = hand.points
        val palm = hypot(p[0].x - p[9].x, p[0].y - p[9].y).coerceAtLeast(1f)
        val cx = p[9].x
        val cy = p[9].y
        if (g != held || hypot(cx - heldX, cy - heldY) > palm * 0.6f) {
            held = g
            heldSince = now
            heldX = cx
            heldY = cy
            heldFired = false
        }
        if (!heldFired) {
            val need = when (g) {
                Gesture.OPEN_PALM -> holdPalmMs
                Gesture.THUMBS_UP -> holdThumbMs
                Gesture.FIST -> holdFistMs
                else -> Long.MAX_VALUE
            }
            if (now - heldSince >= need) {
                heldFired = true
                fire(
                    when (g) {
                        Gesture.OPEN_PALM -> HandCommand.PAUSE_PLAY
                        Gesture.THUMBS_UP -> HandCommand.LISTEN
                        else -> HandCommand.QUIET
                    },
                )
            }
        }
        return Result(out, events)
    }

    /** How far a held shape has come (0…1), for a progress ring; null when nothing is being held. */
    fun holdProgress(now: Long): Float? {
        val need = when (held) {
            Gesture.OPEN_PALM -> holdPalmMs
            Gesture.THUMBS_UP -> holdThumbMs
            Gesture.FIST -> holdFistMs
            else -> return null
        }
        if (heldFired) return null
        return (abs(now - heldSince).toFloat() / need).coerceIn(0f, 1f)
    }
}

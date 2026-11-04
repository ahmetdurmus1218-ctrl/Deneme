package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.vision.DemoHands
import io.github.ahmetdurmus1218.jarvis.core.vision.Hand
import io.github.ahmetdurmus1218.jarvis.core.vision.HandCommand
import io.github.ahmetdurmus1218.jarvis.core.vision.HandNavigator
import io.github.ahmetdurmus1218.jarvis.core.vision.HandShape
import io.github.ahmetdurmus1218.jarvis.core.vision.Point
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandNavigatorTest {
    private fun hand(shape: HandShape, cx: Float, cy: Float, turn: Float = 0f): Hand {
        val n = DemoHands.hand(shape, cx, cy, 1.4f)
        val w0 = n.points[0]
        return Hand(n.points.map { p ->
            val dx = p.x - w0.x
            val dy = p.y - w0.y
            Point((w0.x + dx * cos(turn) - dy * sin(turn)) * 1000f, (w0.y + dx * sin(turn) + dy * cos(turn)) * 1000f)
        })
    }

    private fun run(ms: Long, f: (Long) -> Hand?): List<HandCommand> {
        val nav = HandNavigator()
        val out = mutableListOf<HandCommand>()
        var t = 0L
        while (t < ms) { out += nav.update(t, f(t), 1000f, 1000f).commands; t += 33 }
        return out
    }

    @Test
    fun swipesTurnPagesAndSongs() {
        assertEquals(listOf(HandCommand.NEXT), run(500) { t -> hand(HandShape.OPEN, 0.7f - 0.4f * (t / 300f).coerceAtMost(1f), 0.8f) })
        assertEquals(listOf(HandCommand.PREVIOUS), run(500) { t -> hand(HandShape.OPEN, 0.3f + 0.4f * (t / 300f).coerceAtMost(1f), 0.8f) })
        assertEquals(listOf(HandCommand.SCROLL_DOWN), run(500) { t -> hand(HandShape.OPEN, 0.5f, 0.95f - 0.4f * (t / 300f).coerceAtMost(1f)) })
    }

    @Test
    fun heldShapesPauseListenAndQuiet() {
        assertEquals(listOf(HandCommand.PAUSE_PLAY), run(2_000) { hand(HandShape.OPEN, 0.5f, 0.8f) })
        assertEquals(listOf(HandCommand.LISTEN), run(2_000) { hand(HandShape.THUMB, 0.5f, 0.8f) })
        assertEquals(listOf(HandCommand.QUIET), run(2_000) { hand(HandShape.FIST, 0.5f, 0.8f) })
        // A hand that only passes by does nothing.
        assertTrue(run(2_000) { t -> hand(HandShape.OPEN, 0.2f + 0.6f * t / 2_000f, 0.8f) }.isEmpty())
    }

    @Test
    fun pinchAndTurnChangesTheVolume() {
        val up = run(1_000) { t -> hand(HandShape.PINCH, 0.5f, 0.8f, turn = 1.0f * t / 1_000f) }
        assertTrue(up.count { it == HandCommand.VOLUME_UP } >= 2, up.toString())
        val down = run(1_000) { t -> hand(HandShape.PINCH, 0.5f, 0.8f, turn = -1.0f * t / 1_000f) }
        assertTrue(down.count { it == HandCommand.VOLUME_DOWN } >= 2, down.toString())
    }
}

class HandNavigatorEscapeTest {
    private fun hand(shape: HandShape, cx: Float, cy: Float): Hand =
        Hand(DemoHands.hand(shape, cx, cy, 1.4f).points.map { Point(it.x * 1000f, it.y * 1000f) })

    private fun run(ms: Long, second: (Long) -> Hand? = { null }, f: (Long) -> Hand?): List<HandCommand> {
        val nav = HandNavigator()
        val out = mutableListOf<HandCommand>()
        var t = 0L
        while (t < ms) { out += nav.update(t, f(t), 1000f, 1000f, second = second(t)).commands; t += 33 }
        return out
    }

    @Test
    fun victoryGoesBackAWaveGetsOutBothHandsGoHome() {
        assertEquals(listOf(HandCommand.BACK), run(1_800) { hand(HandShape.VICTORY, 0.5f, 0.8f) })
        // A wave: about ±8 % of the width, three times a second.
        val waved = run(1_500) { t -> hand(HandShape.OPEN, 0.5f + 0.08f * kotlin.math.sin(t / 1_000f * 2f * kotlin.math.PI.toFloat() * 3f), 0.8f) }
        assertTrue(HandCommand.EXIT in waved, waved.toString())
        assertTrue(HandCommand.PAUSE_PLAY !in waved, waved.toString())
        assertEquals(listOf(HandCommand.HOME), run(1_800, second = { hand(HandShape.OPEN, 0.25f, 0.8f) }) { hand(HandShape.OPEN, 0.75f, 0.8f) })
    }
}

class NavigationWordsTest {
    @Test
    fun backHomeAndPageAreUnderstood() {
        val r = io.github.ahmetdurmus1218.jarvis.core.router.QuickCommandRouter
        listOf("Geri", "geri dön", "Kapat", "çık", "Rick, bunu kapat").forEach { assertEquals<Any?>(io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.Back, r.route(it), it) }
        listOf("Ana ekran", "ana ekrana dön", "sohbete dön").forEach { assertEquals<Any?>(io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.MainScreen, r.route(it), it) }
        assertEquals<Any?>(io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.Page(1), r.route("aşağı kaydır"))
        assertEquals<Any?>(io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.Page(-1), r.route("yukarı kaydır"))
        // Not navigation: other commands keep their meaning.
        assertEquals<Any?>(io.github.ahmetdurmus1218.jarvis.core.router.QuickCommand.Device(io.github.ahmetdurmus1218.jarvis.core.device.DeviceAction.Torch(false)), r.route("feneri kapat"))
    }
}

package io.github.ahmetdurmus1218.jarvis.core

import io.github.ahmetdurmus1218.jarvis.core.vision.EffectSettings
import io.github.ahmetdurmus1218.jarvis.core.vision.Gesture
import io.github.ahmetdurmus1218.jarvis.core.vision.GestureClassifier
import io.github.ahmetdurmus1218.jarvis.core.vision.Hand
import io.github.ahmetdurmus1218.jarvis.core.vision.HandEffects
import io.github.ahmetdurmus1218.jarvis.core.vision.ParticleKind
import io.github.ahmetdurmus1218.jarvis.core.vision.PlanetKind
import io.github.ahmetdurmus1218.jarvis.core.vision.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandEffectsTest {
    enum class Shape { OPEN, FIST, PINCH, POINT, THUMB }

    /** A right hand, wrist at the bottom, in 0..1 coordinates, shifted by (dx, dy). */
    private fun hand(shape: Shape, dx: Float = 0f, dy: Float = 0f): Hand {
        val pts = MutableList(21) { Point(0.5f, 0.8f) }
        pts[1] = Point(0.45f, 0.72f)
        when (shape) {
            Shape.OPEN -> { pts[2] = Point(0.40f, 0.68f); pts[3] = Point(0.35f, 0.65f); pts[4] = Point(0.28f, 0.58f) }
            Shape.THUMB -> { pts[1] = Point(0.45f, 0.70f); pts[2] = Point(0.43f, 0.60f); pts[3] = Point(0.42f, 0.52f); pts[4] = Point(0.42f, 0.45f) }
            Shape.PINCH -> { pts[2] = Point(0.40f, 0.66f); pts[3] = Point(0.35f, 0.60f); pts[4] = Point(0.32f, 0.54f) }
            else -> { pts[2] = Point(0.43f, 0.68f); pts[3] = Point(0.44f, 0.66f); pts[4] = Point(0.46f, 0.64f) }
        }
        val mcp = listOf(Point(0.44f, 0.60f), Point(0.50f, 0.58f), Point(0.55f, 0.60f), Point(0.60f, 0.62f))
        val extended = when (shape) {
            Shape.OPEN -> listOf(true, true, true, true)
            Shape.POINT -> listOf(true, false, false, false)
            else -> listOf(false, false, false, false)
        }
        for (i in 0 until 4) {
            val b = 5 + 4 * i
            val m = mcp[i]
            pts[b] = m
            if (extended[i]) {
                pts[b + 1] = Point(m.x, m.y - 0.07f); pts[b + 2] = Point(m.x, m.y - 0.12f); pts[b + 3] = Point(m.x, m.y - 0.17f)
            } else {
                pts[b + 1] = Point(m.x, m.y - 0.05f); pts[b + 2] = Point(m.x, m.y - 0.02f); pts[b + 3] = Point(m.x, m.y + 0.03f)
            }
        }
        if (shape == Shape.PINCH) {
            // The index finger bends towards the thumb and their tips touch.
            pts[6] = Point(0.40f, 0.52f); pts[7] = Point(0.36f, 0.50f); pts[8] = Point(0.33f, 0.52f)
        }
        return Hand(pts.map { Point(it.x + dx, it.y + dy) })
    }

    private val W = 640
    private val H = 480

    @Test
    fun pinchIsItsOwnGesture() {
        assertEquals(Gesture.PINCH, GestureClassifier.classify(hand(Shape.PINCH)))
        assertEquals(Gesture.FIST, GestureClassifier.classify(hand(Shape.FIST)))
        assertEquals(Gesture.OPEN_PALM, GestureClassifier.classify(hand(Shape.OPEN)))
        assertEquals(Gesture.POINTING, GestureClassifier.classify(hand(Shape.POINT)))
    }

    @Test
    fun openPalmBurnsUnlessSwitchedOff() {
        val fx = HandEffects()
        for (i in 0..10) fx.update(i * 33L, listOf(hand(Shape.OPEN)), W, H)
        assertTrue(fx.particles.count { it.kind == ParticleKind.FIRE } > 30)
        // Fire rises.
        assertTrue(fx.particles.filter { it.kind == ParticleKind.FIRE }.all { it.vy < 0f })

        val off = HandEffects(settings = EffectSettings(fire = false))
        for (i in 0..10) off.update(i * 33L, listOf(hand(Shape.OPEN)), W, H)
        assertTrue(off.particles.none { it.kind == ParticleKind.FIRE })
    }

    @Test
    fun twoOpenHandsOpenSealRings() {
        val fx = HandEffects()
        fx.update(0, listOf(hand(Shape.OPEN, dx = -0.25f), hand(Shape.OPEN, dx = 0.25f)), W, H)
        assertEquals(2, fx.seals.size)
        fx.update(33, listOf(hand(Shape.OPEN)), W, H)
        assertEquals(0, fx.seals.size, "one hand: no seal")
    }

    @Test
    fun pinchMakesAPlanetThatFollowsTheHand() {
        val fx = HandEffects()
        fx.update(0, listOf(hand(Shape.PINCH)), W, H)
        assertEquals(1, fx.planets.size)
        assertEquals(PlanetKind.SATURN, fx.planets[0].kind, "planets come in order, Saturn first")
        val x0 = fx.planets[0].x
        for (i in 1..10) fx.update(i * 33L, listOf(hand(Shape.PINCH, dx = i * 0.01f)), W, H)
        assertEquals(x0 + 0.1f * W, fx.planets[0].x, 0.5f)
        for (i in 11..14) fx.update(i * 33L, listOf(hand(Shape.PINCH, dx = 0.1f)), W, H)
        // Gentle release: it floats where it was let go.
        fx.update(15 * 33L, listOf(hand(Shape.OPEN, dx = 0.1f)), W, H)
        assertNull(fx.planets[0].heldBy)
        repeat(60) { fx.update(100L + it * 33, emptyList(), W, H) }
        assertEquals(1, fx.planets.size, "a planet let go gently stays")
        // The next pinch away from it brings the next planet.
        fx.update(3_000, listOf(hand(Shape.PINCH, dx = -0.3f)), W, H)
        assertEquals(PlanetKind.EARTH, fx.planets.last().kind)
    }

    @Test
    fun aFlungPlanetFliesAway() {
        val fx = HandEffects()
        var t = 0L
        for (i in 0..5) { fx.update(t, listOf(hand(Shape.PINCH, dx = -0.3f + i * 0.08f)), W, H); t += 33 }
        fx.update(t, listOf(hand(Shape.OPEN, dx = 0.2f)), W, H)
        assertTrue(fx.planets.single().flying, "fast hand → flung")
        repeat(90) { t += 33; fx.update(t, emptyList(), W, H) }
        assertTrue(fx.planets.isEmpty(), "it left the picture")
    }

    @Test
    fun fistThenOpenBurstsThePlanetOrSendsAWave() {
        val fx = HandEffects()
        fx.update(0, listOf(hand(Shape.PINCH)), W, H)
        fx.update(33, listOf(hand(Shape.OPEN, dx = 0.3f)), W, H) // let go (no burst: no fist before)
        assertEquals(1, fx.planets.size)
        fx.update(66, listOf(hand(Shape.FIST)), W, H)
        fx.update(200, listOf(hand(Shape.OPEN)), W, H)
        assertTrue(fx.planets.isEmpty(), "the planet burst")
        assertTrue(fx.particles.count { it.kind == ParticleKind.DEBRIS } > 100)
        assertTrue(fx.shake > 0.5f, "the screen shakes")

        val empty = HandEffects()
        empty.update(0, listOf(hand(Shape.FIST)), W, H)
        empty.update(1_000, listOf(hand(Shape.OPEN)), W, H)
        assertTrue(empty.waves.isEmpty(), "too slow: no burst")
        empty.update(1_100, listOf(hand(Shape.FIST)), W, H)
        empty.update(1_300, listOf(hand(Shape.OPEN)), W, H)
        assertEquals(1, empty.waves.size, "no planet: an energy wave")
    }

    @Test
    fun lightningBetweenTwoIndexFingersTrailWithOne() {
        val fx = HandEffects()
        fx.update(0, listOf(hand(Shape.POINT, dx = -0.25f), hand(Shape.POINT, dx = 0.25f)), W, H)
        assertTrue(fx.bolts.size >= 2)
        val b = fx.bolts.first().points
        assertEquals((0.44f - 0.25f) * W, b.first().x, 0.5f)
        assertEquals((0.44f + 0.25f) * W, b.last().x, 0.5f)

        val one = HandEffects()
        for (i in 0..5) one.update(i * 33L, listOf(hand(Shape.POINT, dx = i * 0.02f)), W, H)
        assertEquals(6, one.trail.size)
        one.update(2_000, emptyList(), W, H)
        assertTrue(one.trail.isEmpty(), "the trail fades")
    }

    @Test
    fun holdingThumbsUpFiresOnceWithProgress() {
        val fx = HandEffects()
        var fired = 0
        var t = 0L
        while (t <= 3_000) {
            if (fx.update(t, listOf(hand(Shape.THUMB)), W, H) == Gesture.THUMBS_UP) fired++
            if (t == 500L) assertEquals(0.5f, assertNotNull(fx.holdProgress).progress, 0.05f)
            t += 50
        }
        assertEquals(1, fired)
        val off = HandEffects(settings = EffectSettings(controls = false))
        assertNull((0..40).map { off.update(it * 50L, listOf(hand(Shape.THUMB)), W, H) }.firstOrNull { it != null })
    }

    @Test
    fun voiceChangesThePlanetAndTwoHandsStretchIt() {
        val fx = HandEffects()
        fx.requestPlanet(PlanetKind.MARS)
        fx.update(0, listOf(hand(Shape.PINCH)), W, H)
        assertEquals(PlanetKind.MARS, fx.planets.single().kind, "asked for before the pinch")
        fx.requestPlanet(PlanetKind.JUPITER)
        assertEquals(PlanetKind.JUPITER, fx.planets.single().kind)
        assertEquals(0f, fx.planets.single().form, "re-forms")
        assertTrue(fx.particles.any { it.kind == ParticleKind.PLANET_DUST })

        val small = fx.planets.single().radius
        fx.update(33, listOf(hand(Shape.PINCH), hand(Shape.PINCH, dx = 0.45f)), W, H)
        assertEquals(1, fx.planets.size)
        assertTrue(fx.planets.single().radius > small * 1.5f, "pulled apart, it grows")

        assertEquals(PlanetKind.MARS, PlanetKind.fromSpeech("jarvis mars yap"))
        assertEquals(PlanetKind.JUPITER, PlanetKind.fromSpeech("jupitere cevir"))
        assertEquals(PlanetKind.MOON, PlanetKind.fromSpeech("ay yap"))
        assertEquals(null, PlanetKind.fromSpeech("ayakkabi"))
    }
}

class DemoHandsTest {
    @Test
    fun theDemoLoopShowsEveryEffect() {
        val fx = HandEffects()
        val seen = mutableSetOf<String>()
        var t = 0L
        while (t < io.github.ahmetdurmus1218.jarvis.core.vision.DemoHands.loopMs) {
            val (_, hands) = io.github.ahmetdurmus1218.jarvis.core.vision.DemoHands.at(t)
            val fired = fx.update(t, hands, 1280, 720)
            if (fx.particles.any { it.kind == ParticleKind.FIRE }) seen += "fire"
            if (fx.seals.size >= 2) seen += "seals"
            if (fx.planets.isNotEmpty()) seen += "planet"
            if (fx.particles.any { it.kind == ParticleKind.DEBRIS }) seen += "burst"
            if (fx.bolts.size >= 2) seen += "lightning"
            if (fx.trail.size > 5) seen += "trail"
            if (fired == Gesture.THUMBS_UP) seen += "hold"
            t += 33
        }
        assertEquals(setOf("fire", "seals", "planet", "burst", "lightning", "trail", "hold"), seen)
    }
}

class FaceLockTest {
    private val big = io.github.ahmetdurmus1218.jarvis.core.vision.FaceBox(0.3f, 0.2f, 0.7f, 0.7f, 0.93f)
    private val small = io.github.ahmetdurmus1218.jarvis.core.vision.FaceBox(0.8f, 0.1f, 0.9f, 0.25f, 0.8f)

    @Test
    fun theClosestFaceLocksOthersStayUnknown() {
        val lock = io.github.ahmetdurmus1218.jarvis.core.vision.FaceLock()
        val (l0, g0) = lock.update(0, listOf(small, big), "ahmet", busy = false)
        assertEquals("KİLİTLENİYOR", l0.first { it.primary }.text)
        assertEquals("KİŞİ · TANIMLANMADI", l0.first { !it.primary }.text)
        assertTrue(!g0)
        var greets = 0
        var t = 0L
        while (t <= 2_000) { if (lock.update(t, listOf(small, big), "ahmet", false).second) greets++; t += 100 }
        val (l1, _) = lock.update(2_100, listOf(small, big), "ahmet", false)
        assertEquals("AHMET · TANINDI · YETKİ: TAM", l1.first { it.primary }.text)
        assertTrue(l1.first { it.primary }.face === big)
        assertEquals(1, greets, "greets once when the lock closes")
        // Gone and back within a minute: locks again, but no second greeting.
        lock.update(5_000, emptyList(), "ahmet", false)
        t = 5_100
        while (t <= 8_000) { if (lock.update(t, listOf(big), "ahmet", false).second) greets++; t += 100 }
        assertEquals(1, greets)
        // After a minute it may greet again, but never while Jarvis is busy.
        lock.update(70_000, emptyList(), "", false)
        t = 70_100
        while (t <= 72_000) { if (lock.update(t, listOf(big), "", busy = true).second) greets++; t += 100 }
        assertEquals(1, greets)
        assertEquals("KULLANICI · TANINDI · YETKİ: TAM", lock.update(72_100, listOf(big), "", false).first.single().text)
    }
}

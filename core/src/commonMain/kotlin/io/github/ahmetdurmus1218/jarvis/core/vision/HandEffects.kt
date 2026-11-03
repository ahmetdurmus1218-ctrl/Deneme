package io.github.ahmetdurmus1218.jarvis.core.vision

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.serialization.Serializable

/** Colours as 0xAARRGGBB, so the core stays free of UI types. */
enum class PlanetKind(val label: String, val body: List<Long>, val ring: Boolean = false, val glow: Long = 0) {
    SATURN("Satürn", listOf(0xFFE8D29BL, 0xFFC9A66BL, 0xFFF3E4BCL), ring = true),
    EARTH("Dünya", listOf(0xFF2E7BD6L, 0xFF3FA34DL, 0xFFEFF6FFL)),
    MARS("Mars", listOf(0xFFC1440EL, 0xFFE27B58L, 0xFF8A2F0BL)),
    JUPITER("Jüpiter", listOf(0xFFD8B48AL, 0xFFB07F4FL, 0xFFF0DEC4L, 0xFFC1440EL)),
    MOON("Ay", listOf(0xFFD9D9D9L, 0xFFA8A8A8L, 0xFF7A7A7AL)),
    SUN("Güneş", listOf(0xFFFFF3A0L, 0xFFFFC233L, 0xFFFF7A00L), glow = 0xFFFFB000L),
    ;

    companion object {
        /** "Mars yap", "Jüpiter'e çevir", "satürn" → the planet, from normalized text. */
        fun fromSpeech(normalized: String): PlanetKind? = when {
            "saturn" in normalized -> SATURN
            "dunya" in normalized -> EARTH
            "mars" in normalized -> MARS
            "jupiter" in normalized -> JUPITER
            Regex("\\bay(i|a|in)?\\b").containsMatchIn(normalized) -> MOON
            "gunes" in normalized -> SUN
            else -> null
        }
    }
}

enum class ParticleKind { FIRE, SPARK, DEBRIS, PLANET_DUST, WAVE_DUST }

class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var life: Float, val maxLife: Float, val size: Float, val color: Long, val kind: ParticleKind,
)

class Planet(var kind: PlanetKind, var x: Float, var y: Float, var radius: Float) {
    var vx = 0f
    var vy = 0f
    var tilt = 0f
    var spin = 0f
    var heldBy: Int? = null
    var age = 0f
    /** 0..1 while forming (also after a kind change: the planet scatters and re-forms). */
    var form = 0f
    val flying get() = heldBy == null && hypot(vx, vy) > FLING_SPEED_PX
    companion object { const val FLING_SPEED_PX = 900f }
}

data class Pt(val x: Float, val y: Float)
data class Seal(val x: Float, val y: Float, val radius: Float, val angle: Float, val squash: Float)
data class Bolt(val points: List<Pt>, val width: Float, val color: Long)
data class Wave(val x: Float, val y: Float, var radius: Float, var alpha: Float, val color: Long)
data class TrailPoint(val x: Float, val y: Float, val t: Long)
data class HoldProgress(val gesture: Gesture, val progress: Float, val x: Float, val y: Float)

/** Which effects are on (each can be switched off; an off effect never triggers). */
@Serializable
data class EffectSettings(
    val fire: Boolean = true,
    val seals: Boolean = true,
    val planets: Boolean = true,
    val burst: Boolean = true,
    val lightning: Boolean = true,
    val trail: Boolean = true,
    val controls: Boolean = true,
    val faceLock: Boolean = true,
    /** null: every pinch brings the next planet; otherwise always this one. */
    val fixedPlanet: PlanetKind? = null,
)

/**
 * Hold-to-act controls with a progress ring: 👍 1 s, ✌️ 0.9 s, ✊ 1.8 s. Fires once per hold;
 * the gesture has to change before it can fire again.
 */
class HoldTrigger(private val durations: Map<Gesture, Long> = DEFAULT) {
    private var current = Gesture.NONE
    private var since = 0L
    private var done = false

    fun progress(now: Long): Float {
        val d = durations[current] ?: return 0f
        return if (done) 1f else ((now - since).toFloat() / d).coerceIn(0f, 1f)
    }

    fun current(): Gesture = current

    /** Feeds the primary hand's gesture; returns the gesture to act on now, or null. */
    fun feed(g: Gesture, now: Long): Gesture? {
        if (g != current) {
            current = g
            since = now
            done = false
        }
        val d = durations[g] ?: return null
        if (!done && now - since >= d) {
            done = true
            return g
        }
        return null
    }

    companion object {
        val DEFAULT = mapOf(Gesture.THUMBS_UP to 1_000L, Gesture.VICTORY to 900L, Gesture.FIST to 1_800L)
    }
}

/**
 * The hand-effects engine (fire, seal rings, pinch planets, burst, lightning, light trail), all in
 * the camera image's pixel space. Pure and deterministic for a given seed, so it is unit tested
 * and drawn the same way on the phone and the PC. Call [update] once per display frame with the
 * latest tracked hands.
 */
class HandEffects(seed: Int = 7, var settings: EffectSettings = EffectSettings()) {
    private val rnd = Random(seed)
    val particles = ArrayList<Particle>()
    val planets = ArrayList<Planet>()
    val waves = ArrayList<Wave>()
    var seals: List<Seal> = emptyList(); private set
    var bolts: List<Bolt> = emptyList(); private set
    val trail = ArrayList<TrailPoint>()
    var shake = 0f; private set
    var width = 1f; private set
    var height = 1f; private set
    private var last = -1L
    private var nextPlanet = 0
    private var requested: PlanetKind? = null
    val hold = HoldTrigger()
    /** The hands of the latest update (0..1 coordinates), for drawing the skeleton. */
    var lastHands: List<Hand> = emptyList(); private set
    var holdProgress: HoldProgress? = null; private set

    /** Hands tracked across frames (matched by palm position) for fist→open and release velocity. */
    private var nextTrackId = 0
    private class Track(val id: Int, var cx: Float, var cy: Float) {
        var gesture = Gesture.NONE
        var fistAt = Long.MIN_VALUE / 2
        var pinchX = 0f
        var pinchY = 0f
        var pinchVx = 0f
        var pinchVy = 0f
        var pinching = false
        var seen = true
    }
    private val tracks = ArrayList<Track>()

    /** "Jarvis, Mars yap": the planet in hand (or the newest one) scatters and re-forms; else the next pinch brings it. */
    fun requestPlanet(kind: PlanetKind) {
        val p = planets.firstOrNull { it.heldBy != null } ?: planets.lastOrNull()
        if (p == null) {
            requested = kind
        } else {
            burstPlanet(p, keep = true)
            p.kind = kind
            p.form = 0f
        }
    }

    private fun nextKind(): PlanetKind {
        requested?.let { requested = null; return it }
        settings.fixedPlanet?.let { return it }
        val k = PlanetKind.entries[nextPlanet % PlanetKind.entries.size]
        nextPlanet++
        return k
    }

    private fun d(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

    /** Returns a gesture control that fired this frame (👍 ✌️ ✊ held long enough), or null. */
    fun update(now: Long, hands: List<Hand>, imageWidth: Int, imageHeight: Int): Gesture? {
        lastHands = hands
        width = imageWidth.toFloat().coerceAtLeast(1f)
        height = imageHeight.toFloat().coerceAtLeast(1f)
        val dt = if (last < 0) 0f else ((now - last).coerceIn(0, 60)) / 1000f
        last = now

        // Hands in pixels, matched to the tracks of the previous frame.
        val px = hands.map { h -> h.points.map { Pt(it.x * width, it.y * height) } }
        val gestures = hands.map(GestureClassifier::classify)
        tracks.forEach { it.seen = false }
        val handTrack = px.map { p ->
            val c = palmCentre(p)
            val t = tracks.filter { !it.seen }.minByOrNull { hypot(it.cx - c.x, it.cy - c.y) }
                ?.takeIf { hypot(it.cx - c.x, it.cy - c.y) < palmSize(p) * 3f }
                ?: Track(nextTrackId++, c.x, c.y).also { tracks += it }
            t.seen = true
            t.cx = c.x
            t.cy = c.y
            t
        }
        // A hand that left the picture lets go of its planet (it floats on).
        tracks.retainAll { it.seen }
        val live = tracks.map { it.id }.toSet()
        planets.forEach { if (it.heldBy != null && it.heldBy !in live) it.heldBy = null }

        val bolts = ArrayList<Bolt>()
        val seals = ArrayList<Seal>()
        val openPalms = gestures.count { it == Gesture.OPEN_PALM }

        px.forEachIndexed { i, p ->
            val g = gestures[i]
            val t = handTrack[i]
            val ti = t.id
            val palm = palmSize(p)
            val c = palmCentre(p)

            // ✋ fire: palm and fingertips burn, sparks jump between neighbouring fingertips.
            if (g == Gesture.OPEN_PALM && settings.fire && !nearShelfFree(c)) {
                for (tip in TIPS + listOf(-1)) {
                    val src = if (tip < 0) c else p[tip]
                    val n = if (tip < 0) 3 else 2
                    repeat(n) { spawnFire(src, palm) }
                }
                if (rnd.nextFloat() < 0.35f) {
                    val k = rnd.nextInt(TIPS.size - 1)
                    bolts += jagged(p[TIPS[k]], p[TIPS[k + 1]], 6, 0.18f, palm * 0.02f, 0xFFFFD27AL)
                }
            }
            // ✋✋ seal rings in front of both palms; turning the hand sideways narrows the ring.
            if (g == Gesture.OPEN_PALM && openPalms >= 2 && settings.seals) {
                val across = d(p[5], p[17])
                val along = d(p[0], p[9]).coerceAtLeast(1f)
                val squash = (across / along / 0.75f).coerceIn(0.2f, 1f)
                seals += Seal(c.x, c.y, palm * 1.4f, (now % 6_000L) / 6_000f * 360f * (if (i % 2 == 0) 1 else -1), squash)
            }

            // 🤏 pinch: a planet forms between thumb and index finger, follows the hand, tilts with it.
            val pinchNow = g == Gesture.PINCH && settings.planets
            val pp = Pt((p[4].x + p[8].x) / 2f, (p[4].y + p[8].y) / 2f)
            if (pinchNow) {
                if (dt > 0f && t.pinching) {
                    val vx = (pp.x - t.pinchX) / dt
                    val vy = (pp.y - t.pinchY) / dt
                    t.pinchVx = t.pinchVx * 0.5f + vx * 0.5f
                    t.pinchVy = t.pinchVy * 0.5f + vy * 0.5f
                }
                val held = planets.firstOrNull { it.heldBy == ti }
                if (held == null && !t.pinching) {
                    val free = planets.filter { it.heldBy == null }.minByOrNull { hypot(it.x - pp.x, it.y - pp.y) }
                        ?.takeIf { hypot(it.x - pp.x, it.y - pp.y) < it.radius * 1.6f }
                    if (free != null) {
                        free.heldBy = ti
                    } else {
                        if (planets.size >= MAX_PLANETS) planets.removeAt(0)
                        planets += Planet(nextKind(), pp.x, pp.y, palm * 0.55f).also { it.heldBy = ti }
                    }
                }
                planets.firstOrNull { it.heldBy == ti }?.let { pl ->
                    pl.x = pp.x
                    pl.y = pp.y
                    pl.vx = 0f
                    pl.vy = 0f
                    pl.tilt = atan2(p[17].y - p[5].y, p[17].x - p[5].x) * 180f / PI.toFloat()
                }
            } else if (t.pinching) {
                // Released: the planet floats on, or flies if it was flung.
                planets.firstOrNull { it.heldBy == ti }?.let { pl ->
                    pl.heldBy = null
                    pl.vx = t.pinchVx
                    pl.vy = t.pinchVy
                }
                t.pinchVx = 0f
                t.pinchVy = 0f
            }
            t.pinching = pinchNow
            t.pinchX = pp.x
            t.pinchY = pp.y

            // ✊ → ✋ quickly: the nearest planet bursts in its colours, or an energy wave leaves the palm.
            if (g == Gesture.FIST) t.fistAt = now
            if (g == Gesture.OPEN_PALM && t.gesture != Gesture.OPEN_PALM && now - t.fistAt <= BURST_WINDOW_MS) {
                if (settings.burst) {
                    val target = planets.filter { it.heldBy == null || it.heldBy == ti }.minByOrNull { hypot(it.x - c.x, it.y - c.y) }
                    if (target != null) {
                        burstPlanet(target, keep = false)
                        planets.remove(target)
                    } else {
                        waves += Wave(c.x, c.y, palm * 0.5f, 1f, 0xFF7FE9FFL)
                        repeat(60) {
                            val a = rnd.nextFloat() * 2f * PI.toFloat()
                            val s = palm * (4f + rnd.nextFloat() * 4f)
                            particles += Particle(c.x, c.y, cos(a) * s, sin(a) * s, 0.7f, 0.7f, palm * 0.05f, 0xFF7FE9FFL, ParticleKind.WAVE_DUST)
                        }
                    }
                    shake = 1f
                }
                t.fistAt = Long.MIN_VALUE / 2
            }
            t.gesture = g
        }

        // Two-hand pinch: the planet held by one hand is stretched between both pinches.
        val pinching = px.indices.filter { gestures[it] == Gesture.PINCH }
        if (pinching.size >= 2 && settings.planets) {
            val a = px[pinching[0]]
            val b = px[pinching[1]]
            val pa = Pt((a[4].x + a[8].x) / 2f, (a[4].y + a[8].y) / 2f)
            val pb = Pt((b[4].x + b[8].x) / 2f, (b[4].y + b[8].y) / 2f)
            val ia = handTrack[pinching[0]].id
            val ib = handTrack[pinching[1]].id
            val held = planets.firstOrNull { it.heldBy == ia } ?: planets.firstOrNull { it.heldBy == ib }
            if (held != null) {
                planets.removeAll { it !== held && (it.heldBy == ia || it.heldBy == ib) }
                held.x = (pa.x + pb.x) / 2f
                held.y = (pa.y + pb.y) / 2f
                held.radius = (d(pa, pb) * 0.42f).coerceAtLeast(palmSize(a) * 0.3f)
            }
        }

        // ☝️☝️ lightning between two index fingertips; ☝️ alone leaves a light trail.
        val pointing = px.indices.filter { gestures[it] == Gesture.POINTING }
        if (pointing.size >= 2 && settings.lightning) {
            val a = px[pointing[0]][8]
            val b = px[pointing[1]][8]
            val w = palmSize(px[pointing[0]]) * 0.05f
            bolts += jagged(a, b, 14, 0.12f, w, 0xFFBFE8FFL)
            bolts += jagged(a, b, 10, 0.2f, w * 0.5f, 0xFF8FD3FFL)
            repeat(2) { spawnSpark(a, w * 20f); spawnSpark(b, w * 20f) }
        } else if (pointing.size == 1 && settings.trail) {
            val tip = px[pointing[0]][8]
            trail += TrailPoint(tip.x, tip.y, now)
        }
        trail.removeAll { now - it.t > TRAIL_MS }

        // Physics
        val damp = exp(-dt * 1.2f)
        planets.forEach { p ->
            p.age += dt
            p.spin += dt * 30f
            p.form = min(1f, p.form + dt / 0.45f)
            if (p.heldBy == null) {
                p.x += p.vx * dt
                p.y += p.vy * dt
                if (!p.flying) {
                    p.vx *= damp
                    p.vy *= damp
                    p.y += sin(p.age * 2f) * 0.15f // a gentle float
                }
            }
        }
        planets.removeAll { it.heldBy == null && (it.x < -it.radius * 3 || it.x > width + it.radius * 3 || it.y < -it.radius * 3 || it.y > height + it.radius * 3 || it.age > PLANET_LIFE_S) }
        particles.forEach { q ->
            q.x += q.vx * dt
            q.y += q.vy * dt
            when (q.kind) {
                ParticleKind.FIRE -> { q.vy -= 260f * dt; q.vx *= exp(-dt * 2f) }
                ParticleKind.DEBRIS, ParticleKind.PLANET_DUST -> { q.vx *= exp(-dt * 1.5f); q.vy = q.vy * exp(-dt * 1.5f) + 120f * dt }
                else -> { q.vx *= exp(-dt * 3f); q.vy *= exp(-dt * 3f) }
            }
            q.life -= dt
        }
        particles.removeAll { it.life <= 0f }
        if (particles.size > MAX_PARTICLES) particles.subList(0, particles.size - MAX_PARTICLES).clear()
        waves.forEach { it.radius += dt * max(width, height) * 0.9f; it.alpha -= dt * 1.6f }
        waves.removeAll { it.alpha <= 0f }
        shake *= exp(-dt * 6f)
        if (shake < 0.01f) shake = 0f
        this.seals = seals
        this.bolts = bolts

        // Hold-to-act controls on the first hand.
        val primary = gestures.firstOrNull() ?: Gesture.NONE
        val fired = if (settings.controls) hold.feed(primary, now) else null
        holdProgress = px.firstOrNull()?.let { p ->
            val pr = hold.progress(now)
            if (settings.controls && primary in HoldTrigger.DEFAULT && pr > 0f) palmCentre(p).let { HoldProgress(primary, pr, it.x, it.y) } else null
        }
        return fired
    }

    /** Placeholder for a "no fire near the bookshelf" zone (the library feature); none by default. */
    var noFireZone: ((Pt) -> Boolean)? = null
    private fun nearShelfFree(c: Pt) = noFireZone?.invoke(c) == true

    private fun spawnFire(src: Pt, palm: Float) {
        val life = 0.35f + rnd.nextFloat() * 0.45f
        val color = FIRE_COLORS[rnd.nextInt(FIRE_COLORS.size)]
        particles += Particle(
            src.x + (rnd.nextFloat() - 0.5f) * palm * 0.15f, src.y,
            (rnd.nextFloat() - 0.5f) * palm * 0.8f, -palm * (0.8f + rnd.nextFloat() * 1.2f),
            life, life, palm * (0.06f + rnd.nextFloat() * 0.07f), color, ParticleKind.FIRE,
        )
    }

    private fun spawnSpark(src: Pt, speed: Float) {
        val a = rnd.nextFloat() * 2f * PI.toFloat()
        particles += Particle(src.x, src.y, cos(a) * speed, sin(a) * speed, 0.25f, 0.25f, speed * 0.004f + 1.5f, 0xFFE6F6FFL, ParticleKind.SPARK)
    }

    private fun burstPlanet(p: Planet, keep: Boolean) {
        val n = if (keep) 70 else 160
        repeat(n) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val s = p.radius * (if (keep) 1.5f else 3f) * (0.5f + rnd.nextFloat() * 1.5f)
            val r = p.radius * sqrt(rnd.nextFloat())
            val color = p.kind.body[rnd.nextInt(p.kind.body.size)]
            particles += Particle(
                p.x + cos(a) * r, p.y + sin(a) * r, cos(a) * s, sin(a) * s,
                if (keep) 0.45f else 1.2f, if (keep) 0.45f else 1.2f, p.radius * (0.05f + rnd.nextFloat() * 0.06f), color,
                if (keep) ParticleKind.PLANET_DUST else ParticleKind.DEBRIS,
            )
        }
        if (!keep) waves += Wave(p.x, p.y, p.radius, 1f, p.kind.body.first())
    }

    /** A jagged bolt from a to b: [segments] pieces, sideways jitter up to [rough] of the length. */
    private fun jagged(a: Pt, b: Pt, segments: Int, rough: Float, width: Float, color: Long): Bolt {
        val len = d(a, b)
        val nx = -(b.y - a.y) / len.coerceAtLeast(1f)
        val ny = (b.x - a.x) / len.coerceAtLeast(1f)
        val pts = (0..segments).map { i ->
            val f = i / segments.toFloat()
            val j = if (i == 0 || i == segments) 0f else (rnd.nextFloat() - 0.5f) * 2f * rough * len * sin(f * PI.toFloat())
            Pt(a.x + (b.x - a.x) * f + nx * j, a.y + (b.y - a.y) * f + ny * j)
        }
        return Bolt(pts, width, color)
    }

    companion object {
        private val TIPS = listOf(4, 8, 12, 16, 20)
        private val FIRE_COLORS = listOf(0xFFFFE08AL, 0xFFFFB347L, 0xFFFF7A1AL, 0xFFFF4D00L, 0xFFFFD000L)
        const val MAX_PLANETS = 3
        const val MAX_PARTICLES = 900
        const val BURST_WINDOW_MS = 450L
        const val TRAIL_MS = 700L
        const val PLANET_LIFE_S = 40f

        fun palmCentre(p: List<Pt>) = Pt((p[0].x + p[5].x + p[9].x + p[13].x + p[17].x) / 5f, (p[0].y + p[5].y + p[9].y + p[13].y + p[17].y) / 5f)
        fun palmSize(p: List<Pt>) = hypot(p[0].x - p[9].x, p[0].y - p[9].y).coerceAtLeast(1f)
    }
}

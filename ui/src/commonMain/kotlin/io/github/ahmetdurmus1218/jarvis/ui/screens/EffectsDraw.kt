package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.vision.FaceLock
import io.github.ahmetdurmus1218.jarvis.core.vision.Gesture
import io.github.ahmetdurmus1218.jarvis.core.vision.HandEffects
import io.github.ahmetdurmus1218.jarvis.core.vision.ParticleKind
import io.github.ahmetdurmus1218.jarvis.core.vision.Planet
import io.github.ahmetdurmus1218.jarvis.core.vision.PlanetKind
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun argb(c: Long, alpha: Float = 1f): Color = Color(c.toInt()).let { it.copy(alpha = it.alpha * alpha.coerceIn(0f, 1f)) }

/**
 * Maps the effects engine's image pixels onto the screen. [crop] = the camera preview's fill-and-crop;
 * otherwise (demo) the whole image fits on the screen.
 */
internal class ImageMapper(w: Float, h: Float, canvas: Size, crop: Boolean) {
    val scale = if (crop) max(canvas.width / w, canvas.height / h) else min(canvas.width / w, canvas.height / h)
    private val ox = (canvas.width - w * scale) / 2f
    private val oy = (canvas.height - h * scale) / 2f
    fun x(v: Float) = ox + v * scale
    fun y(v: Float) = oy + v * scale
    fun o(x: Float, y: Float) = Offset(x(x), y(y))
    fun len(v: Float) = v * scale
}

private val SEAL = Color(0xFFFF9A2E)

internal fun DrawScope.drawEffects(fx: HandEffects, m: ImageMapper, t: Float) {
    // Energy waves
    fx.waves.forEach { w ->
        drawCircle(argb(w.color, w.alpha * 0.8f), radius = m.len(w.radius), center = m.o(w.x, w.y), style = Stroke(width = m.len(w.radius) * 0.06f + 2f))
        drawCircle(argb(w.color, w.alpha * 0.25f), radius = m.len(w.radius) * 0.85f, center = m.o(w.x, w.y), style = Stroke(width = m.len(w.radius) * 0.18f))
    }
    // Seal rings in front of the palms
    fx.seals.forEach { s -> drawSeal(m.o(s.x, s.y), m.len(s.radius), s.angle, s.squash) }
    // Light trail
    if (fx.trail.size >= 2) {
        val newest = fx.trail.last().t
        for (i in 1 until fx.trail.size) {
            val a = fx.trail[i - 1]
            val b = fx.trail[i]
            val age = ((newest - b.t) / HandEffects.TRAIL_MS.toFloat()).coerceIn(0f, 1f)
            val w = (1f - age) * 14f + 2f
            drawLine(JarvisColors.Cyan.copy(alpha = (1f - age) * 0.35f), m.o(a.x, a.y), m.o(b.x, b.y), strokeWidth = w * 2.6f, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
            drawLine(Color.White.copy(alpha = 1f - age), m.o(a.x, a.y), m.o(b.x, b.y), strokeWidth = w * 0.5f, cap = StrokeCap.Round)
        }
    }
    // Planets
    fx.planets.forEach { drawPlanet(it, m, t) }
    // Particles (additive, so fire glows)
    fx.particles.forEach { p ->
        val life = (p.life / p.maxLife).coerceIn(0f, 1f)
        val r = m.len(p.size) * (if (p.kind == ParticleKind.FIRE) 0.6f + life * 0.8f else 1f)
        val c = argb(p.color, if (p.kind == ParticleKind.FIRE) life * 0.9f else life)
        drawCircle(c, radius = r.coerceAtLeast(1f), center = m.o(p.x, p.y), blendMode = BlendMode.Plus)
    }
    // Lightning and sparks between fingertips
    fx.bolts.forEach { b ->
        val path = Path().apply {
            b.points.forEachIndexed { i, p -> if (i == 0) moveTo(m.x(p.x), m.y(p.y)) else lineTo(m.x(p.x), m.y(p.y)) }
        }
        val w = m.len(b.width).coerceAtLeast(1.5f)
        drawPath(path, argb(b.color, 0.35f), style = Stroke(width = w * 5f, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        drawPath(path, Color.White, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    // Hold-to-act ring
    fx.holdProgress?.let { h ->
        val c = when (h.gesture) {
            Gesture.THUMBS_UP -> JarvisColors.Green
            Gesture.VICTORY -> JarvisColors.Violet
            else -> JarvisColors.Red
        }
        val r = 46f
        val centre = m.o(h.x, h.y)
        drawCircle(c.copy(alpha = 0.18f), radius = r, center = centre, style = Stroke(width = 8f))
        drawArc(c, -90f, 360f * h.progress, false, topLeft = Offset(centre.x - r, centre.y - r), size = Size(r * 2, r * 2), style = Stroke(width = 8f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawSeal(c: Offset, r: Float, angle: Float, squash: Float) {
    scale(scaleX = squash, scaleY = 1f, pivot = c) {
        rotate(angle, pivot = c) {
            drawCircle(SEAL.copy(alpha = 0.25f), radius = r * 1.05f, center = c, style = Stroke(width = r * 0.12f), blendMode = BlendMode.Plus)
            drawCircle(SEAL, radius = r, center = c, style = Stroke(width = r * 0.025f))
            drawCircle(SEAL, radius = r * 0.82f, center = c, style = Stroke(width = r * 0.018f))
            drawCircle(SEAL.copy(alpha = 0.8f), radius = r * 0.45f, center = c, style = Stroke(width = r * 0.02f))
            // Runic ticks between the two outer rings
            for (k in 0 until 36) {
                val a = k * 10f / 180f * PI.toFloat()
                val long = k % 3 == 0
                val r1 = r * 0.84f
                val r2 = r * if (long) 0.98f else 0.92f
                drawLine(SEAL, Offset(c.x + r1 * cos(a), c.y + r1 * sin(a)), Offset(c.x + r2 * cos(a), c.y + r2 * sin(a)), strokeWidth = r * 0.015f)
            }
            // Two interlocked squares
            for (q in 0 until 2) {
                val path = Path()
                for (k in 0..4) {
                    val a = (k * 90f + q * 45f) / 180f * PI.toFloat()
                    val p = Offset(c.x + r * 0.8f * cos(a), c.y + r * 0.8f * sin(a))
                    if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, SEAL.copy(alpha = 0.9f), style = Stroke(width = r * 0.018f))
            }
        }
    }
}

private fun DrawScope.drawPlanet(p: Planet, m: ImageMapper, t: Float) {
    val form = p.form.let { 1f - (1f - it) * (1f - it) }
    val r = m.len(p.radius) * (0.2f + 0.8f * form)
    val c = m.o(p.x, p.y)
    val k = p.kind
    if (k.glow != 0L) {
        drawCircle(Brush.radialGradient(listOf(argb(k.glow, 0.55f), Color.Transparent), center = c, radius = r * 2.2f), radius = r * 2.2f, center = c, blendMode = BlendMode.Plus)
    }
    if (k.ring) drawRing(p, c, r, back = true)
    // Body: lit from the top left
    drawCircle(
        Brush.radialGradient(listOf(argb(k.body.last()), argb(k.body.first()), argb(k.body[1]).copy(red = argb(k.body[1]).red * 0.35f, green = argb(k.body[1]).green * 0.35f, blue = argb(k.body[1]).blue * 0.35f)),
            center = Offset(c.x - r * 0.35f, c.y - r * 0.35f), radius = r * 1.6f),
        radius = r, center = c,
    )
    val body = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    clipPath(body) {
        when (k) {
            PlanetKind.JUPITER, PlanetKind.SATURN -> {
                for (i in -4..4) {
                    val y = c.y + i * r * 0.22f + sin(p.spin / 40f + i) * r * 0.03f
                    drawRect(argb(k.body[(i + 8) % k.body.size], 0.45f), topLeft = Offset(c.x - r, y), size = Size(r * 2, r * 0.09f))
                }
                if (k == PlanetKind.JUPITER) drawOval(argb(0xFFC1440EL, 0.8f), topLeft = Offset(c.x + r * 0.15f, c.y + r * 0.2f), size = Size(r * 0.38f, r * 0.2f))
            }
            PlanetKind.EARTH -> {
                val shift = (p.spin * 0.02f) % 2f
                for (i in 0 until 5) {
                    val ax = c.x + ((i * 0.53f + shift) % 2f - 1f) * r
                    val ay = c.y + sin(i * 2.1f) * r * 0.5f
                    drawOval(argb(0xFF3FA34DL, 0.9f), topLeft = Offset(ax - r * 0.25f, ay - r * 0.15f), size = Size(r * 0.5f, r * 0.32f))
                }
                drawCircle(Color.White.copy(alpha = 0.25f), radius = r, center = c, style = Stroke(width = r * 0.08f))
            }
            PlanetKind.MOON, PlanetKind.MARS -> {
                for (i in 0 until 6) {
                    val a = i * 1.7f
                    val cc = Offset(c.x + cos(a) * r * 0.5f * ((i % 3) / 3f + 0.3f), c.y + sin(a) * r * 0.5f)
                    drawCircle(argb(k.body.last(), 0.5f), radius = r * (0.08f + (i % 3) * 0.04f), center = cc)
                }
            }
            PlanetKind.SUN -> {
                for (i in 0 until 10) {
                    val a = i * 0.63f + t
                    drawCircle(argb(0xFFFFF3A0L, 0.35f), radius = r * 0.18f, center = Offset(c.x + cos(a) * r * 0.55f, c.y + sin(a * 1.3f) * r * 0.5f), blendMode = BlendMode.Plus)
                }
            }
        }
        // Night side
        drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), center = Offset(c.x - r * 0.4f, c.y - r * 0.4f), radius = r * 2f), radius = r, center = c)
    }
    if (k.ring) drawRing(p, c, r, back = false)
    // Atmosphere rim
    drawCircle(argb(k.body.first(), 0.35f), radius = r * 1.04f, center = c, style = Stroke(width = r * 0.06f), blendMode = BlendMode.Plus)
}

/** Saturn's ring, tilted with the hand; the back half is drawn behind the body, the front half over it. */
private fun DrawScope.drawRing(p: Planet, c: Offset, r: Float, back: Boolean) {
    rotate(p.tilt * 0.6f - 15f, pivot = c) {
        val w = r * 2.3f
        val h = r * 0.62f
        val start = if (back) 180f else 0f
        for ((i, f) in listOf(1f, 0.86f, 0.74f).withIndex()) {
            drawArc(
                argb(p.kind.body[i % p.kind.body.size], if (back) 0.55f else 0.9f), start, 180f, false,
                topLeft = Offset(c.x - w * f, c.y - h * f), size = Size(w * 2 * f, h * 2 * f), style = Stroke(width = r * 0.09f),
            )
        }
    }
}

internal fun DrawScope.drawFaceLabel(l: FaceLock.Label, left: Float, top: Float, right: Float, bottom: Float, t: Float, text: TextMeasurer) {
    val c = when {
        !l.primary -> JarvisColors.TextDim
        l.state == FaceLock.State.LOCKED -> JarvisColors.Cyan
        else -> JarvisColors.Amber
    }
    val w = right - left
    val h = bottom - top
    // The brackets close in while locking.
    val pad = if (l.primary && l.state == FaceLock.State.LOCKING) (1f - l.progress) * w * 0.25f else 0f
    val x0 = left - pad
    val x1 = right + pad
    val y0 = top - pad
    val y1 = bottom + pad
    val len = min(w, h) * 0.22f
    val sw = 3f + if (l.primary) 2f else 0f
    drawLine(c, Offset(x0, y0), Offset(x0 + len, y0), sw); drawLine(c, Offset(x0, y0), Offset(x0, y0 + len), sw)
    drawLine(c, Offset(x1, y0), Offset(x1 - len, y0), sw); drawLine(c, Offset(x1, y0), Offset(x1, y0 + len), sw)
    drawLine(c, Offset(x0, y1), Offset(x0 + len, y1), sw); drawLine(c, Offset(x0, y1), Offset(x0, y1 - len), sw)
    drawLine(c, Offset(x1, y1), Offset(x1 - len, y1), sw); drawLine(c, Offset(x1, y1), Offset(x1, y1 - len), sw)
    if (l.primary) {
        val y = y0 + (t * 0.8f % 1f) * (y1 - y0)
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, c.copy(alpha = 0.8f), Color.Transparent), startX = x0, endX = x1), Offset(x0, y), Offset(x1, y), sw * 0.6f)
        drawRect(c.copy(alpha = 0.05f), topLeft = Offset(x0, y0), size = Size(x1 - x0, y1 - y0))
    }
    val style = TextStyle(color = c, fontSize = if (l.primary) 13.sp else 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    val label = text.measure(l.text, style)
    drawText(label, topLeft = Offset(x0, (y0 - label.size.height - 6f).coerceAtLeast(0f)))
    if (l.primary) {
        val conf = text.measure("GÜVEN ${(l.face.score * 100).toInt()}%", style.copy(fontSize = 10.sp, color = c.copy(alpha = 0.8f)))
        drawText(conf, topLeft = Offset(x0, y1 + 6f))
    }
}

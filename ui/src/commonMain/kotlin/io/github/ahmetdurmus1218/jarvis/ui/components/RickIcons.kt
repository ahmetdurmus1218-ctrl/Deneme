package io.github.ahmetdurmus1218.jarvis.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Rick's own thin-line icons (drawn, not emoji: they look the same on every phone and in every theme). */
enum class RickIcon { CHAT, CUBE, MEMORY, SHIELD, GEAR, MIC, STOP, CAMERA, MENU, HISTORY }

@Composable
fun RickIconView(icon: RickIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 22.dp, stroke: Float = 1.7f) {
    Canvas(modifier.size(size)) { drawRickIcon(icon, tint, stroke) }
}

/** Draws [icon] into the whole draw area, designed on a 24 × 24 grid. */
fun DrawScope.drawRickIcon(icon: RickIcon, tint: Color, strokeUnits: Float = 1.7f) {
    val u = size.minDimension / 24f
    val st = Stroke(strokeUnits * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = strokeUnits) =
        drawLine(tint, p(x1, y1), p(x2, y2), strokeWidth = w * u, cap = StrokeCap.Round)
    fun path(build: Path.() -> Unit) = drawPath(Path().apply(build), tint, style = st)
    fun Path.m(x: Float, y: Float) = moveTo(x * u, y * u)
    fun Path.l(x: Float, y: Float) = lineTo(x * u, y * u)
    fun Path.c(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = cubicTo(x1 * u, y1 * u, x2 * u, y2 * u, x3 * u, y3 * u)
    when (icon) {
        RickIcon.CHAT -> {
            drawRoundRect(tint, p(3.5f, 4.5f), Size(17f * u, 12f * u), CornerRadius(4f * u), style = st)
            path { m(8f, 16.5f); l(8f, 20.5f); l(12.5f, 16.5f) }
            for (x in floatArrayOf(8.5f, 12f, 15.5f)) drawCircle(tint, 0.9f * u, p(x, 10.5f))
        }
        RickIcon.CUBE -> path {
            m(12f, 3f); l(20f, 7.5f); l(20f, 16.5f); l(12f, 21f); l(4f, 16.5f); l(4f, 7.5f); close()
            m(4f, 7.5f); l(12f, 12f); l(20f, 7.5f); m(12f, 12f); l(12f, 21f)
        }
        RickIcon.MEMORY -> {
            drawCircle(tint, 2.4f * u, p(12f, 5f), style = st)
            drawCircle(tint, 2.4f * u, p(5.5f, 16.5f), style = st)
            drawCircle(tint, 2.4f * u, p(18.5f, 16.5f), style = st)
            drawCircle(tint, 1.2f * u, p(12f, 12.3f))
            line(12f, 7.4f, 12f, 11.1f); line(7.3f, 15.2f, 11f, 12.9f); line(16.7f, 15.2f, 13f, 12.9f)
        }
        RickIcon.SHIELD -> {
            path {
                m(12f, 3f); l(19.5f, 6f); l(19.5f, 11.5f); c(19.5f, 16.5f, 16f, 19.5f, 12f, 21f)
                c(8f, 19.5f, 4.5f, 16.5f, 4.5f, 11.5f); l(4.5f, 6f); close()
            }
            path { m(8.7f, 12f); l(11.2f, 14.5f); l(15.5f, 9.8f) }
        }
        RickIcon.GEAR -> {
            drawCircle(tint, 3.2f * u, p(12f, 12f), style = st)
            drawCircle(tint, 6.2f * u, p(12f, 12f), style = st)
            for (i in 0 until 8) {
                val a = i * PI.toFloat() / 4f
                line(12f + cos(a) * 6.2f, 12f + sin(a) * 6.2f, 12f + cos(a) * 8.6f, 12f + sin(a) * 8.6f, 2.4f)
            }
        }
        RickIcon.MIC -> {
            drawRoundRect(tint, p(9f, 3.5f), Size(6f * u, 10f * u), CornerRadius(3f * u), style = st)
            path { m(5.5f, 11f); c(5.5f, 15f, 8.5f, 17.5f, 12f, 17.5f); c(15.5f, 17.5f, 18.5f, 15f, 18.5f, 11f); m(12f, 17.5f); l(12f, 21f) }
        }
        RickIcon.STOP -> drawRoundRect(tint, p(6.5f, 6.5f), Size(11f * u, 11f * u), CornerRadius(2.5f * u))
        RickIcon.CAMERA -> {
            drawRoundRect(tint, p(3.5f, 7f), Size(17f * u, 12f * u), CornerRadius(3f * u), style = st)
            path { m(8.5f, 7f); l(10f, 4.5f); l(14f, 4.5f); l(15.5f, 7f) }
            drawCircle(tint, 3.3f * u, p(12f, 13f), style = st)
        }
        RickIcon.MENU -> { line(5f, 7f, 19f, 7f); line(5f, 12f, 19f, 12f); line(5f, 17f, 14f, 17f) }
        RickIcon.HISTORY -> {
            drawArc(tint, -60f, 320f, false, p(4f, 4f), Size(16f * u, 16f * u), style = st)
            path { m(12f, 8f); l(12f, 12.3f); l(15f, 14f) }
            path { m(4.2f, 5.2f); l(4.6f, 9.2f); l(8.4f, 8.4f) }
        }
    }
}

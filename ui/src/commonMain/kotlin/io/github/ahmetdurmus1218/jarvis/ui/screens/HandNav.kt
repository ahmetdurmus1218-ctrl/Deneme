package io.github.ahmetdurmus1218.jarvis.ui.screens

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ahmetdurmus1218.jarvis.core.vision.AirPointer
import io.github.ahmetdurmus1218.jarvis.core.vision.Hand
import io.github.ahmetdurmus1218.jarvis.core.vision.HandNavigator
import io.github.ahmetdurmus1218.jarvis.core.vision.Point
import io.github.ahmetdurmus1218.jarvis.ui.JarvisController
import io.github.ahmetdurmus1218.jarvis.ui.PlatformBridge
import io.github.ahmetdurmus1218.jarvis.ui.VisionCamera
import io.github.ahmetdurmus1218.jarvis.ui.VisionFrame
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisColors
import io.github.ahmetdurmus1218.jarvis.ui.theme.JarvisUi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Registers [scroll] as the page a hand swipe turns, while this screen is shown. */
@Composable
fun PageScrollOf(scroll: ScrollState) {
    DisposableEffect(scroll) {
        JarvisUi.pageScroll = scroll
        onDispose { if (JarvisUi.pageScroll === scroll) JarvisUi.pageScroll = null }
    }
}

/** The camera image is mapped to the whole screen; its edges are hard to reach, so the inner part is used. */
internal fun toScreen(p: Point, w: Float, h: Float): Point =
    Point(((p.x - 0.12f) / 0.76f).coerceIn(0f, 1f) * w, ((p.y - 0.12f) / 0.76f).coerceIn(0f, 1f) * h, p.z)

/**
 * "El ile yönet": Rick used with the hands while everything else stays as it is. A small camera
 * window in the corner shows the user; over the whole app a cursor follows the index finger.
 * 🤏 quick pinch: tap where the cursor is · 🤏 drag: scroll · ✋ swipe up / down / left / right:
 * turn the page (or, while music plays, the next / previous song) · ✋ hold: pause · 👍 hold:
 * listen · ✊ hold: quiet · 🤏 turn: volume. Frames stay on the device.
 */
@Composable
fun HandNavOverlay(camera: VisionCamera, controller: JarvisController, bridge: PlatformBridge, onClose: () -> Unit) {
    var frame by remember { mutableStateOf<VisionFrame?>(null) }
    var cursor by remember { mutableStateOf<AirPointer.State?>(null) }
    var hold by remember { mutableStateOf<Float?>(null) }
    var note by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var size by remember { mutableStateOf(Size(1f, 1f)) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var tick by remember { mutableStateOf(0L) }
    val nav = remember { HandNavigator() }
    val scope = rememberCoroutineScope()
    val tracking = remember { camera.trackingReady() }

    fun page(dir: Int) {
        val s = JarvisUi.pageScroll ?: return
        scope.launch { s.animateScrollBy(dir * size.height * 0.6f) }
    }

    LaunchedEffect(Unit) {
        var lastFrame: VisionFrame? = null
        while (isActive) {
            val now = try { withInfiniteAnimationFrameMillis { it } } catch (e: kotlinx.coroutines.CancellationException) {
                if (!isActive) throw e
                return@LaunchedEffect
            }
            tick = now
            val f = frame
            if (f === lastFrame) continue
            lastFrame = f
            val h0 = f?.hands?.firstOrNull()
            val mapped = h0?.let { h -> Hand(h.points.map { toScreen(it, size.width, size.height) }) }
            val r = nav.update(now, mapped, size.width, size.height)
            cursor = nav.pointer.state
            hold = nav.holdProgress(now)
            for (e in r.pointerEvents) when (e) {
                is AirPointer.Event.Click -> if (bridge.injectTap(origin.x + e.x, origin.y + e.y)) note = "👆 Tıkladım" to now
                is AirPointer.Event.Scroll -> JarvisUi.pageScroll?.let { s -> scope.launch { s.scrollBy(-e.dy) } }
                else -> {}
            }
            for (c in r.commands) controller.onHand(c, ::page)?.let { note = it to now }
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { size = Size(it.size.width.toFloat(), it.size.height.toFloat()); origin = it.positionInWindow() }) {
        // Only drawing: the overlay never takes the user's own touches.
        Canvas(Modifier.fillMaxSize()) {
            if (tick < 0) return@Canvas
            cursor?.let { a ->
                val c = Offset(a.x, a.y)
                val r = 20.dp.toPx()
                drawCircle(Color.Black.copy(alpha = 0.25f), r * 1.1f, c)
                drawCircle(JarvisColors.Amber, r, c, style = Stroke(3.dp.toPx()))
                drawCircle(JarvisColors.Amber, if (a.pinching) r * 0.55f else r * 0.2f, c)
                hold?.takeIf { it > 0.05f }?.let { p ->
                    drawArc(JarvisColors.Cyan, -90f, 360f * p, false, topLeft = Offset(c.x - r * 1.5f, c.y - r * 1.5f), size = Size(r * 3f, r * 3f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 8.dp), horizontalAlignment = Alignment.End) {
            Box(Modifier.size(84.dp, 112.dp).clip(RoundedCornerShape(12.dp)).border(2.dp, JarvisColors.Amber, RoundedCornerShape(12.dp))) {
                camera.Preview(Modifier.fillMaxSize(), true) { f -> frame = f }
            }
            Text(
                if (!tracking) "El takibi kurulu değil" else "✋ El ile yönetim · kapat",
                color = JarvisColors.Text, fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose).padding(horizontal = 6.dp, vertical = 3.dp),
            )
            note?.takeIf { tick - it.second < 1_800 }?.let { (text, _) ->
                Text(text, color = JarvisColors.Background, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp).background(JarvisColors.Amber, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}

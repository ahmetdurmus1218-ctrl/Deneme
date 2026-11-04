package io.github.ahmetdurmus1218.jarvis.ui.components

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Everything on the hand-control screen that the air cursor can click: each registers where it is
 * (in root coordinates) and what it does. A click in the air runs the smallest one under the
 * cursor, exactly like a tap would.
 */
class AirTargets {
    private class Target(var rect: Rect, var action: () -> Unit)
    private val targets = LinkedHashMap<Any, Target>()

    fun set(key: Any, rect: Rect, action: () -> Unit) {
        val t = targets[key]
        if (t == null) targets[key] = Target(rect, action) else { t.rect = rect; t.action = action }
    }

    fun remove(key: Any) { targets.remove(key) }

    /** The action under ([x], [y]) in root coordinates, the innermost (smallest) one first. */
    fun at(x: Float, y: Float): (() -> Unit)? = targets.values
        .filter { it.rect.width > 0f && it.rect.contains(androidx.compose.ui.geometry.Offset(x, y)) }
        .minByOrNull { it.rect.width * it.rect.height }?.action

    fun size(): Int = targets.size

    /** A target the cursor is on or near: where its middle is (root coordinates) and what it does. */
    class Hit(val center: androidx.compose.ui.geometry.Offset, val action: () -> Unit)

    /**
     * The target under ([x], [y]), or else the closest one within [radius] (a magnet: the hand
     * shakes, a button does not have to be hit exactly). Root coordinates.
     */
    fun near(x: Float, y: Float, radius: Float): Hit? {
        val live = targets.values.filter { it.rect.width > 0f && it.rect.height > 0f }
        val inside = live.filter { it.rect.contains(androidx.compose.ui.geometry.Offset(x, y)) }.minByOrNull { it.rect.width * it.rect.height }
        val t = inside ?: live.map { it to distance(it.rect, x, y) }.filter { it.second <= radius }.minByOrNull { it.second }?.first
        return t?.let { Hit(it.rect.center, it.action) }
    }

    private fun distance(r: Rect, x: Float, y: Float): Float {
        val dx = maxOf(r.left - x, 0f, x - r.right)
        val dy = maxOf(r.top - y, 0f, y - r.bottom)
        return kotlin.math.hypot(dx, dy)
    }
}

/** Registers this element with [targets] so a click in the air runs [action] (taps keep working as before). */
fun Modifier.airTarget(targets: AirTargets?, action: () -> Unit): Modifier = if (targets == null) this else composed {
    val key = remember { Any() }
    val latest by rememberUpdatedState(action)
    DisposableEffect(key) { onDispose { targets.remove(key) } }
    onGloballyPositioned { c -> targets.set(key, c.boundsInRoot()) { latest() } }
}

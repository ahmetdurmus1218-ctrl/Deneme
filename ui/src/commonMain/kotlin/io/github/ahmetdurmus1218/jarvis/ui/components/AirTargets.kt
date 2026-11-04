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
}

/** Registers this element with [targets] so a click in the air runs [action] (taps keep working as before). */
fun Modifier.airTarget(targets: AirTargets?, action: () -> Unit): Modifier = if (targets == null) this else composed {
    val key = remember { Any() }
    val latest by rememberUpdatedState(action)
    DisposableEffect(key) { onDispose { targets.remove(key) } }
    onGloballyPositioned { c -> targets.set(key, c.boundsInRoot()) { latest() } }
}

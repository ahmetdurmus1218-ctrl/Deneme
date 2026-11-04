package io.github.ahmetdurmus1218.jarvis.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lets the hands drive the app in front (taps, scrolls, back, home) while Rick runs in the
 * background — only apps the user chose ([io.github.ahmetdurmus1218.jarvis.core.device.HandApps]),
 * never banks, payment, messaging, password or settings apps, and never while the phone is locked
 * (the floating Rick checks both before every gesture).
 *
 * It does NOT read the screen: `canRetrieveWindowContent` is false, and the only event it receives
 * is "another window came to the front", from which it keeps the package name and nothing else.
 * No network: Rick has no INTERNET permission.
 */
class RickAccessibility : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // Rick's own floating windows and the status bar are not "the app in front".
        if (pkg == packageName || pkg == "com.android.systemui") return
        _front.value = pkg
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _front.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun tap(x: Float, y: Float): Boolean = gesture(Path().apply { moveTo(x, y) }, 60)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 280): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, ms)

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun gesture(path: Path, ms: Long): Boolean = runCatching {
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build(), null, null)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "RickAccess"

        @Volatile
        var instance: RickAccessibility? = null
            private set

        private val _front = MutableStateFlow<String?>(null)

        /** The package of the app in front (as last reported), or null. */
        val front: StateFlow<String?> = _front.asStateFlow()
    }
}

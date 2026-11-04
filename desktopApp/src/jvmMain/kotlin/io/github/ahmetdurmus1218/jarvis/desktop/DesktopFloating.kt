package io.github.ahmetdurmus1218.jarvis.desktop

import io.github.ahmetdurmus1218.jarvis.ui.FloatingRick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The floating Rick on the PC: a small always-on-top window with the living face (Main.kt draws
 * it while [running] is true). Click: talk; double-click: Rick's main window.
 */
object DesktopFloating : FloatingRick {
    private val shown = MutableStateFlow(false)
    override fun allowed(): Boolean = true
    override fun askPermission() {}
    override suspend fun start(): String? { shown.value = true; return null }
    override fun stop() { shown.value = false }
    override val running: StateFlow<Boolean> = shown.asStateFlow()
}

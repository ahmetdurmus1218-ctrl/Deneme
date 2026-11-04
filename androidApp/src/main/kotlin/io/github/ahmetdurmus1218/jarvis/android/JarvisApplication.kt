package io.github.ahmetdurmus1218.jarvis.android

import android.app.Application
import io.github.ahmetdurmus1218.jarvis.ui.AppGraph
import kotlinx.coroutines.launch

class JarvisApplication : Application() {
    val bridge: AndroidBridge by lazy { AndroidBridge(this) }
    private val llm: AndroidLlm by lazy { AndroidLlm(this, bridge.models) }
    val graph: AppGraph by lazy {
        AppGraph(bridge, realEngine = { g -> llm.pick(g.registry, g.settings.state.value.llmId) }, appScope = kotlinx.coroutines.MainScope()).also { g ->
            // While a download runs, a foreground notification keeps Rick from being killed or frozen.
            kotlinx.coroutines.MainScope().launch {
                var was = false
                g.installer.busy.collect { busy -> if (busy != was) { was = busy; DownloadKeeper.keep(this@JarvisApplication, busy) } }
            }
        }
    }
}

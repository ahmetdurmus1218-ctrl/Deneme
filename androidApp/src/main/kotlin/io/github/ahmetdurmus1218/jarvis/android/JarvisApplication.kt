package io.github.ahmetdurmus1218.jarvis.android

import android.app.Application
import io.github.ahmetdurmus1218.jarvis.ui.AppGraph

class JarvisApplication : Application() {
    val bridge: AndroidBridge by lazy { AndroidBridge(this) }
    private val llm: AndroidLlm by lazy { AndroidLlm(this, bridge.models) }
    val graph: AppGraph by lazy {
        AppGraph(bridge, realEngine = { g -> llm.pick(g.registry, g.settings.state.value.llmId) }, appScope = kotlinx.coroutines.MainScope())
    }
}

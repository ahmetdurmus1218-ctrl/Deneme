package io.github.ahmetdurmus1218.jarvis.android

import android.app.Application
import io.github.ahmetdurmus1218.jarvis.ui.AppGraph

class JarvisApplication : Application() {
    val bridge: AndroidBridge by lazy { AndroidBridge(this) }
    val graph: AppGraph by lazy { AppGraph(bridge) }
}

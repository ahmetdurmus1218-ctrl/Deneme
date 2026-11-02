package io.github.ahmetdurmus1218.jarvis.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.ahmetdurmus1218.jarvis.ui.AppGraph
import io.github.ahmetdurmus1218.jarvis.ui.JarvisApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun main() {
    val version = DesktopBridge::class.java.`package`?.implementationVersion ?: System.getProperty("jarvis.version") ?: "dev"
    val graph = AppGraph(DesktopBridge(version))
    val fmt = SimpleDateFormat("d MMM yyyy HH:mm:ss", Locale.forLanguageTag("tr-TR"))
    application {
        Window(
            onCloseRequest = {
                graph.bridge.speechOutput?.stop()
                exitApplication()
            },
            title = "Jarvis",
            state = rememberWindowState(width = 480.dp, height = 860.dp),
        ) {
            JarvisApp(graph) { fmt.format(Date(it)) }
        }
    }
}

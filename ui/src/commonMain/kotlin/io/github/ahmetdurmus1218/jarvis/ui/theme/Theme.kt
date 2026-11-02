package io.github.ahmetdurmus1218.jarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object JarvisColors {
    val Background = Color(0xFF05080D)
    val Surface = Color(0xFF0C121B)
    val SurfaceHigh = Color(0xFF131C28)
    val Cyan = Color(0xFF38D6FF)
    val CyanDim = Color(0xFF1B6F8A)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF5A5F)
    val Green = Color(0xFF4CD99A)
    val Text = Color(0xFFE6F1FA)
    val TextDim = Color(0xFF8AA0B4)
}

private val scheme = darkColorScheme(
    primary = JarvisColors.Cyan,
    onPrimary = Color(0xFF00222E),
    secondary = JarvisColors.Amber,
    background = JarvisColors.Background,
    onBackground = JarvisColors.Text,
    surface = JarvisColors.Surface,
    onSurface = JarvisColors.Text,
    surfaceVariant = JarvisColors.SurfaceHigh,
    onSurfaceVariant = JarvisColors.TextDim,
    error = JarvisColors.Red,
    outline = JarvisColors.CyanDim,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

package io.github.ahmetdurmus1218.jarvis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** One look of the HUD. "accent" plays the role of Rick's cyan in every screen. */
data class Palette(
    val id: String,
    val label: String,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val accent: Color,
    val accentBright: Color,
    val accentDim: Color,
    val second: Color,
    val glass: Color,
    val glassBorder: Color,
    val amber: Color,
    val red: Color,
    val green: Color,
    val text: Color,
    val textDim: Color,
    val light: Boolean = false,
)

object Themes {
    val JARVIS = Palette(
        "jarvis", "R.I.C.K · mavi", Color(0xFF05080D), Color(0xFF0C121B), Color(0xFF131C28),
        Color(0xFF38D6FF), Color(0xFF7DF3FF), Color(0xFF1B6F8A), Color(0xFF9D7CFF), Color(0x2216324A), Color(0x5538D6FF),
        Color(0xFFFFB547), Color(0xFFFF5A5F), Color(0xFF4CD99A), Color(0xFFE6F1FA), Color(0xFF8AA0B4),
    )
    val MARK3 = Palette(
        "mark3", "Mark III · kırmızı-altın", Color(0xFF0D0505), Color(0xFF1A0B0B), Color(0xFF261313),
        Color(0xFFFFB13B), Color(0xFFFFD98A), Color(0xFF8A5A1B), Color(0xFFFF4D4D), Color(0x22401010), Color(0x55FFB13B),
        Color(0xFFFFC266), Color(0xFFFF4D4D), Color(0xFF7BD88F), Color(0xFFFBEFE6), Color(0xFFB89A8A),
    )
    val FRIDAY = Palette(
        "friday", "F.R.I.D.A.Y · mor", Color(0xFF08060F), Color(0xFF120D1F), Color(0xFF1B1430),
        Color(0xFFB07CFF), Color(0xFFD9C2FF), Color(0xFF5B3D8A), Color(0xFF5CE1E6), Color(0x22281A40), Color(0x55B07CFF),
        Color(0xFFFFB547), Color(0xFFFF5A7A), Color(0xFF5CE1A0), Color(0xFFEEE8FA), Color(0xFF9A8FB4),
    )
    val ULTRON = Palette(
        "ultron", "Ultron · kırmızı", Color(0xFF0A0404), Color(0xFF170909), Color(0xFF221010),
        Color(0xFFFF3B3B), Color(0xFFFF8A8A), Color(0xFF8A1B1B), Color(0xFFFFB547), Color(0x22401010), Color(0x55FF3B3B),
        Color(0xFFFFB547), Color(0xFFFF6B6B), Color(0xFF6BD99A), Color(0xFFFBE9E9), Color(0xFFB48A8A),
    )
    val MATRIX = Palette(
        "matrix", "Terminal · yeşil", Color(0xFF020803), Color(0xFF07130A), Color(0xFF0C1C10),
        Color(0xFF3DFF8A), Color(0xFFA8FFC9), Color(0xFF1B8A4A), Color(0xFF7DF3FF), Color(0x2210401C), Color(0x553DFF8A),
        Color(0xFFE6FF5C), Color(0xFFFF5A5F), Color(0xFF3DFF8A), Color(0xFFDDFBE6), Color(0xFF7FAE8C),
    )
    val VIBRANIUM = Palette(
        "vibranium", "Vibranyum · gümüş-mor", Color(0xFF07070B), Color(0xFF111119), Color(0xFF1A1A25),
        Color(0xFFC0C8FF), Color(0xFFE4E8FF), Color(0xFF5A6090), Color(0xFFB07CFF), Color(0x22303050), Color(0x55C0C8FF),
        Color(0xFFFFC266), Color(0xFFFF5A7A), Color(0xFF6BE0B0), Color(0xFFF0F2FF), Color(0xFF9EA3BE),
    )
    val DAYLIGHT = Palette(
        "gunisigi", "Gün ışığı · açık", Color(0xFFF2F6FA), Color(0xFFFFFFFF), Color(0xFFE6EDF4),
        Color(0xFF0077B6), Color(0xFF0096C7), Color(0xFF90B8D0), Color(0xFF6A4CFF), Color(0x220077B6), Color(0x550077B6),
        Color(0xFFB86E00), Color(0xFFD62839), Color(0xFF138A4F), Color(0xFF0B1B2B), Color(0xFF516579), light = true,
    )
    val all = listOf(JARVIS, MARK3, FRIDAY, ULTRON, MATRIX, VIBRANIUM, DAYLIGHT)
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: JARVIS
}

/**
 * The HUD colours. They follow the chosen theme: every screen reads them through these
 * properties, so switching the theme redraws the whole app at once.
 */
object JarvisColors {
    var palette by mutableStateOf(Themes.JARVIS)

    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceHigh get() = palette.surfaceHigh
    val Cyan get() = palette.accent
    val CyanBright get() = palette.accentBright
    val CyanDim get() = palette.accentDim
    val Violet get() = palette.second
    /** Translucent panels over the HUD background. */
    val Glass get() = palette.glass
    val GlassBorder get() = palette.glassBorder
    val Amber get() = palette.amber
    val Red get() = palette.red
    val Green get() = palette.green
    val Text get() = palette.text
    val TextDim get() = palette.textDim
}

/** Interface options that are not colours. */
object JarvisUi {
    /** Calmer core and effects (fewer moving parts) for people sensitive to motion, or slow devices. */
    var reduceMotion by mutableStateOf(false)
}

private fun scheme(p: Palette) = if (p.light) {
    lightColorScheme(
        primary = p.accent, onPrimary = Color.White, secondary = p.amber, background = p.background, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim, error = p.red, outline = p.accentDim,
    )
} else {
    darkColorScheme(
        primary = p.accent, onPrimary = p.background, secondary = p.amber, background = p.background, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.textDim, error = p.red, outline = p.accentDim,
    )
}

@Composable
fun JarvisTheme(themeId: String = JarvisColors.palette.id, fontScale: Float = 1f, reduceMotion: Boolean = JarvisUi.reduceMotion, content: @Composable () -> Unit) {
    val p = Themes.byId(themeId)
    if (JarvisColors.palette != p) JarvisColors.palette = p
    if (JarvisUi.reduceMotion != reduceMotion) JarvisUi.reduceMotion = reduceMotion
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * fontScale.coerceIn(0.8f, 1.5f))) {
        MaterialTheme(colorScheme = scheme(p), content = content)
    }
}

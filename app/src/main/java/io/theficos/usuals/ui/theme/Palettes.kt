package io.theficos.usuals.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

enum class AppPalette(val id: String) {
    PLUM("plum"), GROVE("grove"), TIDE("tide");

    companion object {
        fun fromId(id: String?): AppPalette = entries.firstOrNull { it.id == id } ?: PLUM
    }
}

internal fun paletteScheme(palette: AppPalette, dark: Boolean): ColorScheme = when (palette) {
    AppPalette.PLUM -> if (dark) DarkScheme else LightScheme
    AppPalette.GROVE -> if (dark) GroveDark else GroveLight
    AppPalette.TIDE -> if (dark) TideDark else TideLight
}

private fun palette(
    dark: Boolean, primary: Long, ground: Long, paper: Long, ink: Long, muted: Long,
    tint: Long, onTint: Long, secondary: Long, onSecondary: Long,
    tertiary: Long, onTertiary: Long, outline: Long, line: Long, pressed: Long,
): ColorScheme {
    val base = if (dark) DarkScheme else LightScheme
    val onAction = Color(if (dark) ground else paper)
    return base.copy(
        primary = Color(primary), onPrimary = onAction,
        primaryContainer = Color(tint), onPrimaryContainer = Color(onTint),
        secondary = Color(muted), onSecondary = onAction,
        secondaryContainer = Color(secondary), onSecondaryContainer = Color(onSecondary),
        tertiary = Color(onTertiary), onTertiary = onAction,
        tertiaryContainer = Color(tertiary), onTertiaryContainer = Color(onTertiary),
        background = Color(ground), onBackground = Color(ink),
        surface = Color(paper), onSurface = Color(ink), surfaceTint = Color(paper),
        surfaceVariant = Color(pressed), onSurfaceVariant = Color(muted),
        surfaceDim = Color(if (dark) ground else line), surfaceBright = Color(if (dark) pressed else paper),
        surfaceContainerLowest = Color(if (dark) ground else paper), surfaceContainerLow = Color(paper),
        surfaceContainer = Color(pressed), surfaceContainerHigh = Color(pressed), surfaceContainerHighest = Color(line),
        outline = Color(outline), outlineVariant = Color(line),
        inverseSurface = Color(ink), inverseOnSurface = Color(ground), scrim = if (dark) Color.Black else Color(ink),
    )
}

private val GroveLight = palette(
    dark = false, primary = 0xFF36593F, ground = 0xFFF3F5ED, paper = 0xFFFCFDF8,
    ink = 0xFF243428, muted = 0xFF53644F, tint = 0xFFDEE8D6, onTint = 0xFF29452F,
    secondary = 0xFFEDE1CA, onSecondary = 0xFF624C2D, tertiary = 0xFFE2E9DC,
    onTertiary = 0xFF455D3A, outline = 0xFF75806E, line = 0xFFD8E0D1, pressed = 0xFFE8EDE1,
)
private val GroveDark = palette(
    dark = true, primary = 0xFFBCD5AA, ground = 0xFF171F19, paper = 0xFF222D24,
    ink = 0xFFEDF3E8, muted = 0xFFBDCCB7, tint = 0xFF354A35, onTint = 0xFFEDF3E8,
    secondary = 0xFF443B29, onSecondary = 0xFFEDDDBD, tertiary = 0xFF344232,
    onTertiary = 0xFFC9DDBE, outline = 0xFF94A48C, line = 0xFF414F3D, pressed = 0xFF2C392E,
)
private val TideLight = palette(
    dark = false, primary = 0xFF285D70, ground = 0xFFEFF5F7, paper = 0xFFFBFDFE,
    ink = 0xFF233640, muted = 0xFF526872, tint = 0xFFD7EAF0, onTint = 0xFF214E61,
    secondary = 0xFFDBEAE3, onSecondary = 0xFF345D4C, tertiary = 0xFFF0E2CD,
    onTertiary = 0xFF624C2E, outline = 0xFF728791, line = 0xFFD3E1E7, pressed = 0xFFE4EEF2,
)
private val TideDark = palette(
    dark = true, primary = 0xFFA7D6E5, ground = 0xFF151F27, paper = 0xFF202D37,
    ink = 0xFFECF3F7, muted = 0xFFBACDD8, tint = 0xFF294754, onTint = 0xFFEBF5F9,
    secondary = 0xFF2C443E, onSecondary = 0xFFC3E1D2, tertiary = 0xFF473D2D,
    onTertiary = 0xFFEFDEC0, outline = 0xFF90A7B4, line = 0xFF40515D, pressed = 0xFF2A3944,
)

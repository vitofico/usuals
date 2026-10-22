package io.theficos.usuals.ui.theme

import androidx.compose.ui.graphics.Color
import io.theficos.usuals.smart.Category

// A warm editorial palette keeps groceries and clear actions in the foreground.
internal val Ground = Color(0xFFF8F5EF)
internal val Paper = Color(0xFFFFFDFA)
internal val Ink = Color(0xFF302331)
internal val Secondary = Color(0xFF6E5E6B)
internal val Accent = Color(0xFF583D53)
internal val Petal = Color(0xFFEEDFE8)
internal val Champagne = Color(0xFFEFE1CA)
internal val OnChampagne = Color(0xFF664D2D)
internal val Sage = Color(0xFFE2E6DB)
internal val OnSage = Color(0xFF46533B)
internal val Hairline = Color(0xFFE3D9DD)
internal val GroundPressed = Color(0xFFF0EAE7)
internal val Outline = Color(0xFF887887)
internal val Destructive = Color(0xFFA72F48)

internal val GroundDark = Color(0xFF1D181E)
internal val PaperDark = Color(0xFF28212A)
internal val InkDark = Color(0xFFF5EDF1)
internal val SecondaryDark = Color(0xFFC6B6C1)
internal val AccentDark = Color(0xFFE2BCD4)
internal val PetalDark = Color(0xFF4B3548)
internal val ChampagneDark = Color(0xFF453929)
internal val OnChampagneDark = Color(0xFFEDDBBA)
internal val SageDark = Color(0xFF343E2F)
internal val OnSageDark = Color(0xFFD0DFBF)
internal val HairlineDark = Color(0xFF4C3E4A)
internal val GroundPressedDark = Color(0xFF352C35)
internal val OutlineDark = Color(0xFFAA96A5)
internal val DestructiveDark = Color(0xFFFFB3C3)

private val LightCategories = mapOf(
    Category.PRODUCE to Color(0xFF4C6E50), Category.DAIRY to Color(0xFF705274),
    Category.BAKERY to Color(0xFF8A6035), Category.MEAT to Color(0xFF9B4E64),
    Category.FISH to Color(0xFF456F80), Category.FROZEN to Color(0xFF467778),
    Category.PANTRY to Color(0xFF866344), Category.DRINKS to Color(0xFF8A4D70),
    Category.SNACKS to Color(0xFF856A2C), Category.HOUSEHOLD to Color(0xFF60678A),
    Category.PERSONAL_CARE to Color(0xFF7B5C80), Category.OTHER to Color(0xFF686570),
)
private val DarkCategories = mapOf(
    Category.PRODUCE to Color(0xFFB7D5AE), Category.DAIRY to Color(0xFFD9B8DD),
    Category.BAKERY to Color(0xFFE1C198), Category.MEAT to Color(0xFFE7AFBF),
    Category.FISH to Color(0xFFAFD0DC), Category.FROZEN to Color(0xFFA8D7D3),
    Category.PANTRY to Color(0xFFDFC4A4), Category.DRINKS to Color(0xFFE3B2D0),
    Category.SNACKS to Color(0xFFE9D199), Category.HOUSEHOLD to Color(0xFFC5CBE4),
    Category.PERSONAL_CARE to Color(0xFFD8BEDC), Category.OTHER to Color(0xFFC9C0D0),
)

internal fun categoryAccent(category: Category, darkTheme: Boolean): Color =
    (if (darkTheme) DarkCategories else LightCategories).getValue(category)

/**
 * Data colours for product grade chips, best to worst. They state a fact about a
 * product rather than the brand, so the palette picker and dark mode leave them
 * alone. The hues follow the familiar green to red progression but are our own
 * contrast-tuned values: the Nutri-Score rules of use forbid reproducing any
 * part of its logo, and every glyph colour keeps at least 4.5:1 on its fill so
 * the letter, not the hue, carries the meaning for colour-blind readers.
 */
internal enum class GradeShade(val fill: Color, val glyph: Color) {
    STEP_1(Color(0xFF1B7A3A), Color(0xFFFFFFFF)),
    STEP_2(Color(0xFF7CB22E), Color(0xFF16210A)),
    STEP_3(Color(0xFFF2C200), Color(0xFF2A2100)),
    STEP_4(Color(0xFFE8780C), Color(0xFF2B1600)),
    STEP_5(Color(0xFFC9361A), Color(0xFFFFFFFF)),
    STEP_6(Color(0xFF9E2A1B), Color(0xFFFFFFFF)),
}

/**
 * Amber for allergen flags. Like the grade shades it marks a fact about a
 * product, so it stays the same under every palette; both values keep 4.5:1 on
 * every palette's backgrounds and surfaces, and the words, not the hue, carry
 * the warning.
 */
private val CautionLight = Color(0xFF7A4B00)
private val CautionDark = Color(0xFFF2BE5C)

internal fun cautionColor(darkTheme: Boolean): Color = if (darkTheme) CautionDark else CautionLight

package io.theficos.usuals.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.theficos.usuals.R

// One bundled variable font, available offline on every supported Android version.
@OptIn(ExperimentalTextApi::class)
private val Manrope = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.manrope, weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(fontFamily = Manrope, fontWeight = weight, fontSize = size.sp,
        lineHeight = height.sp, letterSpacing = tracking.sp)

// Android's bundled serif gives display text an editorial voice without a font download.
private fun display(size: Int, height: Int, tracking: Float = -0.5f) =
    TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = size.sp,
        lineHeight = height.sp, letterSpacing = tracking.sp)

val UsualsTypography = Typography(
    displayLarge = display(52, 60, -1f),
    displayMedium = display(44, 52, -0.8f),
    displaySmall = display(38, 46, -0.6f),
    headlineLarge = display(34, 42),
    headlineMedium = display(30, 38),
    headlineSmall = display(26, 34, -0.3f),
    titleLarge = type(22, 30, FontWeight.SemiBold, -0.3f),
    titleMedium = type(17, 24, FontWeight.SemiBold, -0.15f),
    titleSmall = type(14, 20, FontWeight.SemiBold),
    bodyLarge = type(16, 24, FontWeight.Medium),
    bodyMedium = type(14, 21),
    bodySmall = type(12, 18),
    labelLarge = type(14, 20, FontWeight.SemiBold),
    labelMedium = type(12, 18, FontWeight.SemiBold),
    labelSmall = type(11, 16, FontWeight.SemiBold, 0.3f),
)

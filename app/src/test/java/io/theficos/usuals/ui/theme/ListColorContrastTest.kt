package io.theficos.usuals.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.usuals.model.ListColor
import org.junit.Test

class ListColorContrastTest {
    private fun ratio(a: Color, b: Color) = (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
    @Test fun everyListColorIsReadableAcrossThemesAndCountPills() {
        for (palette in AppPalette.entries) for (dark in listOf(false, true)) {
            val scheme = paletteScheme(palette, dark)
            for (color in listOf<ListColor?>(null) + ListColor.entries) {
                val glyph = listAccent(color, scheme)
                val pigments = listOf(scheme.primary, scheme.surface,
                    lerp(scheme.secondaryContainer, scheme.onSecondaryContainer, if (dark) 0.45f else 0.16f),
                    lerp(scheme.tertiaryContainer, scheme.onTertiaryContainer, 0.35f))
                val artwork = pigments.map { pigment -> lerp(scheme.surface, pigment, AmbientPigmentStrength)
                    .copy(alpha = AmbientCollageOpacity).compositeOver(scheme.background) }
                for (surface in listOf(scheme.background, scheme.surface, scheme.primaryContainer, scheme.surfaceContainerHigh, listAccentFill(color, scheme)) + artwork) {
                    assertWithMessage("%s/%s/%s", palette, dark, color).that(ratio(glyph, surface).toDouble()).isAtLeast(4.5)
                }
            }
        }
    }
}

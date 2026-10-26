package io.theficos.usuals.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import io.theficos.usuals.smart.Category

class ThemeContrastTest {
    private fun ratio(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)

    @Test fun `every palette keeps light and dark content readable`() {
        for (palette in AppPalette.entries) for (dark in listOf(false, true)) {
            val name = "${palette.id}/${if (dark) "dark" else "light"}"
            val scheme = paletteScheme(palette, dark)
            val textPairs = listOf(
                scheme.onBackground to scheme.background,
                scheme.onSurface to scheme.surface,
                scheme.onSurfaceVariant to scheme.surfaceVariant,
                scheme.onSurfaceVariant to scheme.background,
                scheme.primary to scheme.background,
                scheme.onSurfaceVariant to scheme.primaryContainer,
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.onErrorContainer to scheme.errorContainer,
                scheme.error to scheme.surface,
            )
            textPairs.forEachIndexed { i, (text, surface) ->
                assertWithMessage("%s text pair %s", name, i).that(ratio(text, surface).toDouble()).isAtLeast(4.5)
            }
            for (category in Category.entries) {
                val accent = categoryAccent(category, dark)
                val surface = accent.copy(alpha = 0.10f).compositeOver(scheme.background)
                assertWithMessage("%s category %s", name, category)
                    .that(ratio(accent, surface).toDouble()).isAtLeast(3.0)
            }
            for (surface in listOf(scheme.background, scheme.surface, scheme.surfaceContainerLow)) {
                assertWithMessage("%s allergen caution", name).that(ratio(cautionColor(dark), surface).toDouble()).isAtLeast(4.5)
            }
            assertWithMessage("%s control outlines", name)
                .that(ratio(scheme.outline, scheme.surface).toDouble()).isAtLeast(3.0)
        }
    }

    @Test fun `every grade glyph is readable on its fill`() {
        for (shade in GradeShade.entries) {
            assertWithMessage("grade %s", shade).that(ratio(shade.glyph, shade.fill).toDouble()).isAtLeast(4.5)
        }
    }

    @Test fun `ambient collage preserves exposed text actions and category glyphs in every scheme`() {
        for (palette in AppPalette.entries) for (dark in listOf(false, true)) {
            val scheme = paletteScheme(palette, dark)
            val layers = listOf(
                lerp(scheme.secondaryContainer, scheme.onSecondaryContainer, if (dark) 0.45f else 0.16f),
                lerp(scheme.tertiaryContainer, scheme.onTertiaryContainer, 0.35f),
                scheme.surface, scheme.primary,
            )
            // Muted metadata uses an opaque ambientLabelBacking, checked against background above.
            for ((index, layer) in layers.withIndex()) {
                val pigment = lerp(scheme.surface, layer, AmbientPigmentStrength)
                val ground = pigment.copy(alpha = AmbientCollageOpacity).compositeOver(scheme.background)
                for (text in listOf(scheme.onBackground, scheme.primary)) {
                    assertWithMessage("%s/%s ambient layer %s text", palette, dark, index)
                        .that(ratio(text, ground).toDouble()).isAtLeast(4.5)
                }
                for (category in Category.entries) {
                    val accent = categoryAccent(category, dark)
                    val chip = accent.copy(alpha = 0.08f).compositeOver(ground)
                    assertWithMessage("%s/%s ambient layer %s category %s", palette, dark, index, category)
                        .that(ratio(accent, chip).toDouble()).isAtLeast(3.0)
                }
            }
        }
    }
}

package io.theficos.usuals.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
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
            assertWithMessage("%s control outlines", name)
                .that(ratio(scheme.outline, scheme.surface).toDouble()).isAtLeast(3.0)
        }
    }
}

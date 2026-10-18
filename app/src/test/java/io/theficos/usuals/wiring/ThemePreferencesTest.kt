package io.theficos.usuals.wiring

import android.content.Context
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.R
import io.theficos.usuals.di.PreferenceSettingsStore
import io.theficos.usuals.di.ThemePreferences
import io.theficos.usuals.ui.theme.AppPalette
import io.theficos.usuals.ui.theme.paletteScheme
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemePreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `palette defaults and future unknown values preserve the original appearance`() {
        assertThat(ThemePreferences(context).palette.value).isEqualTo(AppPalette.PLUM)
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("palette", "future").commit()
        assertThat(ThemePreferences(context).palette.value).isEqualTo(AppPalette.PLUM)
    }

    @Test fun `every selection is immediate and survives a new preferences instance`() = runTest {
        val themes = ThemePreferences(context)
        for (palette in listOf(AppPalette.GROVE, AppPalette.TIDE, AppPalette.PLUM)) {
            themes.select(palette)
            assertThat(themes.palette.value).isEqualTo(palette)
            assertThat(ThemePreferences(context).palette.value).isEqualTo(palette)
            assertThat(PreferenceSettingsStore(context).load().scanLookupEnabled).isFalse()
        }
    }

    @Test fun `light widget surfaces match their application palettes`() = widgetSurfaces(false)

    @Test @Config(qualifiers = "night")
    fun `dark widget surfaces match their application palettes`() = widgetSurfaces(true)

    private fun widgetSurfaces(dark: Boolean) {
        for ((palette, surface, line) in listOf(
            Triple(AppPalette.PLUM, R.color.widget_surface, R.color.widget_outline),
            Triple(AppPalette.GROVE, R.color.widget_grove_surface, R.color.widget_grove_outline),
            Triple(AppPalette.TIDE, R.color.widget_tide_surface, R.color.widget_tide_outline),
        )) {
            val scheme = paletteScheme(palette, dark)
            assertThat(context.getColor(surface)).isEqualTo(scheme.surface.toArgb())
            assertThat(context.getColor(line)).isEqualTo(scheme.outlineVariant.toArgb())
            val layout = when (palette) {
                AppPalette.PLUM -> R.layout.shopping_widget
                AppPalette.GROVE -> R.layout.shopping_widget_grove
                AppPalette.TIDE -> R.layout.shopping_widget_tide
            }
            val widget = RemoteViews(context.packageName, layout).apply(context, FrameLayout(context))
            assertThat(widget.findViewById<TextView>(R.id.widget_title).currentTextColor).isEqualTo(scheme.primary.toArgb())
            assertThat(widget.findViewById<TextView>(R.id.widget_items).currentTextColor).isEqualTo(scheme.onSurface.toArgb())
            assertThat(widget.findViewById<TextView>(R.id.widget_count).currentTextColor).isEqualTo(scheme.onSurfaceVariant.toArgb())
        }
    }
}

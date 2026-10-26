package io.theficos.usuals.di

import android.content.Context
import io.theficos.usuals.ui.theme.AppPalette
import io.theficos.usuals.ui.theme.BackgroundStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(AppPalette.fromId(prefs.getString("palette", null)))
    val palette = selected.asStateFlow()
    private val selectedBackground = MutableStateFlow(BackgroundStyle.fromId(prefs.getString("background_style", null)))
    val backgroundStyle = selectedBackground.asStateFlow()

    @Synchronized fun select(palette: AppPalette) {
        prefs.edit().putString("palette", palette.id).apply()
        selected.value = palette
    }

    @Synchronized fun selectBackground(style: BackgroundStyle) {
        prefs.edit().putString("background_style", style.id).apply()
        selectedBackground.value = style
    }
}

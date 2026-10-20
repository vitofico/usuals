package io.theficos.usuals.di

import android.content.Context
import io.theficos.usuals.ui.theme.AppPalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val selected = MutableStateFlow(AppPalette.fromId(prefs.getString("palette", null)))
    val palette = selected.asStateFlow()

    @Synchronized fun select(palette: AppPalette) {
        prefs.edit().putString("palette", palette.id).apply()
        selected.value = palette
    }
}

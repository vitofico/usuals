package io.theficos.usuals.di

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TutorialPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tutorial", Context.MODE_PRIVATE)
    private val seen = MutableStateFlow(prefs.getBoolean("completed_v1", false))
    val completed = seen.asStateFlow()

    fun complete() {
        prefs.edit().putBoolean("completed_v1", true).apply()
        seen.value = true
    }
}

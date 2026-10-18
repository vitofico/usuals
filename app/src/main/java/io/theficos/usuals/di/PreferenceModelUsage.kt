package io.theficos.usuals.di

import android.content.Context
import io.theficos.usuals.llm.ModelUsage

class PreferenceModelUsage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("model_usage", Context.MODE_PRIVATE)
    fun read(): ModelUsage? = if (!prefs.contains("day")) null else ModelUsage(prefs.getLong("day", Long.MIN_VALUE), prefs.getInt("calls", 0))
    fun write(value: ModelUsage) {
        check(prefs.edit().putLong("day", value.utcDay).putInt("calls", value.calls).commit())
    }
}

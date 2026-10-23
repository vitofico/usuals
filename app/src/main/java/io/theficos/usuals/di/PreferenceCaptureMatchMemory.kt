package io.theficos.usuals.di

import android.content.Context
import io.theficos.usuals.ui.list.CaptureMatchMemory

class PreferenceCaptureMatchMemory(context: Context) : CaptureMatchMemory {
    private val prefs = context.applicationContext.getSharedPreferences("capture_matches", Context.MODE_PRIVATE)
    override fun declined(scope: String, from: String, to: String): Boolean =
        "$from|$to" in prefs.getStringSet(scope, emptySet()).orEmpty()

    @Synchronized override fun decline(scope: String, from: String, to: String) {
        prefs.edit().putStringSet(scope, prefs.getStringSet(scope, emptySet()).orEmpty() + "$from|$to").apply()
    }
}

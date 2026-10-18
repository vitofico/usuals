package io.theficos.usuals.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferencePromptMemoryTest {
    @Test fun legacyPromptsMigrateOnceToTheOriginalInstallationScope() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("history_prompts", Context.MODE_PRIVATE)
        val a = java.util.UUID.randomUUID().toString()
        val b = java.util.UUID.randomUUID().toString()
        val key = "carryover:" + "long-key".repeat(50)
        prefs.edit().clear().putString(key, "batch").commit()
        val first = PreferencePromptMemory(context)
        first.migrateScope(a)
        assertThat(first.claim("$a:$key", "batch")).isFalse()
        assertThat(prefs.contains(key)).isFalse()
        PreferencePromptMemory(context).migrateScope(b)
        assertThat(first.claim("$b:$key", "batch")).isTrue()
        assertThat(prefs.getString("$a:$key", null)).isEqualTo("batch")
        assertThat(prefs.contains("$b:$a:$key")).isFalse()
        prefs.edit().clear().commit()
    }
}

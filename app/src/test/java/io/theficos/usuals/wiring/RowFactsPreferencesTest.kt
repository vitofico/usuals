package io.theficos.usuals.wiring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.di.PreferenceSettingsStore
import io.theficos.usuals.di.preferenceRowFacts
import io.theficos.usuals.ui.product.RowFactsChoices
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Row-fact choices survive a restart, and rows can follow the lookup opt-in as it changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RowFactsPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `grades default on and a change is still there after a restart`() {
        assertThat(preferenceRowFacts(context).state.value).isEqualTo(RowFactsChoices())
        preferenceRowFacts(context).showGrades(false)
        assertThat(preferenceRowFacts(context).state.value.showGrades).isFalse()
    }

    @Test fun `an unreadable file falls back to the defaults`() {
        context.getSharedPreferences("row_facts", Context.MODE_PRIVATE).edit().putString("state", "{not json").commit()
        assertThat(preferenceRowFacts(context).state.value).isEqualTo(RowFactsChoices())
    }

    @Test fun `the lookup opt-in is observable`() {
        val store = PreferenceSettingsStore(context)
        assertThat(store.scanLookup.value).isFalse()
        store.setScanLookupEnabled(true)
        assertThat(store.scanLookup.value).isTrue()
        assertThat(PreferenceSettingsStore(context).scanLookup.value).isTrue()
    }
}

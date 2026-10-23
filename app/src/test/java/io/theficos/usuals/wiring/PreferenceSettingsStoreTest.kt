package io.theficos.usuals.wiring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.auth.ProviderKind
import io.theficos.usuals.auth.ProviderSettings
import io.theficos.usuals.di.PreferenceSettingsStore
import io.theficos.usuals.ui.settings.AppSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings persist and an older snapshot cannot undo an explicit privacy choice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PreferenceSettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun a_phone_nobody_has_configured_reads_the_code_defaults() = runTest {
        assertThat(PreferenceSettingsStore(context).load()).isEqualTo(AppSettings())
    }

    @Test
    fun what_the_screen_changes_is_still_there_after_a_restart() = runTest {
        PreferenceSettingsStore(context).save(
            AppSettings(
                provider = ProviderSettings.ollama(
                    baseUrl = "http://10.0.2.2:11434/v1",
                    model = "qwen3.8:27b-mlx",
                ),
                dailyCallCeiling = 12,
                automaticMatchingEnabled = true,
            ),
        )

        // A second instance, which is what the next launch builds.
        val reopened = PreferenceSettingsStore(context).load()

        assertThat(reopened.provider.kind).isEqualTo(ProviderKind.OLLAMA)
        assertThat(reopened.provider.model).isEqualTo("qwen3.8:27b-mlx")
        assertThat(reopened.dailyCallCeiling).isEqualTo(12)
        assertThat(reopened.automaticMatchingEnabled).isTrue()
    }

    @Test
    fun opt_out_is_immediate_and_survives_an_older_pending_save() = runTest {
        val store = PreferenceSettingsStore(context)
        val old = AppSettings(scanLookupEnabled = true)
        store.save(old)
        store.setScanLookupEnabled(false)
        assertThat(store.scanLookupEnabled()).isFalse()
        store.save(old)
        assertThat(store.scanLookupEnabled()).isFalse()
        assertThat(PreferenceSettingsStore(context).load().scanLookupEnabled).isFalse()
    }

    @Test
    fun the_basket_overview_is_off_by_default_and_its_choice_outlives_an_older_save() = runTest {
        val store = PreferenceSettingsStore(context)
        assertThat(store.basketOverviewEnabled()).isFalse()
        store.setBasketOverviewEnabled(true)
        store.save(AppSettings())
        assertThat(store.basketOverviewEnabled()).isTrue()
        assertThat(PreferenceSettingsStore(context).load().basketOverviewEnabled).isTrue()
    }
    @Test fun automatic_matching_opt_out_survives_an_older_pending_save() = runTest {
        val store = PreferenceSettingsStore(context)
        val old = AppSettings(automaticMatchingEnabled = true)
        store.save(old)
        store.setAutomaticMatchingEnabled(false)
        store.save(old)
        assertThat(store.automaticMatchingEnabled()).isFalse()
        assertThat(PreferenceSettingsStore(context).load().automaticMatchingEnabled).isFalse()
    }

    @Test fun declined_capture_matches_survive_restart_and_stay_scoped_to_the_list() {
        val first = io.theficos.usuals.di.PreferenceCaptureMatchMemory(context)
        first.decline("groceries", "bananna", "banana")
        val reopened = io.theficos.usuals.di.PreferenceCaptureMatchMemory(context)
        assertThat(reopened.declined("groceries", "bananna", "banana")).isTrue()
        assertThat(reopened.declined("another-list", "bananna", "banana")).isFalse()
        assertThat(reopened.declined("groceries", "bananna", "bandana")).isFalse()
    }

}

package io.theficos.usuals.di

import android.content.Context
import io.theficos.usuals.auth.ProviderSettingsStore
import io.theficos.usuals.ui.settings.AppSettings
import io.theficos.usuals.ui.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the settings screen's choices actually live on the phone.
 *
 * Two files rather than one, and the split is the one `:auth` already drew. The
 * provider half (which endpoint, which model, is it local) goes to
 * [ProviderSettingsStore], because that is the file `:auth` owns and the one a
 * tenancy test already watches for a leaked key. Everything else is not
 * provider configuration and goes beside it in an ordinary preference file.
 *
 * Nothing here is a secret. The API key never passes through this class: it is
 * written through `SecretStorePort` into the keystore and never read back, and
 * the absence of a field for it is invariant 5 expressed as missing code rather
 * than as a comment.
 *
 * Reads fall back to the code defaults on [AppSettings] rather than inventing
 * their own, so "never configured" and "configured to the default" produce the
 * same screen and there is only one place a default is written down.
 */
class PreferenceSettingsStore(
    context: Context,
    private val providers: ProviderSettingsStore = ProviderSettingsStore(context),
) : SettingsStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val mutationLock = Any()
    @Volatile private var scanLookupOverride: Boolean? = null

    fun scanLookupEnabled(): Boolean = scanLookupOverride ?: prefs.getBoolean("scan_lookup", false)

    private val scanLookupState = MutableStateFlow(scanLookupEnabled())

    /** The opt-in as it changes, for list rows that show cached product facts only while it is on. */
    val scanLookup: StateFlow<Boolean> = scanLookupState.asStateFlow()

    override fun setScanLookupEnabled(enabled: Boolean) {
        scanLookupOverride = enabled
        scanLookupState.value = enabled
        synchronized(mutationLock) {
            prefs.edit().putBoolean("scan_lookup", enabled).apply()
        }
    }

    fun dailyCallCeiling(): Int = prefs.getInt(KEY_DAILY_CALL_CEILING, AppSettings.DEFAULT_DAILY_CALL_CEILING).coerceAtLeast(0)

    override suspend fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            provider = providers.load() ?: defaults.provider,
            dailyCallCeiling = dailyCallCeiling(),
            scanLookupEnabled = scanLookupEnabled(),
        )
    }

    /**
     * `commit()` rather than `apply()`, for the reason [ProviderSettingsStore]
     * gives: this runs from a screen that has just told the person their choice
     * is saved, and it runs on a worker dispatcher rather than in anyone's hot
     * path, so there is nothing to gain by reporting success early.
     */
    override suspend fun save(settings: AppSettings) {
        providers.save(settings.provider)
        synchronized(mutationLock) {
            prefs.edit()
                .putInt(KEY_DAILY_CALL_CEILING, settings.dailyCallCeiling)
                .putBoolean("scan_lookup", scanLookupOverride ?: settings.scanLookupEnabled)
                .commit()
        }
        scanLookupState.value = scanLookupEnabled()
    }

    private companion object {
        const val FILE_NAME = "app_settings"

        const val KEY_DAILY_CALL_CEILING = "daily_call_ceiling"
    }
}

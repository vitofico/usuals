package io.theficos.usuals.auth

import android.content.Context
import android.util.Log

/**
 * Persists [ProviderSettings] in an ordinary `SharedPreferences` file.
 *
 * Ordinary, not encrypted, and that is the deliberate half of the split
 * described on [ProviderSettings]: nothing in here is a secret, so nothing in
 * here needs Keystore. The key lives in [SecretStore] and reaches this file at
 * no point. `ProviderSettingsTenancyTest` asserts that by reading the raw
 * preference values back and failing if the key turns up in any of them, which
 * is the same shape of guard design section 9.3 puts on the shared corpus: an
 * invariant nobody can quietly regress because a test is watching the storage
 * rather than the intention.
 *
 * Fields are written one preference key each rather than as a serialized blob.
 * It costs a few lines here and buys a readable file when something is wrong,
 * a forward-compatible read (an unknown extra key is ignored, not a parse
 * failure), and no schema to migrate the first time a field is added.
 */
class ProviderSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val mutationLock = Any()

    /**
     * The stored configuration, or null when the user has not configured a
     * provider.
     *
     * Reads fail closed. A record whose `kind` is missing or is a value this
     * build does not know (a downgrade after a future version wrote it) is
     * reported as "nothing configured" rather than guessed at, because a
     * guessed provider sends the user's item names somewhere they did not pick.
     */
    fun load(): ProviderSettings? {
        val kindRaw = prefs.getString(KEY_KIND, null) ?: return null
        val kind = runCatching { ProviderKind.valueOf(kindRaw) }.getOrElse {
            Log.w(TAG, "unknown stored provider kind '$kindRaw', treating as unconfigured")
            return null
        }
        return ProviderSettings(
            kind = kind,
            label = prefs.getString(KEY_LABEL, null) ?: kind.name,
            baseUrl = prefs.getString(KEY_BASE_URL, null).orEmpty(),
            model = prefs.getString(KEY_MODEL, null).orEmpty(),
            isLocal = prefs.getBoolean(KEY_IS_LOCAL, false),
        )
    }

    /**
     * Replace the stored configuration.
     *
     * `commit()` for the same reason as [KeystoreSecretStore]: this runs from a
     * settings screen that is about to tell the user it saved, and it is not in
     * anyone's hot path.
     */
    fun save(settings: ProviderSettings) {
        synchronized(mutationLock) {
            val committed = prefs.edit()
                .putString(KEY_KIND, settings.kind.name)
                .putString(KEY_LABEL, settings.label)
                .putString(KEY_BASE_URL, settings.baseUrl)
                .putString(KEY_MODEL, settings.model)
                .putBoolean(KEY_IS_LOCAL, settings.isLocal)
                .commit()
            if (!committed) {
                Log.w(TAG, "saving provider settings returned false, nothing was written")
            }
        }
    }

    /**
     * Forget the configuration.
     *
     * This does not touch [SecretStore]. Removing a provider properly means
     * clearing both, and the caller does both explicitly rather than one store
     * reaching into the other, so that "the key is still there" is never a
     * surprise hidden behind an unrelated call.
     */
    fun clear() {
        synchronized(mutationLock) {
            val committed = prefs.edit().clear().commit()
            if (!committed) {
                Log.w(TAG, "clearing provider settings returned false")
            }
        }
    }

    internal companion object {
        private const val TAG = "ProviderSettingsStore"

        internal const val FILE_NAME = "provider_settings"

        internal const val KEY_KIND = "kind"
        internal const val KEY_LABEL = "label"
        internal const val KEY_BASE_URL = "base_url"
        internal const val KEY_MODEL = "model"
        internal const val KEY_IS_LOCAL = "is_local"
    }
}

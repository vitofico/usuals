package io.theficos.comanda.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The real [SecretStore]: the model API key at rest under a hardware-backed
 * key, the way quire stores calibre-web credentials.
 *
 * [EncryptedSharedPreferences] encrypts both the preference names and their
 * values with a [MasterKey] that lives in the Android Keystore, so the file on
 * disk is opaque and the key material that decrypts it never enters the app's
 * address space in a form a heap dump would give up. The alternative, a plain
 * `SharedPreferences` XML file, is readable by anything with root or an adb
 * backup, and "the key never leaves the device" is a much weaker claim when the
 * device is unlocked and shared.
 *
 * Writes use `commit()` rather than `apply()`. Storing a key is a user action
 * with a visible result ("your key is saved"), and reporting success before the
 * write has landed is how a settings screen ends up lying after a crash. It
 * happens on a settings screen, not in a loop, so the synchronous cost is not
 * worth optimising away.
 *
 * Note what is missing: no `TAG`-prefixed log line anywhere in this class
 * interpolates the key, and neither does the failure path. Invariant 5.
 */
class KeystoreSecretStore(context: Context) : SecretStore {

    // The application context, so a store built from an Activity does not pin
    // that Activity for the lifetime of the process.
    private val appContext = context.applicationContext

    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        FILE_NAME,
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Guards every mutation. Two callers racing a save against a clear can
     * otherwise interleave their editor transactions and leave the store
     * holding whichever half committed last, which for a secret is the worst
     * kind of surprise: a key that appears to be gone and is not.
     */
    private val mutationLock = Any()

    override fun putModelKey(key: String) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "model key must not be blank" }
        synchronized(mutationLock) {
            val committed = prefs.edit().putString(KEY_MODEL_KEY, trimmed).commit()
            if (!committed) {
                Log.w(TAG, "storing the model key returned false, nothing was written")
            }
        }
    }

    override fun modelKey(): String? =
        prefs.getString(KEY_MODEL_KEY, null)?.takeIf { it.isNotEmpty() }

    override fun clear() {
        synchronized(mutationLock) {
            val committed = prefs.edit().clear().commit()
            if (!committed) {
                Log.w(TAG, "clearing the secret store returned false, a key may still be present")
            }
        }
    }

    /**
     * Constant by construction. This is the line that makes invariant 5 hold
     * through every accidental string interpolation of a store instance, and
     * [io.theficos.comanda.auth.SecretStoreRedactionTest] fails if it ever
     * starts depending on what is stored.
     */
    override fun toString(): String = "KeystoreSecretStore(model key: ${SecretStore.REDACTED})"

    internal companion object {
        private const val TAG = "KeystoreSecretStore"

        /**
         * Deliberately its own file, separate from [ProviderSettingsStore]'s.
         * The split is the point: see [ProviderSettings].
         */
        internal const val FILE_NAME = "comanda_secrets"

        internal const val KEY_MODEL_KEY = "model_api_key"
    }
}

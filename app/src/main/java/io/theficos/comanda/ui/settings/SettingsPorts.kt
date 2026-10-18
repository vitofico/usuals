package io.theficos.comanda.ui.settings

import io.theficos.comanda.llm.ModelCapabilities
import io.theficos.comanda.auth.ProviderSettings

/**
 * Where the settings on this screen live.
 *
 * `:auth`'s `ProviderSettingsStore` already persists the provider half. This
 * port exists because the rest of the screen is not provider configuration and
 * does not belong in that file. The adapter in the app's wiring puts the provider
 * where `:auth` wants it and the rest beside it.
 *
 * There is no `readApiKey`. The key is written through [SecretStorePort] and
 * never read back into this layer, which is invariant 5 expressed as a missing
 * method rather than as a comment somebody can ignore.
 */
interface SettingsStore {
    /** Privacy gates must observe opt-out before asynchronous persistence finishes. */
    fun setScanLookupEnabled(enabled: Boolean) = Unit

    suspend fun load(): AppSettings

    suspend fun save(settings: AppSettings)
}

/**
 * The key, write-only.
 *
 * Deliberately narrower than `:auth`'s `SecretStore`, which can also hand the
 * plaintext back. This screen has no business holding it, so the method that
 * would let it is not on the interface it sees.
 */
interface SecretStorePort {
    suspend fun putModelKey(key: String)

    suspend fun clearModelKey()

    suspend fun hasModelKey(): Boolean
}

/**
 * Asks the configured endpoint what it can actually do.
 *
 * Bind to `:llm`'s `LlmClient.probe()`. It takes the settings being edited
 * rather than reading the saved ones, so "Probar conexión" tests what is on
 * screen and not what was saved an hour ago, and the adapter is what pairs
 * those settings with the stored key.
 */
fun interface ModelProbe {
    suspend fun probe(settings: ProviderSettings): ModelCapabilities
}

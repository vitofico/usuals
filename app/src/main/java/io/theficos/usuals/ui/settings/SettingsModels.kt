package io.theficos.usuals.ui.settings

import io.theficos.usuals.auth.ProviderKind
import io.theficos.usuals.auth.ProviderSettings

/**
 * Everything this screen can change, in one value.
 *
 * The API key is deliberately absent, and so is any way of reading it back.
 * It goes to the Android keystore through `:auth` and never enters a state
 * object, a log line, or an error report (invariant 5). What the UI is allowed
 * to know is whether one exists.
 */
data class AppSettings(
    val provider: ProviderSettings = ProviderSettings.anthropic(),
    /** quire's `ai_daily_budget`, where zero disables the model rather than lifting the limit. */
    val dailyCallCeiling: Int = DEFAULT_DAILY_CALL_CEILING,
    val scanLookupEnabled: Boolean = false,
    /** Off by default, and only offered while [scanLookupEnabled] is on. */
    val basketOverviewEnabled: Boolean = false,
    val automaticMatchingEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_DAILY_CALL_CEILING = 200
    }
}

/**
 * The endpoint defaults this screen offers per provider.
 *
 * The Ollama base URL is the emulator's route to the host machine's loopback,
 * with the OpenAI-compatible path prefix already on it: the client appends
 * `/chat/completions`, and Ollama serves that under `/v1`. On a physical phone
 * this has to become the LAN address of whatever runs Ollama, which is what the
 * hint under the field says.
 *
 * It differs from `ProviderSettings.DEFAULT_OLLAMA_BASE_URL` for those two
 * reasons, the emulator route and the `/v1` prefix, rather than by accident.
 */
object ProviderDefaults {

    const val OLLAMA_BASE_URL = "http://10.0.2.2:11434/v1"

    /** A local model that actually fits the job, named so the field is not a blank stare. */
    const val OLLAMA_EXAMPLE_MODEL = "qwen3.8:27b-mlx"

    /**
     * What a provider switch drops into the fields.
     *
     * Switching provider replaces them rather than keeping them, because a base
     * URL left over from one endpoint pointing at another is a support ticket
     * waiting to happen.
     */
    fun forKind(kind: ProviderKind): ProviderSettings = when (kind) {
        ProviderKind.ANTHROPIC -> ProviderSettings.anthropic()
        ProviderKind.OPENAI_COMPATIBLE -> ProviderSettings.openAiCompatible(baseUrl = "", model = "")
        ProviderKind.OLLAMA -> ProviderSettings.ollama(
            baseUrl = OLLAMA_BASE_URL,
            model = OLLAMA_EXAMPLE_MODEL,
        )
    }
}

data class AppInfo(
    val versionName: String = "",
    val versionCode: Int = 0,
    val licence: String = "Apache-2.0",
)

/**
 * A probe's lifecycle, so the button can be honest about not having run yet.
 *
 * [Broken] is separate from a result saying "no": a probe that threw tells you
 * nothing about the endpoint, and reporting it as an unreachable endpoint would
 * be guessing.
 */
/**
 * What the keystore says about the model key.
 *
 * Three states rather than a Boolean, for the same reason [ProbeState.Broken]
 * is not a result saying "no": a keystore that would not answer tells you
 * nothing about whether a key is stored, and rendering that silence as "no key"
 * is a guess the screen would be presenting as a fact.
 *
 * [UNAVAILABLE] is reachable in practice. `KeystoreSecretStore` opens
 * `AndroidKeyStore` and decrypts a master key, and that can throw on a device
 * whose keystore is locked, restricted by policy, or has lost the key behind a
 * credential reset. Before this existed the throw escaped into a coroutine and
 * took the app down from a settings screen.
 */
enum class KeyState {
    /** Asked, answered, nothing stored. */
    ABSENT,

    /** Asked, answered, a key is there. */
    PRESENT,

    /** The keystore would not answer, so neither of the above is known. */
    UNAVAILABLE,
}

sealed interface ProbeState<out T> {
    data object Idle : ProbeState<Nothing>

    data object Running : ProbeState<Nothing>

    data class Done<out T>(val result: T) : ProbeState<T>

    data object Broken : ProbeState<Nothing>
}

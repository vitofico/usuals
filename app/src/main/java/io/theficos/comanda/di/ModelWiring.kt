package io.theficos.comanda.di

import io.theficos.comanda.llm.LlmClient
import io.theficos.comanda.llm.ModelBudget
import io.theficos.comanda.llm.ModelCapabilities
import io.theficos.comanda.llm.ModelProblem
import io.theficos.comanda.llm.OpenAiCompatibleClient
import io.theficos.comanda.llm.ProviderConfig
import io.theficos.comanda.llm.ProviderKind as WireKind
import io.theficos.comanda.auth.ProviderKind as SettingKind
import io.theficos.comanda.auth.ProviderSettings
import io.theficos.comanda.auth.SecretStore
import io.theficos.comanda.ui.settings.ModelProbe
import io.theficos.comanda.ui.settings.SecretStorePort

// The three joins between what a person configures and what talks to a model.
//
// :auth stores the endpoint and the key, :llm speaks to the endpoint, and the
// settings screen edits the endpoint. None of the three knows about the others,
// which is the module boundary doing its job, so the joining happens here: a key
// port the screen can write through but never read from, a probe that tests what
// is on screen, and a client recipes get when, and only when, a provider is
// actually configured.
//
// Every one of them is built from functions rather than from objects. That is
// not indirection for its own sake: it is what lets the keystore stay unopened
// until something genuinely needs a key, and what makes the provider settings
// re-read rather than captured, so a person who changes an endpoint does not
// have to restart the app for it to take.

/**
 * The settings screen's write-only view of the model key.
 *
 * `:auth`'s [SecretStore] can also hand the plaintext back, and this cannot.
 * The narrowing is the point: the screen has no business holding the key, so
 * the method that would let it does not exist on the interface it sees.
 *
 * Invariant 5's second half, "never enters a log", is discharged by [toString].
 * It is a constant, it says nothing about what is stored, and a test fails if
 * that stops being true, because the way this invariant actually breaks is a
 * `Log.d("secrets=$port")` somebody adds months later while chasing something
 * else.
 *
 * @param store the real store, behind a function so that opening Keystore
 *   happens on the first call and not when the settings screen is constructed.
 *   Building a `KeystoreSecretStore` decrypts a master key; a person who opens
 *   settings to change a preference should not pay for that.
 */
class KeystoreSecretStorePort(private val store: () -> SecretStore) : SecretStorePort {

    /**
     * Blank input stores nothing and clears nothing.
     *
     * [SecretStore.putModelKey] refuses a blank key by throwing, and the screen
     * already treats an empty field as "no change", so the two agree about the
     * outcome and disagree about how loudly to say it. Turning an empty field
     * into a crash on a settings page is the wrong trade.
     */
    override suspend fun putModelKey(key: String) {
        if (key.isBlank()) return
        store().putModelKey(key)
    }

    override suspend fun clearModelKey() {
        store().clear()
    }

    override suspend fun hasModelKey(): Boolean = store().hasModelKey()

    override fun toString(): String = "KeystoreSecretStorePort(model key: ${SecretStore.REDACTED})"
}

/**
 * *Probar conexión*, wired to a real endpoint.
 *
 * It probes the settings it is handed rather than the settings that were saved,
 * which is the whole reason the port takes them as a parameter: a person
 * correcting a base URL wants to know whether the URL in front of them works,
 * not whether the one from an hour ago did.
 *
 * The key is paired in here rather than travelling with the settings, because
 * `:auth` deliberately keeps the two apart. This is the one place in the app
 * that puts them back together, and it does so into a [ProviderConfig], whose
 * `toString` redacts.
 *
 * @param apiKey the stored key, read at the moment a probe runs. A function so
 *   that a provider which cannot be probed at all never opens Keystore.
 * @param clients how a configuration becomes a client. Defaulted to the real
 *   one and injectable so a test can see which configuration a given set of
 *   settings produces without standing up a server.
 */
class OpenAiModelProbe(
    private val apiKey: () -> String?,
    private val budget: ModelBudget? = null,
    private val clients: (ProviderConfig) -> LlmClient = { OpenAiCompatibleClient.forConfig(it) },
) : ModelProbe {

    override suspend fun probe(settings: ProviderSettings): ModelCapabilities {
        val config = wireConfigOf(settings, apiKey)
            ?: return ModelCapabilities(
                reachable = false,
                supportsStructuredOutput = false,
                model = null,
                problem = unusableProblem(settings),
            )
        if (budget != null && !budget.tryConsume()) return ModelCapabilities(false, false, null,
            ModelProblem(ModelProblem.Kind.BUDGET_EXHAUSTED))
        // Never cached. Two probes of two different endpoints are two clients,
        // because a client holds the configuration it was built with and
        // reusing one would test the endpoint the person just moved away from.
        return clients(config).probe()
    }
}

/** Rebuilds the optional client when saved configuration changes. */
class ModelClients(
    private val settings: () -> ProviderSettings?,
    private val apiKey: () -> String?,
    private val build: (ProviderConfig) -> LlmClient = { OpenAiCompatibleClient.forConfig(it) },
) {

    fun current(): LlmClient? {
        val configured = settings() ?: return null
        val config = wireConfigOf(configured, apiKey) ?: return null
        return build(config)
    }
}

/**
 * Provider settings plus a key, as the thing `:llm` can actually dial, or
 * null when they do not describe a reachable endpoint.
 *
 * Null covers three states that are the same state from here: a provider whose
 * wire format this build cannot speak, a missing endpoint, and a missing model
 * name. `ProviderConfig` refuses the last two in its constructor, so screening
 * them here is what keeps a half-filled settings screen from throwing on the
 * way to parsing a recipe.
 *
 * [apiKey] is called last and only on the success path. Reading it is what
 * opens Keystore, and a provider that cannot be used should not cost that.
 */
internal fun wireConfigOf(settings: ProviderSettings, apiKey: () -> String?): ProviderConfig? {
    val kind = when (settings.kind) {
        // No Anthropic client exists in this build, only the OpenAI-compatible
        // one. Reporting that as "not configured" is truthful; quietly dialling
        // an OpenAI-shaped request at Anthropic would not be.
        SettingKind.ANTHROPIC -> null
        SettingKind.OPENAI_COMPATIBLE -> WireKind.OPENAI_COMPATIBLE
        SettingKind.OLLAMA -> WireKind.OLLAMA
    } ?: return null

    val baseUrl = settings.baseUrl.trim()
    val model = settings.model.trim()
    if (baseUrl.isEmpty() || model.isEmpty()) return null

    return ProviderConfig(
        kind = kind,
        label = settings.label,
        baseUrl = baseUrl,
        model = model,
        isLocal = settings.isLocal,
        // A key is optional and blank is not a key. Ollama on the LAN has none,
        // and sending an empty bearer token would be a lie about the request.
        apiKey = apiKey()?.takeIf { it.isNotBlank() }?.trim(),
    )
}

/** The app localizes this configuration problem alongside provider failures. */
internal fun unusableProblem(settings: ProviderSettings): ModelProblem = ModelProblem(when {
    settings.kind == SettingKind.ANTHROPIC ->
        ModelProblem.Kind.UNSUPPORTED_PROVIDER

    settings.baseUrl.isBlank() -> ModelProblem.Kind.MISSING_ENDPOINT

    settings.model.isBlank() -> ModelProblem.Kind.MISSING_MODEL

    else -> ModelProblem.Kind.INCOMPLETE_CONFIGURATION
})

package io.theficos.usuals.llm

import io.theficos.usuals.parse.ParsedItem

/** Which wire protocol a provider speaks. Adding one is configuration, not a fork. */
enum class ProviderKind {
    ANTHROPIC,
    OPENAI_COMPATIBLE,

    /**
     * Also OpenAI-compatible on the wire, but kept separate because it is the
     * configuration that makes design section 4.1's privacy claim true rather
     * than aspirational: a model on the home LAN means the recipes and item names
     * never leave the flat.
     */
    OLLAMA,
}

/**
 * What an endpoint can do right now, as opposed to what its documentation says.
 *
 * galley probes a provider and shows the result behind a Test connection
 * button, because two endpoints with the same wire format differ in what they
 * actually support. The one that matters here is [supportsStructuredOutput]:
 * an endpoint that cannot return a small JSON object cannot read a recipe, and
 * the person should learn that on the settings screen and not mid-recipe.
 */
data class ModelCapabilities(
    val reachable: Boolean,
    val supportsStructuredOutput: Boolean,
    val model: String?,
    val problem: ModelProblem? = null,
)

/**
 * Everything needed to talk to one provider.
 *
 * [apiKey] is the only secret this module ever holds, and invariant 5 says it
 * never reaches a log, an exception, or a crash report. That is enforced here
 * rather than at every call site: [toString] redacts it, and [redact] is the
 * funnel every message that might have touched it goes through before anyone
 * else sees it. A data class prints its fields by default, so overriding
 * [toString] is not decoration, it is the invariant.
 */
data class ProviderConfig(
    val kind: ProviderKind,
    val label: String,
    val baseUrl: String,
    val model: String,
    val isLocal: Boolean,
    val apiKey: String?,
) {

    init {
        require(baseUrl.isNotBlank()) { "a provider needs an endpoint" }
        require(model.isNotBlank()) { "a provider needs a model name" }
    }

    val hasKey: Boolean get() = !apiKey.isNullOrBlank()

    /** `{baseUrl}/chat/completions`, tolerating a trailing slash in the setting. */
    fun chatCompletionsUrl(): String = baseUrl.trimEnd('/') + "/chat/completions"

    /**
     * Scrubs the key out of anything about to be surfaced. Endpoints do echo
     * credentials back in error bodies, so this is not theoretical: the 401
     * body that explains what went wrong is exactly the string most likely to
     * contain the key.
     */
    fun redact(text: String): String {
        val key = apiKey
        return if (key.isNullOrBlank()) text else text.replace(key, REDACTED)
    }

    override fun toString(): String =
        "ProviderConfig(kind=$kind, label=$label, baseUrl=$baseUrl, model=$model, " +
            "isLocal=$isLocal, apiKey=${if (hasKey) REDACTED else "none"})"

    companion object {
        const val REDACTED = "(redacted)"

        /** The genuinely private configuration: a model on the LAN, no key, nothing billed. */
        fun ollama(model: String, baseUrl: String = "http://localhost:11434/v1"): ProviderConfig =
            ProviderConfig(
                kind = ProviderKind.OLLAMA,
                label = "Ollama",
                baseUrl = baseUrl,
                model = model,
                isLocal = true,
                apiKey = null,
            )
    }
}

/**
 * The only model interface the app knows about.
 *
 * The app depends on this and never on a provider, so adding Anthropic or a
 * local Ollama is a new implementation rather than a change to any feature, and
 * every test that involves a model runs against a scripted one on the JVM.
 *
 * Every method is allowed to be useless. An empty list or a provider that is
 * not configured at all is not an error: the model declining is a normal
 * answer, and every feature that asks has a path that works without it.
 */
interface LlmClient {

    /** What this endpoint can do today. Cheap, and safe to call before every run. */
    suspend fun probe(): ModelCapabilities

    /**
     * The one place a model is used: turning a pasted recipe or a dictated
     * paragraph into structured items. Never called for a simple utterance,
     * which the local parser handles for free, and never for a scan.
     */
    suspend fun parseUtterance(utterance: String): List<ParsedItem>
}

package io.theficos.comanda.auth

/**
 * Wire protocol selection for the optional recipe model. The app adapter reports
 * unsupported protocols explicitly rather than pretending configuration is support.
 */
enum class ProviderKind {
    /** Anthropic's own API. Haiku by default, per design section 9.1. */
    ANTHROPIC,

    /**
     * Anything that speaks the OpenAI chat-completions wire format at a
     * user-supplied base URL. This is the escape hatch that makes "bring your
     * own model" true rather than decorative, and it is also how a self-hosted
     * runtime that is not Ollama gets used.
     */
    OPENAI_COMPATIBLE,

    /**
     * Ollama on the local network. Kept distinct from [OPENAI_COMPATIBLE] even
     * though the wire format overlaps, because this is the configuration design
     * section 4.1 calls the genuinely private one, and the settings screen has
     * to be able to say so without inferring it from a URL.
     */
    OLLAMA,
}

/**
 * The model provider configuration, minus the key.
 *
 * The missing field is the design. The API key is not stored here and is not
 * stored beside this in the same file: it lives in [SecretStore], which is
 * backed by the Android Keystore, while this object goes into an ordinary
 * `SharedPreferences` blob. Three reasons, in descending order of how much
 * trouble they save:
 *
 * 1. **Logs.** This is a data class, so its `toString()` prints every field it
 *    has. Settings objects end up in debug screens, in crash text and in a
 *    `Log.d` somebody adds while chasing a wrong base URL. Invariant 5 says the
 *    key never enters a log, and the only durable way to guarantee that is for
 *    the key not to be in the object that gets printed.
 * 2. **Storage.** An ordinary preferences file is plain XML in the app sandbox,
 *    which an adb backup or a rooted device hands over intact. Encrypting the
 *    whole settings blob to protect one field would mean paying Keystore
 *    decryption to read a base URL, and would tempt someone to later add a
 *    fourth harmless-looking field to the encrypted blob and log it.
 * 3. **Lifecycle.** These two change for different reasons. Switching model or
 *    correcting a base URL should not make the user re-enter a key, and
 *    revoking a key should not lose the endpoint they spent ten minutes
 *    getting right.
 *
 * The cost of the split is one extra object at every call site that needs both,
 * which is exactly one: the code that builds an `LlmClient`.
 *
 * @param label What the settings screen calls this provider. User-editable,
 *   because "the box in the study" is a better name than a base URL.
 * @param baseUrl The endpoint root. Empty for [ProviderKind.ANTHROPIC], where
 *   the SDK's own default applies.
 * @param model The model identifier to send. Free text: a local runtime's model
 *   names are whatever the user pulled.
 * @param isLocal Whether traffic stays on the user's own network. This drives
 *   the privacy state in design section 4.1, so it is stored rather than
 *   derived from [kind]: an OpenAI-compatible endpoint pointed at a machine in
 *   the hallway is local, and an Ollama instance behind a tunnel is not. See
 *   [looksLocal] for the honest default to offer.
 */
data class ProviderSettings(
    val kind: ProviderKind,
    val label: String,
    val baseUrl: String,
    val model: String,
    val isLocal: Boolean,
) {

    companion object {

        /** Haiku, per design section 9.1. */
        const val DEFAULT_ANTHROPIC_MODEL: String = "claude-haiku-4-5"

        const val DEFAULT_OLLAMA_BASE_URL: String = "http://localhost:11434"

        /**
         * Anthropic with Haiku, the default the design names. The base URL is
         * empty because there is nothing for the user to choose.
         */
        fun anthropic(model: String = DEFAULT_ANTHROPIC_MODEL): ProviderSettings =
            ProviderSettings(
                kind = ProviderKind.ANTHROPIC,
                label = "Anthropic",
                baseUrl = "",
                model = model,
                isLocal = false,
            )

        /**
         * Ollama on the loopback or the home LAN. [isLocal] follows
         * [looksLocal] rather than being hardcoded true, because a user who
         * points this at a rented GPU should not be told their groceries stay
         * at home. Invariant 8: the claim and its edge live together.
         */
        fun ollama(
            baseUrl: String = DEFAULT_OLLAMA_BASE_URL,
            model: String = "",
            label: String = "Ollama",
        ): ProviderSettings = ProviderSettings(
            kind = ProviderKind.OLLAMA,
            label = label,
            baseUrl = baseUrl,
            model = model,
            isLocal = looksLocal(baseUrl),
        )

        fun openAiCompatible(
            baseUrl: String,
            model: String,
            label: String = "Custom endpoint",
            isLocal: Boolean = looksLocal(baseUrl),
        ): ProviderSettings = ProviderSettings(
            kind = ProviderKind.OPENAI_COMPATIBLE,
            label = label,
            baseUrl = baseUrl,
            model = model,
            isLocal = isLocal,
        )

        /**
         * Whether [baseUrl] points somewhere that never leaves the user's own
         * network: loopback, an RFC 1918 range, or an mDNS `.local` name.
         *
         * This is a default for the settings toggle, not a security control. It
         * exists so the privacy state in design section 4.1 starts out honest
         * without the user having to reason about address ranges, and so a
         * hosted endpoint does not get labelled private by a hopeful default.
         * The user can still override it, because a VPN or a hosts file can
         * make either answer wrong and only they know which.
         */
        fun looksLocal(baseUrl: String): Boolean {
            val host = hostOf(baseUrl) ?: return false
            if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
            if (host.endsWith(".local") || host.endsWith(".localhost")) return true
            if (host.startsWith("10.") || host.startsWith("192.168.")) return true
            // 172.16.0.0/12, which is the one range people get wrong by hand.
            val octets = host.split('.')
            if (octets.size == 4 && octets[0] == "172") {
                val second = octets[1].toIntOrNull()
                if (second != null && second in 16..31) return true
            }
            return false
        }

        /**
         * Host part of [baseUrl], lowercased, or null when there is nothing
         * that looks like one.
         *
         * Hand-rolled rather than `java.net.URI` because the field is text a
         * user typed: "localhost:11434" with no scheme is a thing people write,
         * and `URI` reports no host for it. `lowercase()` with no locale is
         * deliberate, it is the locale-independent form, so a phone set to
         * Turkish does not turn "LOCALHOST" into something with a dotless i.
         */
        private fun hostOf(baseUrl: String): String? {
            val withoutScheme = baseUrl.trim().substringAfter("://", baseUrl.trim())
            val authority = withoutScheme.substringBefore('/').substringBefore('?')
            val afterUserInfo = authority.substringAfterLast('@')
            val host = if (afterUserInfo.startsWith("[")) {
                afterUserInfo.substringAfter('[').substringBefore(']')
            } else {
                afterUserInfo.substringBefore(':')
            }
            return host.lowercase().takeIf { it.isNotEmpty() }
        }
    }
}

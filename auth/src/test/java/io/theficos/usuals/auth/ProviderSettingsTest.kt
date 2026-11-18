package io.theficos.usuals.auth

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class ProviderSettingsTest {

    /**
     * The privacy state in design section 4.1 is only honest if this table is.
     * Every "local" row is somewhere a packet physically cannot leave the
     * user's network, and every "not local" row is somewhere it does, including
     * the ones that look homely.
     */
    private val localityCases: List<Pair<String, Boolean>> = listOf(
        // Loopback, in the spellings people actually type.
        "http://localhost:11434" to true,
        "localhost:11434" to true,
        "http://127.0.0.1:11434/v1" to true,
        "http://[::1]:11434" to true,
        "HTTP://LocalHost:11434" to true,

        // Private ranges.
        "http://192.168.1.42:11434" to true,
        "http://10.0.0.7:8080/v1" to true,
        "http://172.16.0.5:11434" to true,
        "http://172.31.255.254:11434" to true,

        // mDNS, which is how a box on the home network is usually reached.
        "http://ollama.local:11434" to true,
        "http://nas.local" to true,

        // Public, including the near-misses in and around 172.16.0.0/12.
        "http://172.15.0.1:11434" to false,
        "http://172.32.0.1:11434" to false,
        "http://172.5.0.1:11434" to false,
        "https://api.anthropic.com" to false,
        "https://ollama.example.com:11434/v1" to false,
        "https://localhost.example.com" to false,
        "http://100.64.0.1" to false,
        "http://user@192.168.0.9:11434/v1/chat" to true,
        "https://user@api.example.com/v1" to false,
        "" to false,
        "   " to false,
    )

    @Test
    fun `looksLocal recognises networks a packet cannot leave`() {
        for ((baseUrl, expected) in localityCases) {
            assertWithMessage("looksLocal(\"$baseUrl\")")
                .that(ProviderSettings.looksLocal(baseUrl))
                .isEqualTo(expected)
        }
    }

    @Test
    fun `provider defaults preserve model and locality choices`() {
        val cases = listOf(
            ProviderSettings.anthropic() to ProviderSettings(
                ProviderKind.ANTHROPIC, "Anthropic", "", ProviderSettings.DEFAULT_ANTHROPIC_MODEL, false,
            ),
            ProviderSettings.ollama() to ProviderSettings(
                ProviderKind.OLLAMA, "Ollama", ProviderSettings.DEFAULT_OLLAMA_BASE_URL, "", true,
            ),
            ProviderSettings.ollama("https://ollama.example.com") to ProviderSettings(
                ProviderKind.OLLAMA, "Ollama", "https://ollama.example.com", "", false,
            ),
            ProviderSettings.openAiCompatible("http://192.168.1.20:8080/v1", "qwen2.5:7b") to ProviderSettings(
                ProviderKind.OPENAI_COMPATIBLE, "Custom endpoint", "http://192.168.1.20:8080/v1", "qwen2.5:7b", true,
            ),
            ProviderSettings.openAiCompatible("https://gateway.example.com/v1", "llama3.1", isLocal = true) to ProviderSettings(
                ProviderKind.OPENAI_COMPATIBLE, "Custom endpoint", "https://gateway.example.com/v1", "llama3.1", true,
            ),
        )
        for ((actual, expected) in cases) {
            assertWithMessage(expected.baseUrl).that(actual).isEqualTo(expected)
        }
    }

    /**
     * Structural half of invariant 5: there is no field here that could hold a
     * key, so there is nothing for the generated `toString()` to leak. If
     * someone adds one, this fails.
     */
    @Test
    fun `settings carry no secret to print`() {
        val settings = ProviderSettings.openAiCompatible(
            baseUrl = "https://gateway.example.com/v1",
            model = "llama3.1",
            label = "The box in the study",
        )

        val fieldNames = ProviderSettings::class.java.declaredFields
            .map { it.name.lowercase() }
            .filterNot { it.startsWith("\$") }

        for (name in fieldNames) {
            assertWithMessage("ProviderSettings gained a field named '$name'")
                .that(name)
                .isNoneOf("key", "apikey", "token", "secret", "password", "credential")
        }
        assertThat(settings.toString()).contains("The box in the study")
    }
}

package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.llm.ProviderKind
import org.junit.Assert.assertThrows
import org.junit.Test

class AiConfigurationTest {
    @Test fun providerKindsLoadFromProfilesAndReadTheConfiguredEnvironmentKey() {
        for (kind in listOf(ProviderKind.ANTHROPIC, ProviderKind.OLLAMA, ProviderKind.OPENAI_COMPATIBLE)) {
            val config = AiConfiguration.fromEnvironment(mapOf("MODEL_KEY" to "synthetic-provider-key", "USUALS_AI_PROFILES" to """
                [{"id":"model","label":"Model","baseUrl":"https://models.example/v1",
                  "model":"text","imageModel":"vision","kind":"${kind.name}","keyEnv":"MODEL_KEY"}]
            """.trimIndent()))
            val profile = config.profiles.single()
            assertThat(ProviderKind.valueOf(profile.kind)).isEqualTo(kind)
            assertThat(profile.model).isEqualTo("text")
            assertThat(profile.imageModel).isEqualTo("vision")
            assertThat(config.key(profile)).isEqualTo("synthetic-provider-key")
        }
    }

    @Test fun unknownProviderKindsAreRejected() {
        val profile = AiProvider("model", "Model", "https://models.example/v1", "text", kind = "UNKNOWN")
        assertThrows(IllegalArgumentException::class.java) { AiConfiguration(listOf(profile)) }
    }

    @Test fun internalCloudProxyRetainsExternalProcessingDisclosure() {
        val config = AiConfiguration.fromEnvironment(mapOf("USUALS_AI_PROFILES" to """
            [{"id":"cluster","label":"Ollama cloud","baseUrl":"http://ollama.ollama.svc.cluster.local:11434/v1",
              "model":"cloud-model","kind":"OLLAMA","isLocal":false,"allowHttp":true}]
        """.trimIndent()))
        assertThat(config.profiles.single().view().isLocal).isFalse()
        assertThat(config.profiles.single().view().baseUrl).isEqualTo("http://ollama.ollama.svc.cluster.local:11434/v1")
    }

    @Test fun cloudDestinationsRequireHttpsUnlessOperatorExplicitlyAllowsHttp() {
        val profile = AiProvider("cloud", "Cloud", "http://models.example/v1", "model")
        assertThrows(IllegalArgumentException::class.java) { AiConfiguration(listOf(profile)) }
        AiConfiguration(listOf(profile.copy(baseUrl = "https://models.example/v1")))
        AiConfiguration(listOf(profile.copy(isLocal = true)))
        assertThrows(IllegalArgumentException::class.java) { AiConfiguration(listOf(profile.copy(baseUrl = "ftp://models.example/v1", allowHttp = true))) }
    }
}

package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AiConfigurationTest {
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

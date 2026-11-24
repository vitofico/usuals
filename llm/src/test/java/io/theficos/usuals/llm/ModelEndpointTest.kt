package io.theficos.usuals.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelEndpointTest {
    @Test fun localHttpRequiresConsentAndRejectsKeys() {
        for (url in listOf("http://localhost:11434/v1", "http://192.168.1.4", "http://[::1]", "http://ollama.local")) {
            assertThat(ModelEndpoint.permits(url, false, false)).isFalse()
            assertThat(ModelEndpoint.permits(url, true, true)).isFalse()
            assertThat(ModelEndpoint.permits(url, true, false)).isTrue()
        }
        assertThat(ModelEndpoint.permits("https://models.example/v1", false, true)).isTrue()
    }

    @Test fun consentCannotAuthorizePublicOrMalformedHttpEndpoints() {
        for (url in listOf("http://example.org", "http://10.0.0.1.example", "http://192.168.1.256",
            "http://user@localhost", "http://localhost:99999", "http://localhost/?key=x", "ftp://localhost")) {
            assertThat(ModelEndpoint.permits(url, true, false)).isFalse()
        }
    }
}

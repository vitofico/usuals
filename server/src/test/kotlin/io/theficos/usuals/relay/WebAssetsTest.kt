package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.junit.Test

class WebAssetsTest {
    @Test fun onlyExplicitModelOriginsEnterTheCspAndNoProviderCredentialsArePublished() = testApplication {
        val config = BrowserModels.fromEnvironment(mapOf("USUALS_WEB_MODEL_ORIGINS" to "[\"https://models.example\"]"))
        application { routing { webAssets(config) } }
        assertThat(client.get("/").headers["Content-Security-Policy"]).contains("connect-src 'self' https://models.example;")
        assertThat(client.get("/").headers["Content-Security-Policy"]).contains("img-src 'self' blob:")
        assertThat(client.get("/model-origins.json").bodyAsText()).isEqualTo("{\"origins\":[\"https://models.example\"]}")
    }
    @Test fun modelOriginsRejectHeaderInjectionCredentialsPathsAndUnencryptedRemoteProviders() {
        assertThat(BrowserModels.fromEnvironment(emptyMap()).origins).isEmpty()
        assertThat(BrowserModels(listOf("http://localhost:18748")).origins).containsExactly("http://localhost:18748")
        for (value in listOf("https://model.example;script-src *", "https://model.example\n", "https://user:password@model.example", "https://model.example/v1",
            "http://model.example", "https://model.example?key=secret", "https://model.example#fragment", "https://model.example:", "https://model.example:0", "https://model.example:99999")) {
            var failed = false
            try { BrowserModels(listOf(value)) } catch (_: IllegalArgumentException) { failed = true }
            com.google.common.truth.Truth.assertWithMessage(value).that(failed).isTrue()
        }
    }
    @Test fun packagedBrowserHasRestrictiveHeadersAndNoRemoteDependencies() = testApplication {
        application { routing { webAssets() } }
        val index = client.get("/")
        assertThat(index.status.value).isEqualTo(200)
        assertThat(index.bodyAsText()).contains("<title>Usuals</title>")
        assertThat(index.bodyAsText()).doesNotContain("https://")
        assertThat(index.headers["Content-Security-Policy"]).contains("script-src 'self'")
        assertThat(index.headers["Content-Security-Policy"]).doesNotContain("unsafe-inline")
        assertThat(index.headers["Content-Security-Policy"]).contains("frame-ancestors 'none'")
        assertThat(index.headers["Content-Security-Policy"]).contains("media-src 'self' blob:")
        assertThat(index.headers["Permissions-Policy"]).isEqualTo("camera=(self), microphone=(), geolocation=(), payment=()")
        assertThat(index.headers["Referrer-Policy"]).isEqualTo("no-referrer")
        assertThat(index.headers["Cache-Control"]).isEqualTo("no-store")
        assertThat(index.headers["X-Content-Type-Options"]).isEqualTo("nosniff")
        val script = client.get("/src/wire.mjs")
        assertThat(script.status.value).isEqualTo(200)
        assertThat(script.headers["Content-Type"]).startsWith("text/javascript")
        assertThat(client.get("/assets/manrope.ttf").status.value).isEqualTo(200)
        assertThat(client.get("/assets/manrope-OFL.txt").bodyAsText()).contains("SIL OPEN FONT LICENSE")
        for (path in listOf("/src/qr.mjs", "/src/vendor/qrcodegen.mjs", "/src/vendor/jsqr.mjs")) {
            val asset = client.get(path)
            assertThat(asset.status.value).isEqualTo(200)
            assertThat(asset.headers["Content-Type"]).startsWith("text/javascript")
        }
        assertThat(client.get("/assets/jsqr-LICENSE.txt").bodyAsText()).contains("Apache License")
    }
    @Test fun assetsCannotExposeServerResourcesTestsOrApiFallbacks() = testApplication {
        application { routing { webAssets() } }
        assertThat(client.get("/join").status.value).isEqualTo(200)
        for (path in listOf("/db/001_relay.sql", "/src/../../db/001_relay.sql", "/src/%2e%2e/db/001_relay.sql", "/test/interop.test.mjs", "/PROTOCOL.md", "/v1/unknown", "/api/me", "/assets/missing.svg")) {
            val response = client.get(path)
            assertThat(response.status.value).isEqualTo(404)
            assertThat(response.bodyAsText()).doesNotContain("<title>Usuals</title>")
        }
    }
}

package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import org.junit.Test

class WebAssetsTest {
    @Test fun packagedBrowserHasRestrictiveHeadersAndNoRemoteDependencies() = testApplication {
        application { routing { webAssets() } }
        val index = client.get("/")
        assertThat(index.status.value).isEqualTo(200)
        assertThat(index.bodyAsText()).contains("<title>Usuals</title>")
        assertThat(index.bodyAsText()).doesNotContain("https://")
        assertThat(index.headers["Content-Security-Policy"]).contains("script-src 'self'")
        assertThat(index.headers["Content-Security-Policy"]).doesNotContain("unsafe-inline")
        assertThat(index.headers["Content-Security-Policy"]).contains("frame-ancestors 'none'")
        assertThat(index.headers["Referrer-Policy"]).isEqualTo("no-referrer")
        assertThat(index.headers["Cache-Control"]).isEqualTo("no-store")
        assertThat(index.headers["X-Content-Type-Options"]).isEqualTo("nosniff")
        val script = client.get("/src/wire.mjs")
        assertThat(script.status.value).isEqualTo(200)
        assertThat(script.headers["Content-Type"]).startsWith("text/javascript")
        assertThat(client.get("/assets/manrope.ttf").status.value).isEqualTo(200)
        assertThat(client.get("/assets/manrope-OFL.txt").bodyAsText()).contains("SIL OPEN FONT LICENSE")
    }
    @Test fun assetsCannotExposeServerResourcesTestsOrApiFallbacks() = testApplication {
        application { routing { webAssets() } }
        for (path in listOf("/db/001_relay.sql", "/src/../../db/001_relay.sql", "/src/%2e%2e/db/001_relay.sql", "/test/interop.test.mjs", "/PROTOCOL.md", "/v1/unknown", "/api/me", "/assets/missing.svg")) {
            val response = client.get(path)
            assertThat(response.status.value).isEqualTo(404)
            assertThat(response.bodyAsText()).doesNotContain("<title>Usuals</title>")
        }
    }
}

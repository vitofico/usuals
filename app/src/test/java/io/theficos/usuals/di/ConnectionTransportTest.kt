package io.theficos.usuals.di

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.util.concurrent.TimeUnit

class ConnectionTransportTest {
    @Test fun emptyLogoutPostReachesTheServerWithItsAuthorization(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"approved\":false}"))
            val response = OkHttpRelayTransport(true).request(server.url("/").toString().trimEnd('/'),
                "POST", "/v1/connection/logout", token = "synthetic-token")
            assertThat(response.status).isEqualTo(200)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertThat(request.method).isEqualTo("POST")
            assertThat(request.path).isEqualTo("/v1/connection/logout")
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer synthetic-token")
        }
    }
}

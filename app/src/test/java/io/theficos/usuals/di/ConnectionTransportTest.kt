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

    @Test fun listRequestsCarryTheCredentialAndServerCodesSurviveAsProblems(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{\"cursor\":\"2.7\",\"reset\":true,\"hasMore\":false,\"lists\":[],\"removed\":[]}"))
            server.enqueue(MockResponse().setResponseCode(409).setBody("{\"code\":\"list_exists\"}"))
            val origin = server.url("/").toString().trimEnd('/')
            val saved = ServerConnection(origin, InstanceInfo(name = "Usuals", server = ServerInfo(Wire.hash(byteArrayOf(1)), Wire.hash(byteArrayOf(2))),
                accounts = true, ai = false), "me", "synthetic-token", Long.MAX_VALUE)
            val lists = HttpListsService(OkHttpRelayTransport(true)) { saved }
            assertThat(lists.changes("1.5 &x", wait = 25)).isEqualTo(ListChanges("2.7", reset = true, hasMore = false, lists = emptyList(), removed = emptyList()))
            val feed = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertThat(feed.path).isEqualTo("/api/lists/changes?cursor=1.5%20%26x&limit=50&wait=25")
            assertThat(feed.getHeader("Authorization")).isEqualTo("Bearer synthetic-token")
            val problem = runCatching { lists.create("list-1", Projection(name = "Home")) }.exceptionOrNull() as RelayProblem
            assertThat(problem.code to problem.status).isEqualTo("list_exists" to 409)
            val put = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertThat(put.method to put.path).isEqualTo("PUT" to "/api/lists/list-1")
            assertThat(put.body.readUtf8()).contains("\"name\":\"Home\"")
            val signedOut = runCatching { HttpListsService(OkHttpRelayTransport(true)) { null }.people("") }.exceptionOrNull() as RelayProblem
            assertThat(signedOut.status).isEqualTo(401)
        }
    }
}

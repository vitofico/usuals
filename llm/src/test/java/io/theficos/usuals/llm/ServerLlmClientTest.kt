package io.theficos.usuals.llm

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.AiItem
import io.theficos.usuals.sync.AiReply
import io.theficos.usuals.sync.ClientState
import io.theficos.usuals.sync.ConnectionClient
import io.theficos.usuals.sync.InstanceInfo
import io.theficos.usuals.sync.RelayTransport
import io.theficos.usuals.sync.ServerConnection
import io.theficos.usuals.sync.ServerInfo
import io.theficos.usuals.sync.SyncStateStore
import io.theficos.usuals.sync.Wire
import io.theficos.usuals.sync.WireResponse
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ServerLlmClientTest {
    @Test fun `relay errors and a response with too many items use the client contract`() = runTest {
        val receiptKey = ByteArray(32)
        val identity = ServerInfo(Wire.hash(receiptKey), Wire.encode(receiptKey))
        val info = InstanceInfo(name = "Test", server = identity, accounts = true, ai = true)
        val saved = ServerConnection("https://usuals.example", info, "alice", "test-token", 20000,
            aiEnabled = true, aiRevision = "accepted")
        var state = ClientState(connection = saved)
        val store = object : SyncStateStore {
            override fun read() = state
            override fun update(change: (ClientState) -> ClientState) = change(state).also { state = it }
        }
        lateinit var response: WireResponse
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                assertThat(path).isEqualTo("/v1/ai/text")
                assertThat(token).isEqualTo(saved.token)
                return response
            }
        }
        val relay = ServerLlmClient(ConnectionClient(store, transport) { 1000 })
        for ((status, code, expected) in listOf(
            Triple(429, "ai_budget", ModelProblem.Kind.BUDGET_EXHAUSTED),
            Triple(403, "forbidden", ModelProblem.Kind.KEY_REJECTED),
        )) {
            response = WireResponse(status, """{"code":"$code"}""".toByteArray())
            val failure = runCatching { relay.parseUtterance("milk") }.exceptionOrNull()
            assertThat(failure).isInstanceOf(ModelCallException::class.java)
            assertThat((failure as ModelCallException).problem.kind).isEqualTo(expected)
        }
        val items = List(51) { AiItem("item ${it + 1}") }
        response = WireResponse(200, Wire.json.encodeToString(AiReply.serializer(), AiReply(items = items)).toByteArray())
        assertThat(runCatching { relay.parseUtterance("milk") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}

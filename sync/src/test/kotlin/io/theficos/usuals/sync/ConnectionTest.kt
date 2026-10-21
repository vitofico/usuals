package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.*
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Test
import org.junit.Assert.assertThrows

class ConnectionTest {
    private class Store : SyncStateStore {
        var state = ClientState()
        // The Keystore store deserializes on every read, including the read inside an update.
        override fun read() = ClientState.decode(Wire.json.encodeToString(ClientState.serializer(), state))
        override fun update(change: (ClientState) -> ClientState) = change(read()).also { state = it }
    }
    @Test fun chosenOriginIsStrictAndConnectionLinksCarryNoCredentials() {
        assertThat(serverOrigin("https://usuals.example/connect")).isEqualTo("https://usuals.example")
        assertThat(serverOrigin("usuals://connect#" + Wire.encode("https://usuals.example".toByteArray()))).isEqualTo("https://usuals.example")
        for (bad in listOf("http://usuals.example", "https://user:key@usuals.example", "https://usuals.example/?token=secret", "https://usuals.example#secret", "https://usuals.example/api"))
            assertThrows(IllegalArgumentException::class.java) { serverOrigin(bad) }
    }
    @Test fun approvalResumesWithTheSameVerifier() = runBlocking {
        val store = Store(); var approved = false; var verifierHash = ""
        val signing = SyncCrypto.device().publicKeys.signingPublicKey
        val info = InstanceInfo(name = "Private", server = ServerInfo(Wire.hash(Wire.decode(signing, 32)), signing), accounts = true, ai = true)
        val ticket = ConnectTicket("00000000-0000-4000-8000-000000000001", Wire.encode(ByteArray(32) { 1 }), 10000)
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                assertThat(origin).isEqualTo("https://usuals.example")
                val result = when (path) {
                    "/v1/instance" -> Wire.json.encodeToString(InstanceInfo.serializer(), info)
                    "/v1/connect" -> { val start = Wire.json.decodeFromJsonElement<ConnectStart>(Wire.strictJson(requireNotNull(body), 4096)); verifierHash = start.verifierHash; Wire.json.encodeToString(ConnectTicket.serializer(), ticket) }
                    else -> {
                        val finish = Wire.json.decodeFromJsonElement<ConnectFinish>(Wire.strictJson(requireNotNull(body), 4096))
                        assertThat(Wire.hash(Wire.decode(finish.verifier, 32))).isEqualTo(verifierHash)
                        Wire.json.encodeToString(ConnectResult.serializer(), ConnectResult(approved, if (approved) "account" else null, if (approved) 20000 else null))
                    }
                }
                return WireResponse(200, result.toByteArray())
            }
        }
        val client = ConnectionClient(store, transport) { 1000 }
        val pending = client.begin("https://usuals.example/connect")
        assertThat(pending.toString()).doesNotContain(pending.verifier)
        assertThat(pending.device.toString()).doesNotContain(pending.device.signingSeed)
        assertThat(client.finish()).isNull()
        approved = true
        val restored = ConnectionClient(store, transport) { 1000 }
        val connected = requireNotNull(restored.finish())
        assertThat(connected.token).isEqualTo(connectionToken(ticket.id, pending.verifier, ticket.pollToken))
        assertThat(store.state.connecting).isNull()
        assertThat(connected.aiEnabled).isFalse()
        restored.enableAi(true, "accepted-revision")
        assertThat(restored.current()?.aiRevision).isEqualTo("accepted-revision")
    }
    @Test fun aScannedConnectCodeClaimsItsOffer() = runBlocking {
        val store = Store(); var claim: OfferClaim? = null
        val signing = SyncCrypto.device().publicKeys.signingPublicKey
        val info = InstanceInfo(name = "Private", server = ServerInfo(Wire.hash(Wire.decode(signing, 32)), signing), accounts = true, ai = true)
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                assertThat(origin).isEqualTo("https://usuals.example")
                val result = when (path) {
                    "/v1/instance" -> Wire.json.encodeToString(InstanceInfo.serializer(), info)
                    else -> { claim = Wire.json.decodeFromJsonElement<ConnectStart>(Wire.strictJson(requireNotNull(body), 4096)).offer
                        Wire.json.encodeToString(ConnectTicket.serializer(), ConnectTicket("00000000-0000-4000-8000-000000000001", Wire.encode(ByteArray(32) { 1 }), 10000)) }
                }
                return WireResponse(200, result.toByteArray())
            }
        }
        val link = "https://usuals.example/connect#1." + Wire.encode(ByteArray(16) { 2 } + ByteArray(32) { 3 })
        val pending = ConnectionClient(store, transport) { 1000 }.begin(link)
        assertThat(claim).isEqualTo(ConnectCode.parse(link).claim())
        assertThat(pending.offered).isTrue()
        assertThat(store.read().connecting?.offered).isTrue()
        val plain = ConnectionClient(Store(), transport) { 1000 }.begin("https://usuals.example/connect")
        assertThat(claim).isNull()
        assertThat(plain.offered).isFalse()
    }
    private fun pending(): PendingConnection {
        val signing = SyncCrypto.device().publicKeys.signingPublicKey
        val info = InstanceInfo(name = "Private", server = ServerInfo(Wire.hash(Wire.decode(signing, 32)), signing), accounts = true, ai = true)
        return PendingConnection("https://usuals.example", info,
            ConnectTicket("00000000-0000-4000-8000-000000000001", Wire.encode(ByteArray(32) { 1 }), 10000),
            Wire.encode(ByteArray(32) { 2 }), SyncCrypto.device())
    }
    @Test fun cancelledOrReplacedPollCannotRestoreItsGrant() = runBlocking {
        for (replace in listOf(false, true)) {
            val store = Store(); val original = pending(); store.state = ClientState(connecting = original)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var revoked = 0
            val transport = object : RelayTransport {
                override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                    if (path.endsWith("/logout")) { revoked++; assertThat(token).isEqualTo(connectionToken(original.ticket.id, original.verifier, original.ticket.pollToken)) }
                    else { entered.complete(Unit); release.await() }
                    return WireResponse(200, Wire.json.encodeToString(ConnectResult.serializer(), ConnectResult(!path.endsWith("/logout"), "account", 20000)).toByteArray())
                }
            }
            val client = ConnectionClient(store, transport) { 1000 }
            val poll = async { client.finish() }; entered.await(); client.cancel()
            val replacement = original.copy(ticket = original.ticket.copy(id = "00000000-0000-4000-8000-000000000002"))
            if (replace) store.update { it.copy(connecting = replacement) }
            release.complete(Unit)
            assertThat(poll.await()).isNull()
            assertThat(client.current()).isNull()
            assertThat(revoked).isEqualTo(1)
            assertThat(client.pending()?.ticket).isEqualTo(if (replace) replacement.ticket else null)
        }
    }
    @Test fun concurrentPollsPreserveTheAlreadyAcceptedGrant() = runBlocking {
        val store = Store(); store.state = ClientState(connecting = pending())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var polls = 0; var revoked = 0
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                if (path.endsWith("/logout")) revoked++ else { polls++; if (polls == 2) entered.complete(Unit); release.await() }
                return WireResponse(200, Wire.json.encodeToString(ConnectResult.serializer(), ConnectResult(true, "account", 20000)).toByteArray())
            }
        }
        val client = ConnectionClient(store, transport) { 1000 }
        val first = async { client.finish() }; val second = async { client.finish() }
        entered.await(); release.complete(Unit)
        assertThat(first.await()).isEqualTo(second.await())
        assertThat(client.current()).isNotNull(); assertThat(revoked).isEqualTo(0)
    }
    @Test fun expectedConnectionCannotSendToAReplacementServer() = runBlocking {
        val store = Store()
        val saved = ServerConnection("https://usuals.example", pending().info, "user", "synthetic-token", 20000, aiEnabled = true)
        store.state = ClientState(connection = saved.copy(origin = "https://replacement.example"))
        var calls = 0
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse { calls++; error("must not send") }
        }
        val client = ConnectionClient(store, transport) { 1000 }
        val failure = runCatching { client.requestBytes("POST", "/v1/ai/text", expected = saved) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(calls).isEqualTo(0)
    }

    @Test fun stateSavedWhileHouseholdsExistedKeepsItsConnection() {
        val connection = ServerConnection("https://usuals.example", pending().info, "user", "synthetic-token", 20000)
        val saved = """{"households":{},"pendingShares":{},"invites":{},"sharingSource":"list","connection":""" +
            Wire.json.encodeToString(ServerConnection.serializer(), connection) + "}"
        val state = ClientState.decode(saved)
        assertThat(state.connection).isEqualTo(connection)
        assertThat(Wire.json.encodeToString(ClientState.serializer(), state)).doesNotContain("households")
    }

    @Test fun aCredentialPastItsStoredExpiryStillReachesTheServer() = runBlocking {
        // The server slides the credential on every use, so only its 401 says it has expired.
        val store = Store()
        store.state = ClientState(connection = ServerConnection("https://usuals.example", pending().info, "user", "synthetic-token", 20000))
        var sent: String? = null
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                sent = token; return WireResponse(200, "{}".toByteArray())
            }
        }
        ConnectionClient(store, transport) { 30000 }.requestBytes("GET", "/v1/ai/settings")
        assertThat(sent).isEqualTo("synthetic-token")
    }
}

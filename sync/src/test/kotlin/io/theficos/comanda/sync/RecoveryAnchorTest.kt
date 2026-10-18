package io.theficos.comanda.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.util.UUID
import org.junit.Test

class RecoveryAnchorTest {
    private fun uuid() = UUID.randomUUID().toString()
    @Test fun higherValidForkCannotDiscardACachedMembershipPinAndPositiveExtensionCanRecover() = runBlocking {
        val receipt = SyncCrypto.device()
        val server = ServerInfo(Wire.hash(Wire.decode(receipt.publicKeys.signingPublicKey)), receipt.publicKeys.signingPublicKey)
        val setup = RecoveryKits.prepare("https://relay.example", server)
        val genesis = ManifestProof(setup.genesis.manifestBytes, setup.genesis.signature)
        val household = setup.genesis.body().householdId
        fun signed(previous: ManifestProof, action: String, members: List<RosterMember> = previous.signed().body().members,
            joins: Map<String, String> = emptyMap()): ManifestProof {
            val body = previous.signed().body().copy(epoch = previous.signed().body().epoch + 1, previousHash = previous.signed().hash(),
                genesisHash = setup.genesis.hash(), action = action, members = members, approvedJoins = joins)
            val draft = SignedManifest(Wire.encode(Wire.json.encodeToString(body).toByteArray()), Wire.encode(ByteArray(64)), emptyList(), emptyList())
            val full = draft.copy(signature = SyncCrypto.sign(setup.device, draft.signingBytes()))
            return ManifestProof(full.manifestBytes, full.signature)
        }
        val editor = RosterMember(uuid(), "editor", listOf(SyncCrypto.device().publicKeys))
        val joined = signed(genesis, "approve_join", genesis.signed().body().members + editor, mapOf(uuid() to Wire.hash(byteArrayOf(1))))
        val cached = signed(joined, "remove", genesis.signed().body().members)
        val alternative = signed(joined, "rotate")
        val forkHead = signed(alternative, "rotate")
        val positiveHead = signed(cached, "rotate")
        var returned = listOf(genesis, joined, alternative, forkHead)
        var writes = 0
        val calls = mutableListOf<String>()
        var state = ClientState(households = mapOf(household to SavedHousehold(setup.origin, server, household, genesis, cached, setup.device, previous = joined)))
        val store = object : SyncStateStore {
            override fun read() = state
            override fun update(change: (ClientState) -> ClientState): ClientState { writes++; state = change(state); return state }
            override fun reserve(scope: String) { }
            override fun saveBlob(id: String, bytes: ByteArray) { error("no lists") }
            override fun readBlob(id: String): ByteArray? = null
            override fun removeBlob(id: String) { }
        }
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                calls += "$method $path"
                val bytes = when {
                    path == "/v1/server-info" -> Wire.json.encodeToString(server)
                    path == "/v1/devices/challenge" -> {
                        val request = Wire.json.decodeFromString<ChallengeRequest>(body!!.toString(Charsets.UTF_8))
                        Wire.json.encodeToString(Challenge(uuid(), Wire.encode(SyncCrypto.randomKey()), server.serverId, Long.MAX_VALUE, request.purpose))
                    }
                    path == "/v1/devices/session" -> Wire.json.encodeToString(Session(Wire.encode(SyncCrypto.randomKey()), Long.MAX_VALUE))
                    method == "GET" && path.contains("/membership?afterEpoch=") -> {
                        val after = path.substringAfter("afterEpoch=").toLong()
                        Wire.json.encodeToString(Membership(returned.filter { it.signed().body().epoch > after }.take(1), returned.last().signed().body().epoch))
                    }
                    method == "POST" && path.endsWith("/membership") -> {
                        val m = Wire.json.decodeFromString<SignedManifest>(body!!.toString(Charsets.UTF_8))
                        val proof = ManifestProof(m.manifestBytes, m.signature, m.transferAcceptance)
                        returned = returned + proof
                        Wire.json.encodeToString(Membership(listOf(proof), m.body().epoch))
                    }
                    else -> error("unexpected route")
                }
                return WireResponse(200, bytes.toByteArray())
            }
        }
        val client = HouseholdClient(store, transport, CheckpointSource { _, _ -> error("no lists") })
        val before = state
        try { client.recover(setup.file, setup.secret); error("A higher correctly signed fork must fail") } catch (_: IllegalArgumentException) { }
        assertThat(state).isEqualTo(before); assertThat(writes).isEqualTo(0)
        assertThat(calls.none { it.contains("key-packages") || it.contains("snapshot") || it.startsWith("POST /v1/households/") }).isTrue()
        returned = listOf(genesis, joined, cached, positiveHead)
        assertThat(client.recover(setup.file, setup.secret)).isEqualTo(household)
        assertThat(state.households.getValue(household).epoch).isEqualTo(5)
        assertThat(state.households.getValue(household).device.publicKeys).isNotEqualTo(setup.device.publicKeys)
    }
}

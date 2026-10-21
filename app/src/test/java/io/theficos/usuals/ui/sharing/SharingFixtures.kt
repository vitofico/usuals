package io.theficos.usuals.ui.sharing

import io.theficos.usuals.sync.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

internal const val ORIGIN = "https://usuals.example"
internal const val ID = "00000000-0000-4000-8000-000000000001"
internal val digest: String = Wire.encode(ByteArray(32))
internal val server: ServerInfo = SyncCrypto.device().publicKeys.let { ServerInfo(Wire.hash(Wire.decode(it.signingPublicKey)), it.signingPublicKey) }

internal fun memoryStore(initial: ClientState = ClientState()): SyncStateStore = object : SyncStateStore {
    private var value = initial
    @Synchronized override fun read() = value
    @Synchronized override fun update(change: (ClientState) -> ClientState) = change(value).also { value = it }
    override fun reserve(scope: String) { error("unused") }
    override fun saveBlob(id: String, bytes: ByteArray) { error("unused") }
    override fun readBlob(id: String): ByteArray? = error("unused")
    override fun removeBlob(id: String) { error("unused") }
}

/** Records every path and answers from [routes]; anything unrouted is a relay refusal. */
internal class RecordingRelay(private val routes: (String) -> Any?) : RelayTransport {
    val paths: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
        paths += path
        val answer = routes(path) ?: throw RelayProblem("forbidden", 403)
        return WireResponse(200, (if (answer is String) answer else Wire.json.encodeToString(kotlinx.serialization.serializer(answer::class.java), answer)).toByteArray())
    }
}

internal fun pendingConnection() = PendingConnection(ORIGIN, InstanceInfo(name = "Server", server = server, accounts = true, ai = false),
    ConnectTicket(ID, digest, Long.MAX_VALUE), digest, SyncCrypto.device())

internal fun pendingJoin(): PendingJoinState {
    val invitation = InvitationLink(version = 2, origin = ORIGIN, serverId = server.serverId, householdId = ID, genesisHash = digest,
        ownerDeviceId = ID, ownerFingerprint = digest, inviteId = ID, memberId = ID, role = "editor", secret = digest,
        expiresAt = Long.MAX_VALUE, key = digest)
    val device = SyncCrypto.device()
    val assertion = JoinAssertion(ORIGIN, server.serverId, ID, digest, ID, ID, ID, device.publicKeys, Wire.hash(Wire.decode(digest)), ID, digest, Long.MAX_VALUE)
    return PendingJoinState(invitation, device, assertion, SyncCrypto.sign(device, assertion.bytes()))
}

internal fun household(role: String = "owner", removed: Boolean = false) = HouseholdSummary(ID, ORIGIN, role, 1, emptyList(), removed, false)

internal fun SharingViewModel.settled(): SharingState = runBlocking { withTimeout(5000) { state.first { !it.busy } } }

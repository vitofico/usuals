package io.theficos.comanda.sync

import kotlinx.serialization.Serializable

/** Implementations commit atomically and keep this state outside all shopping-data transfers. */
interface SyncStateStore {
    fun read(): ClientState
    fun update(change: (ClientState) -> ClientState): ClientState
    fun reserve(scope: String)
    fun saveBlob(id: String, bytes: ByteArray)
    fun readBlob(id: String): ByteArray?
    fun removeBlob(id: String)
}
@Serializable data class ClientState(
    val households: Map<String, SavedHousehold> = emptyMap(),
    val bootstrap: PendingBootstrap? = null,
    val joining: PendingJoinState? = null,
) { override fun toString() = "ClientState([redacted])" }
@Serializable data class SavedHousehold(
    val origin: String, val server: ServerInfo, val id: String, val genesis: ManifestProof, val current: ManifestProof,
    val device: DeviceSecrets, val previous: ManifestProof? = null, val keys: Map<String, String> = emptyMap(), val session: Session? = null,
    val headPins: Map<String, HeadPin> = emptyMap(), val receiptPins: Map<String, Map<Long, String>> = emptyMap(), val removed: Boolean = false, val pending: PendingMembership? = null,
) {
    val epoch get() = current.signed().body().epoch
    val genesisHash get() = genesis.signed().hash()
    val hash get() = current.signed().hash()
    override fun toString() = "SavedHousehold([redacted])"
}
@Serializable data class PendingBootstrap(val origin: String, val server: ServerInfo, val genesis: SignedManifest, val device: DeviceSecrets, val token: String) {
    override fun toString() = "PendingBootstrap([redacted])"
}
@Serializable data class PendingJoinState(val invitation: InvitationLink, val device: DeviceSecrets, val assertion: JoinAssertion, val signature: String) {
    override fun toString() = "PendingJoinState([redacted])"
}
@Serializable data class PendingMembership(val manifest: SignedManifest?, val draft: Manifest, val keys: Map<String, String>,
    val candidates: Map<String, String> = emptyMap()) {
    override fun toString() = "PendingMembership([redacted])"
}
@Serializable data class InvitationLink(val version: Int = 1, val origin: String, val serverId: String, val householdId: String,
    val genesisHash: String, val ownerDeviceId: String, val ownerFingerprint: String, val inviteId: String,
    val memberId: String, val role: String, val secret: String, val expiresAt: Long) {
    fun uri() = "comanda://join#" + Wire.encode(Wire.json.encodeToString(serializer(), this).toByteArray())
    override fun toString() = "InvitationLink([redacted])"
    companion object {
        fun parse(uri: String): InvitationLink {
            require(uri.startsWith("comanda://join#") && uri.length <= 4096)
            val value = Wire.json.decodeFromJsonElement(serializer(), Wire.strictJson(Wire.decode(uri.substringAfter('#')), 3072))
            require(value.version == 1 && value.role in setOf("owner", "editor") && value.expiresAt > 0)
            Wire.uuid(value.householdId); Wire.uuid(value.ownerDeviceId); Wire.uuid(value.inviteId); Wire.uuid(value.memberId)
            Wire.decode(value.serverId, 32); Wire.decode(value.genesisHash, 32); Wire.decode(value.ownerFingerprint, 32); Wire.decode(value.secret, 32)
            return value
        }
    }
}
interface RelayTransport {
    suspend fun request(origin: String, method: String, path: String, body: ByteArray? = null, token: String? = null, maxResponse: Int = 1024 * 1024): WireResponse
}
data class WireResponse(val status: Int, val body: ByteArray) { override fun toString() = "WireResponse([redacted])" }
class RelayProblem(val code: String, val status: Int = 0) : RuntimeException(code)

/** Immutable encrypted-content checkpoint. Source catches up first; wall time is never a revision. */
data class ListCheckpoint(val seq: Long, val head: String, val payload: ByteArray) { override fun toString() = "ListCheckpoint([redacted])" }
fun interface CheckpointSource { suspend fun checkpoint(household: String, list: String): ListCheckpoint }

@Serializable data class HeadPin(val seq: Long, val head: String)

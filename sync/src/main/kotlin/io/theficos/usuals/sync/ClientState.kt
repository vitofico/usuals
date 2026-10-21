package io.theficos.usuals.sync

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
    val connection: ServerConnection? = null,
    val connecting: PendingConnection? = null,
    val sharingSource: String? = null,
    val pendingShares: Map<String, PendingShare> = emptyMap(),
    val invites: Map<String, OutgoingInvite> = emptyMap(),
) { override fun toString() = "ClientState([redacted])" }
/** An invite this device created; only its key can verify the applicant's join MAC. */
@Serializable data class OutgoingInvite(val household: String, val key: String, val memberId: String, val role: String, val expiresAt: Long) {
    override fun toString() = "OutgoingInvite([redacted])"
}
@Serializable data class PendingShare(val household: String, val source: String, val target: String, val includeHistory: Boolean)
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
@Serializable data class PendingBootstrap(val origin: String, val server: ServerInfo, val genesis: SignedManifest, val device: DeviceSecrets, val token: String, val account: Boolean = false) {
    override fun toString() = "PendingBootstrap([redacted])"
}
@Serializable data class PendingJoinState(val invitation: InvitationLink, val device: DeviceSecrets, val assertion: JoinAssertion, val signature: String) {
    override fun toString() = "PendingJoinState([redacted])"
}
@Serializable data class PendingMembership(val manifest: SignedManifest?, val draft: Manifest, val keys: Map<String, String>,
    val candidates: Map<String, String> = emptyMap()) {
    override fun toString() = "PendingMembership([redacted])"
}
/** The household facts an applicant took from a verified invite; [key] is null only for a legacy v1 join kept in storage. */
@Serializable data class InvitationLink(val version: Int = 1, val origin: String, val serverId: String, val householdId: String,
    val genesisHash: String, val ownerDeviceId: String, val ownerFingerprint: String, val inviteId: String,
    val memberId: String, val role: String, val secret: String, val expiresAt: Long, val key: String? = null) {
    override fun toString() = "InvitationLink([redacted])"
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

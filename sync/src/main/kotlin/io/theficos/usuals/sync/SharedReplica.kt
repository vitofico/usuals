package io.theficos.usuals.sync

/** Shopping transactions remain on the device; the client never receives a database handle. */
interface SharedReplica {
    suspend fun confirmed(list: String): ReplicaCheckpoint?
    suspend fun pending(list: String): List<PendingIntent>
    suspend fun saveEnvelope(list: String, intent: Intent, bytes: ByteArray)
    suspend fun confirm(list: String, checkpoint: ListCheckpoint, epoch: Long, manifestHash: String)
}
data class ReplicaCheckpoint(val checkpoint: ListCheckpoint, val epoch: Long, val manifestHash: String) {
    override fun toString() = "ReplicaCheckpoint([redacted])"
}
data class PendingIntent(val intent: Intent, val envelope: ByteArray?) {
    override fun toString() = "PendingIntent([redacted])"
}
data class SyncProgress(val sent: Int, val hasPending: Boolean)

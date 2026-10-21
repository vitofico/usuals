package io.theficos.usuals.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Implementations commit atomically and keep this state outside all shopping-data transfers. */
interface SyncStateStore {
    fun read(): ClientState
    fun update(change: (ClientState) -> ClientState): ClientState
}
@Serializable data class ClientState(
    val connection: ServerConnection? = null,
    val connecting: PendingConnection? = null,
) {
    override fun toString() = "ClientState([redacted])"
    companion object {
        /** State saved while households existed still carries their fields. */
        fun decode(text: String): ClientState = stored.decodeFromString(serializer(), text)
        private val stored = Json(Wire.json) { ignoreUnknownKeys = true }
    }
}
interface RelayTransport {
    suspend fun request(origin: String, method: String, path: String, body: ByteArray? = null, token: String? = null, maxResponse: Int = 1024 * 1024): WireResponse
}
data class WireResponse(val status: Int, val body: ByteArray) { override fun toString() = "WireResponse([redacted])" }
class RelayProblem(val code: String, val status: Int = 0) : RuntimeException(code)

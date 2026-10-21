package io.theficos.usuals.sync

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable data class ServiceError(val code: String)
@Serializable data class InstanceInfo(val version: Int = 1, val name: String, val server: ServerInfo, val accounts: Boolean, val ai: Boolean)
@Serializable data class ConnectStart(val device: DeviceKeys, val verifierHash: String, val offer: OfferClaim? = null)
/** The secret from a signed-in browser's QR; it lets that account, not this phone's browser, approve the connection. */
@Serializable data class OfferClaim(val id: String, val secret: String) {
    override fun toString() = "OfferClaim([redacted])"
}
@Serializable data class ConnectTicket(val id: String, val pollToken: String, val expiresAt: Long) {
    override fun toString() = "ConnectTicket([redacted])"
}
@Serializable data class ConnectFinish(val pollToken: String, val verifier: String) {
    override fun toString() = "ConnectFinish([redacted])"
}
@Serializable data class ConnectResult(val approved: Boolean, val userId: String? = null, val expiresAt: Long? = null)
@Serializable data class ConnectView(val id: String, val fingerprint: String, val expiresAt: Long)
/**
 * [offered] attempts wait for the browser whose QR was scanned instead of a sign-in on this phone;
 * [invite] is that browser's household link, used once the account connection is approved.
 */
@Serializable data class PendingConnection(val origin: String, val info: InstanceInfo, val ticket: ConnectTicket, val verifier: String, val device: DeviceSecrets,
    val offered: Boolean = false, val invite: String? = null) {
    val browserUrl get() = "$origin/?connect=${ticket.id}"
    val fingerprint get() = connectionFingerprint(info.server.serverId, ticket.id, device.publicKeys)
    override fun toString() = "PendingConnection([redacted])"
}
@Serializable data class ServerConnection(val origin: String, val info: InstanceInfo, val userId: String, val token: String, val expiresAt: Long, val aiEnabled: Boolean = false, val aiRevision: String? = null) {
    override fun toString() = "ServerConnection([redacted])"
}
@Serializable data class AiProfileView(val id: String, val label: String, val model: String, val imageModel: String, val isLocal: Boolean, val baseUrl: String = "")
@Serializable data class AiSettings(val profiles: List<AiProfileView>, val selected: String?, val personal: Boolean, val hasKey: Boolean, val dailyLimit: Int, val used: Int, val revision: String? = null)
@Serializable data class AiSelection(val profile: String? = null, val model: String? = null, val key: String? = null) {
    override fun toString() = "AiSelection([redacted])"
}
@Serializable data class AiRequest(val text: String? = null, val image: String? = null, val mimeType: String? = null, val revision: String? = null) { override fun toString() = "AiRequest([redacted])" }
@Serializable data class AiItem(val name: String, val amount: String? = null, val unit: String? = null, val note: String? = null)
@Serializable data class AiReply(val items: List<AiItem> = emptyList(), val reachable: Boolean = true, val structured: Boolean = true,
    val images: Boolean = false, val model: String? = null, val problem: String? = null)

fun connectionFingerprint(server: String, ticket: String, device: DeviceKeys) =
    Wire.decode(Wire.fingerprint(server, ticket, device.signingPublicKey, device.recipientPublicKey), 32).joinToString("") { "%02x".format(it.toInt() and 255) }

fun connectionToken(ticket: String, verifier: String, pollToken: String) =
    Wire.hash("USUALS/CONNECTION/1\u0000$ticket\u0000$verifier\u0000$pollToken".toByteArray())

/** Instance URLs contain no capability. Invitations retain their private fragment. */
fun serverOrigin(link: String): String {
    if (link.startsWith("usuals://connect#")) return serverOrigin(Wire.decode(link.substringAfter('#')).toString(Charsets.UTF_8))
    val uri = URI(link.trim())
    require(uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.path in listOf("", "/", "/connect"))
    val origin = "${uri.scheme}://${uri.rawAuthority}"
    require(origin == origin.lowercase() && (uri.port == -1 || uri.port in 1..65535))
    require(uri.scheme == "https" || uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))
    return origin
}

/** Private state uses the existing Keystore vault; shopping storage is not involved. */
class ConnectionClient(private val store: SyncStateStore, private val transport: RelayTransport, private val clock: () -> Long = System::currentTimeMillis) {
    fun current() = store.read().connection
    fun pending() = store.read().connecting
    suspend fun discover(link: String): Pair<String, InstanceInfo> {
        val origin = serverOrigin(link)
        val info = call<InstanceInfo>(origin, "GET", "/v1/instance")
        require(info.version == 1 && info.name.length in 1..100 && 1 in info.server.protocolVersions && Wire.hash(Wire.decode(info.server.receiptPublicKey, 32)) == info.server.serverId)
        return origin to info
    }
    suspend fun begin(link: String): PendingConnection {
        require(pending() == null && current() == null)
        val code = if (ConnectCode.matches(link)) ConnectCode.parse(link) else null
        val (origin, info) = discover(code?.origin ?: link); require(info.accounts)
        val verifier = Wire.encode(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        val device = SyncCrypto.device()
        val ticket = call<ConnectTicket>(origin, "POST", "/v1/connect", ConnectStart(device.publicKeys, Wire.hash(Wire.decode(verifier, 32)), code?.claim()))
        Wire.uuid(ticket.id); Wire.decode(ticket.pollToken, 32); require(ticket.expiresAt > clock())
        return PendingConnection(origin, info, ticket, verifier, device, code != null, code?.invite?.link()).also { value -> store.update { it.copy(connecting = value) } }
    }
    suspend fun finish(): ServerConnection? {
        val pending = requireNotNull(pending())
        require(clock() < pending.ticket.expiresAt)
        val result = call<ConnectResult>(pending.origin, "POST", "/v1/connect/${pending.ticket.id}/complete", ConnectFinish(pending.ticket.pollToken, pending.verifier))
        if (!result.approved) return null
        val connection = ServerConnection(pending.origin, pending.info, requireNotNull(result.userId),
            connectionToken(pending.ticket.id, pending.verifier, pending.ticket.pollToken), requireNotNull(result.expiresAt))
        require(connection.expiresAt > clock())
        var accepted = false
        store.update { state ->
            val sameAttempt = state.connecting?.let { it.origin == pending.origin && it.info == pending.info &&
                it.ticket == pending.ticket && it.verifier == pending.verifier && it.device.publicKeys == pending.device.publicKeys } == true
            if (sameAttempt && state.connection == null) {
                accepted = true; state.copy(connection = connection, connecting = null)
            } else {
                // A concurrent poll may already have committed this same grant and enabled AI.
                accepted = state.connection?.let { it.origin == connection.origin && it.token == connection.token } == true
                state
            }
        }
        if (!accepted) {
            try { bytes(pending.origin, "POST", "/v1/connection/logout", null, connection.token) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { }
            return null
        }
        return current()
    }
    fun cancel() { store.update { it.copy(connecting = null) } }
    fun enableAi(enabled: Boolean, revision: String? = null) { store.update { it.copy(connection = it.connection?.copy(aiEnabled = enabled, aiRevision = revision)) } }
    suspend fun disconnect() {
        try { if (current() != null) request<ConnectResult>("POST", "/v1/connection/logout") }
        finally { store.update { it.copy(connection = null, connecting = null) } }
    }
    suspend inline fun <reified T> request(method: String, path: String, body: Any? = null, expected: ServerConnection? = null): T =
        decode(requestBytes(method, path, body, expected))
    suspend fun requestBytes(method: String, path: String, body: Any? = null, expected: ServerConnection? = null): ByteArray {
        val saved = current() ?: throw RelayProblem("unauthenticated", 401)
        require(expected == null || saved == expected)
        return bytes(saved.origin, method, path, encode(body), saved.token)
    }
    private suspend inline fun <reified T> call(origin: String, method: String, path: String, body: Any? = null): T = decode(bytes(origin, method, path, encode(body)))
    private fun encode(body: Any?): ByteArray? = when (body) {
        null -> null
        is ConnectStart -> Wire.json.encodeToString(ConnectStart.serializer(), body)
        is ConnectFinish -> Wire.json.encodeToString(ConnectFinish.serializer(), body)
        is AiSelection -> Wire.json.encodeToString(AiSelection.serializer(), body)
        is AiRequest -> Wire.json.encodeToString(AiRequest.serializer(), body)
        else -> error("unsupported_body")
    }?.toByteArray()
    private suspend fun bytes(origin: String, method: String, path: String, body: ByteArray?, token: String? = null): ByteArray {
        val response = transport.request(origin, method, path, body, token)
        if (response.status !in 200..299) throw RelayProblem(runCatching { decode<ServiceError>(response.body).code }.getOrDefault("unavailable"), response.status)
        return response.body
    }
    companion object {
        inline fun <reified T> decode(bytes: ByteArray): T = Wire.json.decodeFromJsonElement(Wire.strictJson(bytes, 1024 * 1024))
    }
}

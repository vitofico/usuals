package io.theficos.usuals.relay

import io.theficos.usuals.sync.*
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable data class NativeConnectionView(val id: String, val expiresAt: Long)
@Serializable data class NativeConnections(val connections: List<NativeConnectionView>)

/** Browser approval grants account services only; household keys stay on devices. */
class Connections(private val db: Database, private val relay: Relay, private val identity: WebIdentity,
    private val clock: () -> Long = System::currentTimeMillis) {
    fun start(request: ConnectStart, ip: String): ConnectTicket {
        relay.auth.limit("connect:$ip", 10)
        return db.transaction { c ->
            id(request.device.id); Wire.decode(request.device.signingPublicKey, 32); Wire.decode(request.device.recipientPublicKey, 32)
            Wire.decode(request.verifierHash, 32)
            c.execute("DELETE FROM connection_ticket WHERE expires<=?", clock())
            c.execute("DELETE FROM native_session WHERE expires<=?", clock())
            expect(c.query("SELECT count(*) FROM connection_ticket") { it.getLong(1) }.single() < 1000, "rate_limited", 429)
            val ticket = ConnectTicket(UUID.randomUUID().toString(), randomToken(), clock() + 300000)
            c.execute("INSERT INTO connection_ticket VALUES (?,?,?,?,?,?,?,NULL)", id(ticket.id), Wire.decode(request.device.signingPublicKey, 32),
                Wire.decode(request.device.recipientPublicKey, 32), id(request.device.id), Wire.decode(request.verifierHash, 32), digest(ticket.pollToken), ticket.expiresAt)
            ticket
        }
    }
    internal fun view(caller: WebCaller, ticket: String): ConnectView = db.transaction { c ->
        allowed(c, caller.user.id)
        c.query("SELECT device,signing,recipient,expires FROM connection_ticket WHERE id=? AND expires>?", id(ticket), clock()) {
            ConnectView(ticket, connectionFingerprint(relay.identity.serverId, ticket, DeviceKeys(it.getObject(1).toString(), Wire.encode(it.getBytes(2)), Wire.encode(it.getBytes(3)))), it.getLong(4))
        }.singleOrNull() ?: reject("not_found", 404)
    }
    internal fun approve(caller: WebCaller, ticket: String) = db.transaction { c ->
        allowed(c, caller.user.id)
        identity.current(c, caller)
        expect(c.execute("UPDATE connection_ticket SET user_id=? WHERE id=? AND expires>? AND (user_id IS NULL OR user_id=?)", id(caller.user.id), id(ticket), clock(), id(caller.user.id)) == 1, "not_found", 404)
    }
    fun complete(ticket: String, request: ConnectFinish, ip: String): ConnectResult {
        relay.auth.limit("connect-poll:$ip", 60)
        return db.transaction { c ->
            val row = c.query("SELECT user_id FROM connection_ticket WHERE id=? AND expires>? AND verifier_hash=? AND poll_hash=?", id(ticket), clock(),
                Wire.decode(Wire.hash(Wire.decode(request.verifier, 32)), 32), digest(request.pollToken)) { it.getObject(1)?.toString() }.singleOrNull()
            // A pending row has a null account; distinguish it from invalid credentials.
            expect(c.query("SELECT 1 FROM connection_ticket WHERE id=? AND expires>? AND verifier_hash=? AND poll_hash=?", id(ticket), clock(),
                Wire.decode(Wire.hash(Wire.decode(request.verifier, 32)), 32), digest(request.pollToken)) { true }.isNotEmpty(), "unauthenticated", 401)
            if (row == null) return@transaction ConnectResult(false)
            allowed(c, row)
            expect(c.query("SELECT 1 FROM connection_ticket WHERE id=? AND user_id=? AND expires>? AND verifier_hash=? AND poll_hash=? FOR UPDATE", id(ticket), id(row), clock(),
                Wire.decode(Wire.hash(Wire.decode(request.verifier, 32)), 32), digest(request.pollToken)) { true }.isNotEmpty(), "unauthenticated", 401)
            val token = connectionToken(ticket, request.verifier, request.pollToken)
            val expires = clock() + 30L * 86400000
            expect(c.query("SELECT count(*) FROM native_session WHERE user_id=? AND revoked=false AND expires>?", id(row), clock()) { it.getLong(1) }.single() < 20 ||
                c.query("SELECT 1 FROM native_session WHERE ticket=?", id(ticket)) { true }.isNotEmpty(), "rate_limited", 429)
            c.execute("INSERT INTO native_session(hash,ticket,user_id,expires) VALUES (?,?,?,?) ON CONFLICT(ticket) DO NOTHING", digest(token), id(ticket), id(row), expires)
            val expiry = c.query("SELECT expires FROM native_session WHERE ticket=? AND revoked=false", id(ticket)) { it.getLong(1) }.singleOrNull() ?: reject("unauthenticated", 401)
            ConnectResult(true, row, expiry)
        }
    }
    internal fun caller(c: Connection, token: String): String {
        Wire.decode(token, 32)
        val user = c.query("SELECT user_id FROM native_session WHERE hash=? AND revoked=false AND expires>?", digest(token), clock()) { it.getObject(1).toString() }
            .singleOrNull() ?: reject("unauthenticated", 401)
        allowed(c, user)
        expect(c.query("SELECT 1 FROM native_session WHERE hash=? AND user_id=? AND revoked=false AND expires>?", digest(token), id(user), clock()) { true }.isNotEmpty(), "unauthenticated", 401)
        return user
    }
    internal fun allowed(c: Connection, user: String) {
        expect(c.query("SELECT issuer,disabled FROM web_user WHERE id=? FOR UPDATE", id(user)) { identity.config.accepts(it.getString(1)) && !it.getBoolean(2) }.singleOrNull() == true, "forbidden", 403)
    }
    internal fun sessions(caller: WebCaller) = db.transaction { c ->
        allowed(c, caller.user.id)
        NativeConnections(c.query("SELECT ticket,expires FROM native_session WHERE user_id=? AND revoked=false AND expires>? ORDER BY expires", id(caller.user.id), clock()) {
            NativeConnectionView(it.getObject(1).toString(), it.getLong(2))
        })
    }
    internal fun revoke(caller: WebCaller, ticket: String) = db.transaction { c ->
        allowed(c, caller.user.id)
        expect(c.execute("UPDATE native_session SET revoked=true WHERE ticket=? AND user_id=?", id(ticket), id(caller.user.id)) == 1, "not_found", 404)
    }
    fun logout(token: String) = db.transaction { c -> caller(c, token); c.execute("UPDATE native_session SET revoked=true WHERE hash=?", digest(token)); ConnectResult(false) }
}

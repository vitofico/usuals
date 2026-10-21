package io.theficos.usuals.relay

import io.theficos.usuals.sync.*
import java.sql.Connection
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable data class NativeConnectionView(val id: String, val expiresAt: Long)
@Serializable data class NativeConnections(val connections: List<NativeConnectionView>)
@Serializable data class ConnectOfferRequest(val secretHash: String)
@Serializable data class ConnectOffer(val id: String, val expiresAt: Long)
@Serializable data class ConnectOfferView(val id: String, val expiresAt: Long, val ticket: ConnectView? = null)

/** Connects a phone to an account: approved by a signed-in browser, or at once through that browser's QR offer. */
class Connections(private val db: Database, private val relay: Relay, private val identity: WebIdentity,
    private val clock: () -> Long = relay.auth::now) {
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
            // A claimed offer lives exactly as long as its ticket, and a second phone with the same QR finds it taken.
            // Holding the secret is the approval, as long as the browser session that showed the QR is still signed in.
            request.offer?.let { claim ->
                c.execute("DELETE FROM connection_offer WHERE expires<=?", clock())
                val user = c.query("UPDATE connection_offer o SET ticket=?, expires=? WHERE id=? AND secret_hash=? AND expires>? AND ticket IS NULL " +
                    "AND EXISTS(SELECT 1 FROM web_session s WHERE s.id=o.session AND s.revoked=false AND s.expires>?) RETURNING user_id", id(ticket.id), ticket.expiresAt,
                    id(claim.id), Wire.decode(Wire.hash(Wire.decode(claim.secret, 32)), 32), clock(), clock()) { it.getObject(1) }.singleOrNull() ?: reject("not_found", 404)
                c.execute("UPDATE connection_ticket SET user_id=? WHERE id=?", user, id(ticket.id))
            }
            ticket
        }
    }
    internal fun view(caller: WebCaller, ticket: String): ConnectView = db.transaction { c ->
        allowed(c, caller.user.id)
        ticketView(c, ticket) ?: reject("not_found", 404)
    }
    private fun ticketView(c: Connection, ticket: String) =
        c.query("SELECT device,signing,recipient,expires FROM connection_ticket WHERE id=? AND expires>?", id(ticket), clock()) {
            ConnectView(ticket, connectionFingerprint(relay.identity.serverId, ticket, DeviceKeys(it.getObject(1).toString(), Wire.encode(it.getBytes(2)), Wire.encode(it.getBytes(3)))), it.getLong(4))
        }.singleOrNull()
    /** A signed-in browser's QR for its own phone: the phone that holds the secret asks, and this account approves without a second sign-in. */
    internal fun offer(caller: WebCaller, request: ConnectOfferRequest): ConnectOffer = db.transaction { c ->
        allowed(c, caller.user.id)
        identity.current(c, caller)
        val hash = Wire.decode(request.secretHash, 32)
        c.execute("DELETE FROM connection_offer WHERE expires<=?", clock())
        expect(c.query("SELECT count(*) FROM connection_offer WHERE user_id=? AND ticket IS NULL", id(caller.user.id)) { it.getLong(1) }.single() < 5, "rate_limited", 429)
        ConnectOffer(UUID.randomUUID().toString(), clock() + 600000).also {
            c.execute("INSERT INTO connection_offer(id,user_id,secret_hash,expires,session) VALUES (?,?,?,?,?)", id(it.id), id(caller.user.id), hash, it.expiresAt, id(caller.session))
        }
    }
    internal fun offerView(caller: WebCaller, offer: String): ConnectOfferView = db.transaction { c ->
        allowed(c, caller.user.id)
        val (expires, ticket) = c.query("SELECT expires,ticket FROM connection_offer WHERE id=? AND user_id=? AND expires>?", id(offer), id(caller.user.id), clock()) {
            it.getLong(1) to it.getObject(2)?.toString()
        }.singleOrNull() ?: reject("not_found", 404)
        ConnectOfferView(offer, expires, ticket?.let { ticketView(c, it) })
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
            val expires = clock() + SESSION
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
        // Slides to 180 days from use, written at most once a day.
        c.execute("UPDATE native_session SET expires=? WHERE hash=? AND expires<?", clock() + SESSION, digest(token), clock() + SESSION - 86400000)
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
    private companion object { const val SESSION = 180L * 86400000 }
}

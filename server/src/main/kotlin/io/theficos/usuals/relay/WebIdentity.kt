package io.theficos.usuals.relay

import io.theficos.usuals.sync.Proof
import io.theficos.usuals.sync.CreateHousehold
import io.theficos.usuals.sync.ManifestProof
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

@Serializable data class WebAuthConfig(val enabled: Boolean, val loginPath: String? = null, val oidcEnabled: Boolean = false, val localEnabled: Boolean = false)
@Serializable data class WebUser(val id: String, val displayName: String, val email: String?, val admin: Boolean, val disabled: Boolean, val createdAt: Long, val lastLoginAt: Long, val issuer: String, val subject: String, val username: String? = null)
@Serializable data class WebMe(val user: WebUser, val csrfToken: String, val expiresAt: Long) { override fun toString() = "WebMe([redacted])" }
@Serializable data class WebSessionView(val id: String, val createdAt: Long, val expiresAt: Long, val current: Boolean)
@Serializable data class WebSessions(val sessions: List<WebSessionView>)
@Serializable data class WebUsers(val users: List<WebUser>, val next: String? = null)
@Serializable data class WebUserUpdate(val disabled: Boolean)
internal data class WebCaller(val session: String, val user: WebUser, val csrf: String, val expires: Long) { override fun toString() = "WebCaller([redacted])" }
internal data class WebLoginStart(val location: String, val browserToken: String) { override fun toString() = "WebLoginStart([redacted])" }
internal data class WebLoginComplete(val token: String, val expires: Long) { override fun toString() = "WebLoginComplete([redacted])" }

/** App identity is separate from household keys, manifests and recovery authority. */
class WebIdentity internal constructor(
    val config: WebIdentityConfig,
    private val db: Database,
    private val auth: Auth,
    private val oidc: OidcClient? = config.oidc?.let(::OidcClient),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    internal constructor(config: OidcConfig, db: Database, auth: Auth, oidc: OidcClient = OidcClient(config), clock: () -> Long = System::currentTimeMillis) :
        this(WebIdentityConfig(config.origin, config, false), db, auth, oidc, clock)
    companion object { fun create(config: WebIdentityConfig, db: Database, auth: Auth) = WebIdentity(config, db, auth) }
    private val local = LocalAccounts(config, db, auth, clock)
    internal fun localChallenge(ip: String) = local.challenge(ip)
    internal suspend fun localLogin(browser: String, origin: String?, csrf: String?, ip: String, request: suspend () -> LocalLoginRequest): WebLoginComplete {
        local.consumeChallenge(browser, origin, csrf)
        return local.login(request(), ip) { c, userId -> newSession(c, userId) }
    }
    internal suspend fun localActivate(browser: String, origin: String?, csrf: String?, ip: String, request: suspend () -> LocalActivateRequest): WebLoginComplete {
        local.consumeChallenge(browser, origin, csrf)
        return local.activate(request(), ip) { c, userId -> newSession(c, userId) }
    }
    internal fun localPassword(caller: WebCaller, request: LocalPasswordRequest, ip: String) {
        db.transaction { current(it, caller) }; local.password(caller, request, ip)
    }
    internal fun localCreate(caller: WebCaller, request: LocalCreateRequest): LocalSetup = db.transaction { c ->
        expect(current(c, caller).user.admin, "forbidden", 403)
        local.create(c, request, false).let { value -> LocalSetup(localUser(c, value.userId), value.token, value.expires) }
    }
    internal fun localReset(caller: WebCaller, target: String): LocalSetup = db.transaction { c ->
        expect(current(c, caller).user.admin, "forbidden", 403)
        local.reset(c, target).let { value -> LocalSetup(localUser(c, value.userId), value.token, value.expires) }
    }
    internal fun localOperator(username: String, path: java.nio.file.Path, reset: Boolean) = local.operator(username, path, reset)
    private fun localUser(c: Connection, userId: String) = c.query("SELECT ${userColumns()} FROM web_user WHERE id=?", id(userId), read = ::user).single()
    internal fun begin(ip: String): WebLoginStart {
        auth.limit("web-login:$ip", 10)
        val client = oidc ?: reject("not_found", 404)
        val metadata = client.metadata()
        val state = randomToken(); val browser = randomToken(); val nonce = randomToken(); val verifier = randomToken()
        db.transaction { c ->
            c.execute("DELETE FROM web_login WHERE expires<=?", clock())
            expect(c.query("SELECT count(*) FROM web_login") { it.getLong(1) }.single() < 1000, "rate_limited", 429)
            c.execute("INSERT INTO web_login VALUES (?,?,?,?,?)", digest(state), digest(browser), digest(nonce), verifier, clock() + 300000)
        }
        return WebLoginStart(client.authorization(metadata, state, nonce, verifier), browser)
    }
    internal fun complete(code: String, state: String, browser: String, ip: String): WebLoginComplete {
        auth.limit("web-callback:$ip", 20)
        requireToken(state); requireToken(browser)
        // Commit consumption before contacting the provider, even if exchange or verification fails.
        val pending = db.transaction { c -> c.query("DELETE FROM web_login WHERE state_hash=? RETURNING browser_hash,nonce_hash,verifier,expires", digest(state)) {
            Pending(it.getBytes(1), it.getBytes(2), it.getString(3), it.getLong(4))
        }.singleOrNull() } ?: reject("unauthenticated", 401)
        expect(pending.expires > clock() && MessageDigest.isEqual(digest(browser), pending.browser), "unauthenticated", 401)
        val oidcConfig = config.oidc ?: reject("not_found", 404)
        val identity = requireNotNull(oidc).complete(code, pending.verifier, pending.nonce)
        return db.transaction { c ->
            c.query("SELECT pg_advisory_xact_lock(740520261018)") { Unit }
            var user = c.query("SELECT ${userColumns()} FROM web_user WHERE issuer=? AND subject=? FOR UPDATE", oidcConfig.issuer, identity.subject, read = ::user).singleOrNull()
            if (user == null) {
                expect(c.query("SELECT count(*) FROM web_user") { it.getLong(1) }.single() < 10000, "unavailable", 503)
                val id = UUID.randomUUID()
                c.execute("INSERT INTO web_user(id,issuer,subject,display_name,email,disabled,created_at,last_login_at) VALUES (?,?,?,?,?,false,?,?)", id, oidcConfig.issuer, identity.subject, identity.displayName, identity.email, clock(), clock())
                user = WebUser(id.toString(), identity.displayName, identity.email, identity.subject in oidcConfig.adminSubjects, false, clock(), clock(), oidcConfig.issuer, identity.subject)
            } else {
                expect(!user.disabled, "forbidden", 403)
                c.execute("UPDATE web_user SET display_name=?,email=?,last_login_at=? WHERE id=?", identity.displayName, identity.email, clock(), id(user.id))
            }
            newSession(c, user.id)
        }
    }
    private fun newSession(c: Connection, userId: String): WebLoginComplete {
            val active = c.query("SELECT issuer,disabled FROM web_user WHERE id=? FOR UPDATE", id(userId)) { it.getString(1) to it.getBoolean(2) }.singleOrNull()
            expect(active != null && config.accepts(active.first) && !active.second, "unauthenticated", 401)
            c.execute("DELETE FROM web_session WHERE expires<=?", clock())
            expect(c.query("SELECT count(*) FROM web_session") { it.getLong(1) }.single() < 20000, "unavailable", 503)
            // Limit active sessions per account without quietly logging out another browser.
            expect(c.query("SELECT count(*) FROM web_session WHERE user_id=? AND revoked=false AND expires>?", id(userId), clock()) { it.getLong(1) }.single() < 20, "rate_limited", 429)
            val token = randomToken(); val expiry = clock() + 900000
            c.execute("INSERT INTO web_session VALUES (?,?,?,?,?,?,false)", UUID.randomUUID(), digest(token), id(userId), randomToken(), clock(), expiry)
            return WebLoginComplete(token, expiry)
    }
    internal fun caller(c: Connection, token: String): WebCaller {
        requireToken(token)
        val row = c.query("SELECT s.id,s.csrf_token,s.expires,${userColumns("u")} FROM web_session s JOIN web_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked=false AND s.expires>?", digest(token), clock()) {
            WebCaller(it.getObject(1).toString(), user(it, 3), it.getString(2), it.getLong(3))
        }.singleOrNull() ?: reject("unauthenticated", 401)
        expect(config.accepts(row.user.issuer) && !row.user.disabled, "forbidden", 403)
        return row
    }
    internal fun me(token: String): WebMe = db.transaction { c -> caller(c, token).let { WebMe(it.user, it.csrf, it.expires) } }
    internal fun csrf(token: String, origin: String?, csrf: String?): WebCaller = db.transaction { c ->
        val caller = caller(c, token)
        expect(origin == config.origin && csrf != null && MessageDigest.isEqual(digest(csrf), digest(caller.csrf)), "forbidden", 403)
        caller
    }
    internal fun logout(caller: WebCaller) = db.transaction { c ->
        current(c, caller)
        c.execute("UPDATE web_session SET revoked=true WHERE id=?", id(caller.session))
    }
    internal fun sessions(token: String): WebSessions = db.transaction { c ->
        val caller = caller(c, token)
        WebSessions(c.query("SELECT id,created_at,expires FROM web_session WHERE user_id=? AND revoked=false AND expires>? ORDER BY created_at,id LIMIT 20", id(caller.user.id), clock()) {
            WebSessionView(it.getObject(1).toString(), it.getLong(2), it.getLong(3), it.getObject(1).toString() == caller.session)
        })
    }
    internal fun revoke(caller: WebCaller, session: String) = db.transaction { c ->
        current(c, caller)
        expect(c.execute("UPDATE web_session SET revoked=true WHERE id=? AND user_id=?", id(session), id(caller.user.id)) == 1, "not_found", 404)
    }
    internal fun users(token: String, after: String?): WebUsers = db.transaction { c ->
        expect(caller(c, token).user.admin, "forbidden", 403)
        val rows = c.query("SELECT ${userColumns()} FROM web_user WHERE (issuer=? OR issuer=?) AND id>? ORDER BY id LIMIT 101", config.oidc?.issuer ?: "", if (config.localEnabled) WebIdentityConfig.LOCAL_ISSUER else "", id(after ?: "00000000-0000-0000-0000-000000000000"), read = ::user)
        WebUsers(rows.take(100), if (rows.size > 100) rows[99].id else null)
    }
    internal fun update(caller: WebCaller, target: String, update: WebUserUpdate): WebUser = db.transaction { c ->
        expect(current(c, caller).user.admin, "forbidden", 403)
        expect(c.execute("UPDATE web_user SET disabled=? WHERE id=? AND (issuer=? OR issuer=?)", update.disabled, id(target), config.oidc?.issuer ?: "", if (config.localEnabled) WebIdentityConfig.LOCAL_ISSUER else "") == 1, "not_found", 404)
        if (update.disabled) c.execute("UPDATE web_session SET revoked=true WHERE user_id=?", id(target))
        c.query("SELECT ${userColumns()} FROM web_user WHERE id=?", id(target), read = ::user).single()
    }
    internal fun revokeUser(caller: WebCaller, target: String) = db.transaction { c ->
        expect(current(c, caller).user.admin, "forbidden", 403)
        expect(c.query("SELECT issuer FROM web_user WHERE id=?", id(target)) { config.accepts(it.getString(1)) }.singleOrNull() == true, "not_found", 404)
        c.execute("UPDATE web_session SET revoked=true WHERE user_id=?", id(target))
    }
    internal fun link(caller: WebCaller, proof: Proof) = db.transaction { c ->
        current(c, caller)
        // The principal lock also serializes session minting, closing the bind/mint race.
        expect(c.query("SELECT id FROM principal WHERE id=? FOR UPDATE", id(proof.deviceId)) { true }.singleOrNull() == true, "unauthenticated", 401)
        auth.consume(c, proof, setOf("session"))
        expect(c.query("SELECT role FROM membership WHERE device=? AND active=true", id(proof.deviceId)) { it.getString(1) }.let { it.isNotEmpty() && it.none { role -> role == "recovery" } }, "forbidden", 403)
        expect(c.query("SELECT 1 FROM web_device WHERE device=?", id(proof.deviceId)) { true }.isEmpty(), "conflict", 409)
        c.execute("INSERT INTO web_device VALUES (?,?)", id(proof.deviceId), id(caller.user.id))
        c.execute("UPDATE auth_session SET revoked=true WHERE device=?", id(proof.deviceId))
    }
    internal fun createHousehold(caller: WebCaller, request: CreateHousehold, relay: Relay): ManifestProof {
        auth.limit("web-household:${caller.user.id}", 5)
        return db.transaction { c ->
            // Serialize quota checks with account disabling and bind before any device session can exist.
            c.query("SELECT id FROM web_user WHERE id=? FOR UPDATE", id(caller.user.id)) { Unit }
            current(c, caller)
            val manifest = ManifestRules.genesis(request.manifest, relay.identity.serverId)
            val existing = c.query("SELECT w.user_id,h.genesis FROM web_created_household w JOIN household h ON h.id=w.household WHERE w.household=?", id(manifest.householdId)) {
                it.getObject(1).toString() to io.theficos.usuals.sync.Wire.encode(it.getBytes(2))
            }.singleOrNull()
            if (existing != null) {
                expect(existing.first == caller.user.id && existing.second == request.manifest.hash(), "conflict", 409)
                val signing = auth.consume(c, request.bootstrapProof, setOf("bootstrap")).second
                expect(request.bootstrapProof.deviceId == manifest.signer && manifest.members.single().devices.single().signingPublicKey == signing)
                request.manifest.proof()
            } else {
                expect(c.query("SELECT count(*) FROM web_created_household WHERE user_id=?", id(caller.user.id)) { it.getLong(1) }.single() < 10, "quota_exceeded", 429)
                val keys = listOf(manifest.members.single().devices.single(), manifest.recovery)
                expect(keys.all { key -> c.query("SELECT 1 FROM principal WHERE id=? OR signing=? OR recipient=?", id(key.id), raw(key.signingPublicKey), raw(key.recipientPublicKey)) { true }.isEmpty() }, "conflict", 409)
                val proof = relay.create(c, request)
                c.execute("INSERT INTO web_device VALUES (?,?)", id(manifest.signer), id(caller.user.id))
                c.execute("INSERT INTO web_created_household VALUES (?,?)", id(manifest.householdId), id(caller.user.id))
                proof
            }
        }
    }
    private fun current(c: Connection, caller: WebCaller): WebCaller {
        val active = c.query("SELECT s.revoked,s.expires,u.disabled,u.issuer FROM web_session s JOIN web_user u ON u.id=s.user_id WHERE s.id=? AND s.user_id=?", id(caller.session), id(caller.user.id)) { !it.getBoolean(1) && it.getLong(2) > clock() && !it.getBoolean(3) && config.accepts(it.getString(4)) }.singleOrNull()
        expect(active == true, "unauthenticated", 401)
        return caller
    }
    private fun user(row: java.sql.ResultSet) = user(row, 0)
    private fun user(row: java.sql.ResultSet, offset: Int) = WebUser(row.getObject(offset + 1).toString(), row.getString(offset + 2), row.getString(offset + 3), config.administrator(row.getString(offset + 8), row.getString(offset + 4), row.getBoolean(offset + 9)), row.getBoolean(offset + 5), row.getLong(offset + 6), row.getLong(offset + 7), row.getString(offset + 8), row.getString(offset + 4), row.getString(offset + 10))
    private data class Pending(val browser: ByteArray, val nonce: ByteArray, val verifier: String, val expires: Long) { override fun toString() = "Pending([redacted])" }
}

private fun requireToken(value: String) { expect(value.length == 43 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }, "unauthenticated", 401) }
internal fun userColumns(prefix: String = "") = listOf("id", "display_name", "email", "subject", "disabled", "created_at", "last_login_at", "issuer", "local_admin", "local_username").joinToString(",") { if (prefix.isEmpty()) it else "$prefix.$it" }

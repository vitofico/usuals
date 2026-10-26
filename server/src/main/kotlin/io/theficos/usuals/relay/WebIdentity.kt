package io.theficos.usuals.relay

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

@Serializable data class WebAuthConfig(val enabled: Boolean, val loginPath: String? = null, val oidcEnabled: Boolean = false, val localEnabled: Boolean = false,
    val registrationPath: String? = null, val emailInvitations: Boolean = false, val invitationOnly: Boolean = false)
@Serializable data class WebUser(val id: String, val displayName: String, val email: String?, val admin: Boolean, val disabled: Boolean, val createdAt: Long, val lastLoginAt: Long, val issuer: String, val subject: String, val username: String? = null)
@Serializable data class WebMe(val user: WebUser, val csrfToken: String, val expiresAt: Long, val limits: AccountLimitView? = null) { override fun toString() = "WebMe([redacted])" }
@Serializable data class WebSessionView(val id: String, val createdAt: Long, val expiresAt: Long, val current: Boolean)
@Serializable data class WebSessions(val sessions: List<WebSessionView>)
@Serializable data class WebUsers(val users: List<WebUser>, val next: String? = null)
@Serializable data class WebUserUpdate(val disabled: Boolean)
@Serializable internal data class AccountSwitch(val url: String)
internal data class WebCaller(val session: String, val user: WebUser, val csrf: String, val expires: Long, val renewed: Boolean = false,
    val authenticatedAt: Long? = null, val verifiedEmail: String? = null) {
    fun me() = WebMe(user, csrf, expires)
    override fun toString() = "WebCaller([redacted])"
}
internal data class WebLoginStart(val location: String, val browserToken: String) { override fun toString() = "WebLoginStart([redacted])" }
internal data class WebLoginComplete(val token: String, val expires: Long) { override fun toString() = "WebLoginComplete([redacted])" }

/** Browser accounts: sign-in, sessions and administration. Sessions slide to 30 days from use. */
class WebIdentity internal constructor(
    val config: WebIdentityConfig,
    private val db: Database,
    private val auth: Auth,
    private val oidc: OidcClient? = config.oidc?.let(::OidcClient),
    private val clock: () -> Long = System::currentTimeMillis,
    internal val invitations: Invitations? = null,
) {
    internal constructor(config: OidcConfig, db: Database, auth: Auth, oidc: OidcClient = OidcClient(config), clock: () -> Long = System::currentTimeMillis) :
        this(WebIdentityConfig(config.origin, config, false), db, auth, oidc, clock)
    companion object {
        fun create(config: WebIdentityConfig, db: Database, auth: Auth) = WebIdentity(config, db, auth)
        const val SESSION_SECONDS = 30 * 86400
        private const val SESSION = SESSION_SECONDS * 1000L
        private const val DAY = 86_400_000L
    }
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
    internal fun begin(ip: String, register: Boolean = false, fresh: Boolean = false, invitationCookie: String? = null): WebLoginStart {
        auth.limit("web-login:$ip", 10)
        if (register) expect(config.oidc?.registrationEnabled == true, "not_found", 404)
        val contextHash = db.transaction { invitations?.contextHash(it, invitationCookie) }
        if (register && config.invitationOnly) expect(contextHash != null, "invitation_required", 403)
        val requireFresh = fresh || register || contextHash != null
        val client = oidc ?: reject("not_found", 404)
        val metadata = client.metadata()
        val state = randomToken(); val browser = randomToken(); val nonce = randomToken(); val verifier = randomToken()
        db.transaction { c ->
            c.execute("DELETE FROM web_login WHERE expires<=?", clock())
            expect(c.query("SELECT count(*) FROM web_login") { it.getLong(1) }.single() < 1000, "rate_limited", 429)
            c.execute("INSERT INTO web_login VALUES (?,?,?,?,?,?,?)", digest(state), digest(browser), digest(nonce), verifier, clock() + 300000, contextHash, requireFresh)
        }
        return WebLoginStart(client.authorization(metadata, state, nonce, verifier, register, requireFresh), browser)
    }
    internal fun complete(code: String, state: String, browser: String, ip: String): WebLoginComplete {
        auth.limit("web-callback:$ip", 20)
        requireToken(state); requireToken(browser)
        // Commit consumption before contacting the provider, even if exchange or verification fails.
        val pending = db.transaction { c -> c.query("DELETE FROM web_login WHERE state_hash=? RETURNING browser_hash,nonce_hash,verifier,expires,invitation_context,fresh", digest(state)) {
            Pending(it.getBytes(1), it.getBytes(2), it.getString(3), it.getLong(4), it.getBytes(5), it.getBoolean(6))
        }.singleOrNull() } ?: reject("unauthenticated", 401)
        expect(pending.expires > clock() && MessageDigest.isEqual(digest(browser), pending.browser), "unauthenticated", 401)
        val oidcConfig = config.oidc ?: reject("not_found", 404)
        val identity = requireNotNull(oidc).complete(code, pending.verifier, pending.nonce, pending.fresh)
        return db.transaction { c ->
            c.query("SELECT pg_advisory_xact_lock(740520261018)") { Unit }
            var user = c.query("SELECT ${userColumns()} FROM web_user WHERE issuer=? AND subject=? FOR UPDATE", oidcConfig.issuer, identity.subject, read = ::user).singleOrNull()
            if (user == null) {
                if (config.invitationOnly && identity.subject !in oidcConfig.adminSubjects)
                    (invitations ?: reject("invitation_required", 403)).admit(c, identity, pending.context)
                if (oidcConfig.registrationEnabled || config.invitationOnly) expect(identity.email != null, "email_verification_required", 403)
                expect(c.query("SELECT count(*) FROM web_user") { it.getLong(1) }.single() < 10000, "unavailable", 503)
                val id = UUID.randomUUID()
                c.execute("INSERT INTO web_user(id,issuer,subject,display_name,email,disabled,created_at,last_login_at) VALUES (?,?,?,?,?,false,?,?)", id, oidcConfig.issuer, identity.subject, identity.displayName, identity.email, clock(), clock())
                user = WebUser(id.toString(), identity.displayName, identity.email, identity.subject in oidcConfig.adminSubjects, false, clock(), clock(), oidcConfig.issuer, identity.subject)
            } else {
                expect(!user.disabled, "forbidden", 403)
                c.execute("UPDATE web_user SET display_name=?,email=?,last_login_at=? WHERE id=?", identity.displayName, identity.email, clock(), id(user.id))
            }
            newSession(c, user.id, identity.authenticatedAt, identity.email)
        }
    }
    private fun newSession(c: Connection, userId: String, authenticatedAt: Long? = null, verifiedEmail: String? = null): WebLoginComplete {
            val active = c.query("SELECT issuer,disabled FROM web_user WHERE id=? FOR UPDATE", id(userId)) { it.getString(1) to it.getBoolean(2) }.singleOrNull()
            expect(active != null && config.accepts(active.first) && !active.second, "unauthenticated", 401)
            c.execute("DELETE FROM web_session WHERE expires<=?", clock())
            expect(c.query("SELECT count(*) FROM web_session") { it.getLong(1) }.single() < 20000, "unavailable", 503)
            // Keep at most 20 sessions per account. Refusing a sign-in instead could lock an account out for 30 days.
            c.execute("UPDATE web_session SET revoked=true WHERE id IN (SELECT id FROM web_session WHERE user_id=? AND revoked=false AND expires>? ORDER BY expires DESC,id OFFSET 19)", id(userId), clock())
            val token = randomToken(); val expiry = clock() + SESSION
            c.execute("INSERT INTO web_session VALUES (?,?,?,?,?,?,false,?,?)", UUID.randomUUID(), digest(token), id(userId), randomToken(), clock(), expiry, authenticatedAt, verifiedEmail)
            return WebLoginComplete(token, expiry)
    }
    internal fun caller(c: Connection, token: String): WebCaller {
        requireToken(token)
        val row = c.query("SELECT s.id,s.csrf_token,s.expires,${userColumns("u")},s.authenticated_at,s.verified_email FROM web_session s JOIN web_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.revoked=false AND s.expires>?", digest(token), clock()) {
            WebCaller(it.getObject(1).toString(), user(it, 3), it.getString(2), it.getLong(3), authenticatedAt = (it.getObject(14) as? Number)?.toLong(), verifiedEmail = it.getString(15))
        }.singleOrNull() ?: reject("unauthenticated", 401)
        expect(config.accepts(row.user.issuer) && !row.user.disabled, "forbidden", 403)
        // Extended at most once a day; the HTTP layer then reissues the cookie.
        if (row.expires >= clock() + SESSION - DAY) return row
        c.execute("UPDATE web_session SET expires=? WHERE id=?", clock() + SESSION, id(row.session))
        return row.copy(expires = clock() + SESSION, renewed = true)
    }
    internal fun reader(token: String): WebCaller = db.transaction { c -> caller(c, token) }
    internal fun me(token: String): WebMe = me(reader(token))
    internal fun me(caller: WebCaller): WebMe = db.transaction { c ->
        current(c, caller)
        caller.me().copy(limits = config.accountLimits.view(c, caller.user.id))
    }
    internal fun csrf(token: String, origin: String?, csrf: String?): WebCaller = db.transaction { c ->
        val caller = caller(c, token)
        expect(origin == config.origin && csrf != null && MessageDigest.isEqual(digest(csrf), digest(caller.csrf)), "forbidden", 403)
        caller
    }
    internal fun logout(caller: WebCaller) = db.transaction { c ->
        current(c, caller)
        c.execute("UPDATE web_session SET revoked=true WHERE id=?", id(caller.session))
    }
    internal fun switchAccount(caller: WebCaller): AccountSwitch {
        val provider = oidc ?: reject("account_switch_unavailable", 409)
        val target = provider.switchAccount(provider.metadata())
        logout(caller)
        return AccountSwitch(target)
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
        if (update.disabled) {
            c.execute("UPDATE web_session SET revoked=true WHERE user_id=?", id(target))
            c.execute("UPDATE native_session SET revoked=true WHERE user_id=?", id(target))
        c.execute("DELETE FROM connection_ticket WHERE user_id=?", id(target))
        }
        c.query("SELECT ${userColumns()} FROM web_user WHERE id=?", id(target), read = ::user).single()
    }
    internal fun revokeUser(caller: WebCaller, target: String) = db.transaction { c ->
        expect(current(c, caller).user.admin, "forbidden", 403)
        expect(c.query("SELECT issuer FROM web_user WHERE id=? FOR UPDATE", id(target)) { config.accepts(it.getString(1)) }.singleOrNull() == true, "not_found", 404)
        c.execute("UPDATE web_session SET revoked=true WHERE user_id=?", id(target))
        c.execute("UPDATE native_session SET revoked=true WHERE user_id=?", id(target))
        c.execute("DELETE FROM connection_ticket WHERE user_id=?", id(target))
    }
    internal fun current(c: Connection, caller: WebCaller): WebCaller {
        accountRead(c)
        val active = c.query("SELECT s.revoked,s.expires,u.disabled,u.issuer FROM web_session s JOIN web_user u ON u.id=s.user_id WHERE s.id=? AND s.user_id=?", id(caller.session), id(caller.user.id)) { !it.getBoolean(1) && it.getLong(2) > clock() && !it.getBoolean(3) && config.accepts(it.getString(4)) }.singleOrNull()
        expect(active == true, "unauthenticated", 401)
        return caller
    }
    internal fun requireFresh(c: Connection, caller: WebCaller, password: String) {
        auth.limit("account-closure:${caller.user.id}", 5)
        if (caller.user.issuer == WebIdentityConfig.LOCAL_ISSUER) {
            expect(password.length in 1..1024, "reauthentication_required", 401)
            val credential = c.query("SELECT salt,password_hash FROM local_credential WHERE user_id=?", id(caller.user.id)) { it.getBytes(1) to it.getBytes(2) }.singleOrNull()
            expect(LocalPasswords.matches(password, credential?.first, credential?.second), "reauthentication_required", 401)
        } else expect(caller.authenticatedAt != null && caller.authenticatedAt >= clock() - 300000 && caller.authenticatedAt <= clock() + 30000, "reauthentication_required", 401)
    }
    private fun user(row: java.sql.ResultSet) = user(row, 0)
    private fun user(row: java.sql.ResultSet, offset: Int) = WebUser(row.getObject(offset + 1).toString(), row.getString(offset + 2), row.getString(offset + 3), config.administrator(row.getString(offset + 8), row.getString(offset + 4), row.getBoolean(offset + 9)), row.getBoolean(offset + 5), row.getLong(offset + 6), row.getLong(offset + 7), row.getString(offset + 8), row.getString(offset + 4), row.getString(offset + 10))
    private data class Pending(val browser: ByteArray, val nonce: ByteArray, val verifier: String, val expires: Long, val context: ByteArray?, val fresh: Boolean) { override fun toString() = "Pending([redacted])" }
}

private fun requireToken(value: String) { expect(value.length == 43 && value.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }, "unauthenticated", 401) }
internal fun userColumns(prefix: String = "") = listOf("id", "display_name", "email", "subject", "disabled", "created_at", "last_login_at", "issuer", "local_admin", "local_username").joinToString(",") { if (prefix.isEmpty()) it else "$prefix.$it" }

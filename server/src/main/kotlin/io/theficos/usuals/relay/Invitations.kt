package io.theficos.usuals.relay

import io.theficos.usuals.sync.Wire
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

@Serializable internal data class SendInvitation(val email: String, val locale: String = "en")
@Serializable internal data class InvitationView(val id: String, val email: String, val state: String, val delivery: String, val expiresAt: Long, val resendAt: Long, val senderId: String)
@Serializable internal data class InvitationPage(val invitations: List<InvitationView>)
@Serializable internal data class InvitationChallenge(val csrfToken: String, val expiresAt: Long)
internal data class InvitationStart(val token: String, val result: InvitationChallenge) { override fun toString() = "InvitationStart([redacted])" }
@Serializable internal data class InvitationSecret(val token: String) { override fun toString() = "InvitationSecret([redacted])" }
@Serializable internal data class InvitationPreview(val state: String, val inviter: String? = null, val listId: String? = null)
@Serializable internal data class InvitationAccepted(val list: ListView? = null)

/** Generic optional email sharing. The token is a pending invitation, never an authentication credential. */
internal class Invitations(private val relay: Relay, private val config: WebIdentityConfig,
    private val mail: InvitationMailer, private val limits: MailConfiguration,
    private val clock: () -> Long = { relay.auth.now() }) : AutoCloseable {
    private val db = relay.db
    private val cipher = relay.cipher
    @Volatile private var stopped = false
    private var worker: Thread? = null
    private val lifetime = 7L * 86400000
    private val contextLifetime = 30 * 60000L
    private val columns = "id,sender,list_id,email,token_hash,expires,state,accepted_by,last_sent_at,delivery,locale"
    private data class Row(val id: String, val sender: String, val list: String?, val email: String, val hash: ByteArray,
        val expires: Long, val state: String, val acceptedBy: String?, val lastSent: Long, val delivery: String, val locale: String) {
        fun view(now: Long) = InvitationView(id, email, if (state == "pending" && expires <= now) "expired" else state, delivery, expires, lastSent + 60000, sender)
        override fun toString() = "Invitation([redacted])"
    }
    private fun row(r: java.sql.ResultSet) = Row(r.getObject(1).toString(), r.getObject(2).toString(), r.getObject(3)?.toString(), r.getString(4),
        r.getBytes(5), r.getLong(6), r.getString(7), r.getObject(8)?.toString(), r.getLong(9), r.getString(10), r.getString(11))
    private fun find(c: Connection, invitation: String, lock: Boolean = false) =
        c.query("SELECT $columns FROM email_invitation WHERE id=?" + if (lock) " FOR UPDATE" else "", id(invitation), read = ::row).singleOrNull()
    private fun lockList(c: Connection, list: String) {
        expect(c.query("SELECT id FROM list WHERE id=? AND projection IS NOT NULL FOR UPDATE", id(list)) { Unit }.isNotEmpty(), "invitation_unavailable", 410)
    }
    private fun member(c: Connection, user: String, list: String) = c.query("SELECT 1 FROM list_member WHERE list_id=? AND user_id=? AND removed IS NULL", id(list), id(user)) { Unit }.isNotEmpty()
    private fun enabled(c: Connection, user: String) = c.query("SELECT issuer,disabled FROM web_user WHERE id=?", id(user)) { config.accepts(it.getString(1)) && !it.getBoolean(2) }.singleOrNull() == true
    private fun valid(c: Connection, row: Row): Boolean = row.state == "pending" && row.expires > clock() && enabled(c, row.sender) &&
        (if (row.list == null) c.query("SELECT issuer,subject,local_admin FROM web_user WHERE id=?", id(row.sender)) { config.administrator(it.getString(1), it.getString(2), it.getBoolean(3)) }.singleOrNull() == true
        else member(c, row.sender, row.list) && c.query("SELECT 1 FROM list WHERE id=? AND projection IS NOT NULL", id(row.list)) { Unit }.isNotEmpty())
    private fun authorizeSender(c: Connection, caller: WebCaller, list: String?, verify: (Connection) -> Unit) {
        verify(c)
        expect(caller.user.email != null && caller.user.issuer == config.oidc?.issuer, "email_verification_required", 403)
        if (list == null) expect(caller.user.admin, "forbidden", 403)
        else { lockList(c, list); expect(member(c, caller.user.id, list), "not_found", 404) }
    }
    private fun budget(sender: String, recipient: String) {
        relay.auth.limit("invitation:sender:$sender", limits.senderLimit)
        relay.auth.limit("invitation:recipient:$recipient", limits.recipientLimit)
        relay.auth.limit("invitation:instance", limits.instanceLimit)
    }
    fun send(caller: WebCaller, list: String?, request: SendInvitation, verify: (Connection) -> Unit): InvitationView {
        val email = emailAddress(request.email)
        expect(request.locale.length <= 15 && Regex("[a-z]{2,3}(?:-[A-Z]{2})?").matches(request.locale))
        val locale = mailLocale(request.locale)
        db.transaction { authorizeSender(it, caller, list, verify) }
        budget(caller.user.id, email)
        return db.transaction { c ->
            authorizeSender(c, caller, list, verify)
            // A shared budget lock bounds pending storage across accounts and replicas.
            c.query("SELECT pg_advisory_xact_lock(740520261023)") { Unit }
            clean(c)
            val existing = c.query("SELECT $columns FROM email_invitation WHERE sender=? AND list_id IS NOT DISTINCT FROM ? AND email=? AND state='pending' AND expires>? ORDER BY created_at DESC LIMIT 1 FOR UPDATE",
                id(caller.user.id), list?.let(::id), email, clock(), read = ::row).singleOrNull()
            if (existing != null) return@transaction existing.view(clock())
            expect(c.query("SELECT count(*) FROM email_invitation WHERE state='pending' AND expires>? AND sender=?", clock(), id(caller.user.id)) { it.getLong(1) }.single() < 50, "limit_reached", 409)
            expect(c.query("SELECT count(*) FROM email_invitation WHERE state='pending' AND expires>?", clock()) { it.getLong(1) }.single() < 2000, "limit_reached", 409)
            val invitation = UUID.randomUUID().toString(); val token = randomToken(); val now = clock()
            c.execute("INSERT INTO email_invitation(id,sender,list_id,email,token_hash,created_at,expires,last_sent_at,locale,mail_cipher,next_attempt) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id(invitation), id(caller.user.id), list?.let(::id), email, digest(token), now, now + lifetime, now, locale,
                sealed(invitation, token, email, caller.user.displayName.ifBlank { caller.user.email ?: "" }, locale), now)
            find(c, invitation)!!.view(now)
        }
    }
    fun page(caller: WebCaller, list: String?, verify: (Connection) -> Unit): InvitationPage = db.transaction { c ->
        verify(c)
        if (list == null) expect(caller.user.admin, "forbidden", 403)
        else expect(member(c, caller.user.id, list), "not_found", 404)
        InvitationPage(c.query("SELECT $columns FROM email_invitation WHERE list_id IS NOT DISTINCT FROM ?" +
            (if (list == null) " AND sender=?" else "") + " AND expires>? ORDER BY created_at DESC LIMIT 100",
            *(if (list == null) arrayOf<Any?>(null, id(caller.user.id), clock() - lifetime) else arrayOf<Any?>(id(list), clock() - lifetime)), read = ::row).map { it.view(clock()) })
    }
    fun change(caller: WebCaller, list: String?, invitation: String, resend: Boolean, verify: (Connection) -> Unit): InvitationView {
        val initial = db.transaction { c -> authorizeSender(c, caller, list, verify); find(c, invitation) } ?: reject("not_found", 404)
        expect(initial.list == list && (initial.sender == caller.user.id || list != null && db.transaction { c -> c.query("SELECT role FROM list_member WHERE list_id=? AND user_id=? AND removed IS NULL", id(list), id(caller.user.id)) { it.getString(1) }.singleOrNull() == "owner" }), "not_found", 404)
        if (resend) budget(caller.user.id, initial.email)
        return db.transaction { c ->
            authorizeSender(c, caller, list, verify)
            val row = find(c, invitation, true) ?: reject("not_found", 404)
            expect(row.list == list && row.state == "pending", "invitation_unavailable", 410)
            if (resend) {
                expect(valid(c, row), "invitation_unavailable", 410)
                expect(row.lastSent + 60000 <= clock(), "invitation_resend_later", 429)
                val token = randomToken()
                c.execute("UPDATE email_invitation SET token_hash=?,mail_cipher=?,delivery='queued',attempts=0,next_attempt=?,last_sent_at=?,expires=? WHERE id=?",
                    digest(token), sealed(row.id, token, row.email, senderName(c, row.sender), row.locale), clock(), clock(), clock() + lifetime, id(row.id))
            } else c.execute("UPDATE email_invitation SET state='revoked',delivery='cancelled',mail_cipher=NULL WHERE id=?", id(row.id))
            find(c, invitation)!!.view(clock())
        }
    }
    fun challenge(ip: String): InvitationStart {
        relay.auth.limit("invitation:context:$ip", 20)
        val token = randomToken(); val csrf = randomToken(); val expires = clock() + contextLifetime
        db.transaction { c ->
            c.execute("DELETE FROM invitation_context WHERE expires<=?", clock())
            expect(c.query("SELECT count(*) FROM invitation_context") { it.getLong(1) }.single() < 2000, "rate_limited", 429)
            c.execute("INSERT INTO invitation_context(hash,csrf,expires) VALUES (?,?,?)", digest(token), csrf, expires)
        }
        return InvitationStart(token, InvitationChallenge(csrf, expires))
    }
    fun attach(cookie: String, origin: String?, csrf: String?, secret: InvitationSecret) {
        expect(origin == config.origin && tokenShape(cookie) && csrf != null, "forbidden", 403)
        expect(tokenShape(secret.token), "invitation_unavailable", 410)
        db.transaction { c ->
            val expected = c.query("SELECT csrf FROM invitation_context WHERE hash=? AND expires>? FOR UPDATE", digest(cookie), clock()) { it.getString(1) }.singleOrNull()
            expect(expected != null && MessageDigest.isEqual(digest(expected), digest(csrf!!)), "forbidden", 403)
            val row = c.query("SELECT $columns FROM email_invitation WHERE token_hash=?", digest(secret.token), read = ::row).singleOrNull() ?: reject("invitation_unavailable", 410)
            expect(valid(c, row) || row.state == "accepted", "invitation_unavailable", 410)
            c.execute("UPDATE invitation_context SET invitation_id=?,token_hash=? WHERE hash=?", id(row.id), row.hash, digest(cookie))
        }
    }
    internal fun contextHash(c: Connection, cookie: String?): ByteArray? = cookie?.takeIf(::tokenShape)?.let { value ->
        c.query("SELECT hash FROM invitation_context WHERE hash=? AND invitation_id IS NOT NULL AND expires>?", digest(value), clock()) { it.getBytes(1) }.singleOrNull()
    }
    private fun context(c: Connection, hash: ByteArray?): Row? {
        if (hash == null) return null
        return c.query("SELECT " + columns.split(',').joinToString(",") { "i.$it" } +
            " FROM invitation_context x JOIN email_invitation i ON i.id=x.invitation_id AND i.token_hash=x.token_hash WHERE x.hash=? AND x.expires>?", hash, clock(), read = ::row).singleOrNull()
    }
    internal fun admit(c: Connection, identity: OidcIdentity, contextHash: ByteArray?) {
        val initial = context(c, contextHash) ?: reject("invitation_required", 403)
        initial.list?.let { lockList(c, it) }
        val row = find(c, initial.id, true) ?: reject("invitation_required", 403)
        expect(MessageDigest.isEqual(initial.hash, row.hash), "invitation_required", 403)
        expect(valid(c, row) && identity.email != null && emailAddress(identity.email) == row.email, "invitation_required", 403)
        expect(identity.authenticatedAt != null && identity.authenticatedAt >= clock() - 300000, "reauthentication_required", 401)
    }
    fun preview(cookie: String?, caller: WebCaller?, verify: (Connection) -> Unit): InvitationPreview = db.transaction { c ->
        val hash = cookie?.takeIf(::tokenShape)?.let(::digest)
        if (hash == null) return@transaction InvitationPreview("none")
        val row = context(c, hash) ?: return@transaction InvitationPreview("unavailable")
        if (caller == null) return@transaction InvitationPreview(if (valid(c, row)) "signin" else "unavailable")
        verify(c)
        if (row.state == "accepted") return@transaction InvitationPreview(
            if (row.acceptedBy == caller.user.id && (row.list == null || member(c, caller.user.id, row.list))) "accepted" else "unavailable", listId = row.list.takeIf { row.acceptedBy == caller.user.id })
        if (!valid(c, row)) return@transaction InvitationPreview("unavailable")
        if ((caller.verifiedEmail ?: caller.user.email)?.let(::emailAddress) != row.email) return@transaction InvitationPreview("wrong_account")
        if (!fresh(caller)) return@transaction InvitationPreview("reauthenticate")
        InvitationPreview("ready", senderName(c, row.sender))
    }
    fun accept(cookie: String?, caller: WebCaller, lists: Lists, verify: (Connection) -> Unit): InvitationAccepted {
        val result = db.transaction { c ->
            verify(c)
            expect(fresh(caller), "reauthentication_required", 401)
            val hash = cookie?.takeIf(::tokenShape)?.let(::digest) ?: reject("invitation_unavailable", 410)
            val initial = context(c, hash) ?: reject("invitation_unavailable", 410)
            initial.list?.let { lockList(c, it) }
            val row = find(c, initial.id, true) ?: reject("invitation_unavailable", 410)
            expect(MessageDigest.isEqual(row.hash, initial.hash) && row.expires > clock(), "invitation_unavailable", 410)
            if (row.state == "accepted") {
                expect(row.acceptedBy == caller.user.id && (row.list == null || member(c, caller.user.id, row.list)), "invitation_unavailable", 410)
                return@transaction InvitationAccepted(row.list?.let { lists.existingView(c, caller.user.id, it) })
            }
            expect(valid(c, row), "invitation_unavailable", 410)
            expect(caller.verifiedEmail?.let(::emailAddress) == row.email, "invitation_wrong_account", 403)
            val view = row.list?.let { lists.addInvited(c, row.sender, it, caller.user.id) }
            c.execute("UPDATE email_invitation SET state='accepted',accepted_by=?,delivery=CASE WHEN delivery='queued' THEN 'cancelled' ELSE delivery END,mail_cipher=NULL WHERE id=?", id(caller.user.id), id(row.id))
            InvitationAccepted(view)
        }
        result.list?.let { lists.waiters.changed(it.members.map { member -> member.id }) }
        return result
    }
    private fun fresh(caller: WebCaller) = caller.verifiedEmail != null && caller.authenticatedAt != null && caller.authenticatedAt >= clock() - 300000 && caller.authenticatedAt <= clock() + 30000
    private fun senderName(c: Connection, sender: String) = c.query("SELECT coalesce(nullif(display_name,''),email,local_username,'') FROM web_user WHERE id=?", id(sender)) { it.getString(1) }.single()
    private fun sealed(invitation: String, token: String, email: String, sender: String, locale: String) = cipher.sealMail(invitation,
        Wire.json.encodeToString(InvitationMail(email, sender, config.origin + "/join#" + token, locale)).toByteArray())
    private fun clean(c: Connection) {
        c.execute("UPDATE email_invitation SET delivery='cancelled',mail_cipher=NULL WHERE expires<=? AND delivery='queued'", clock())
        c.execute("DELETE FROM email_invitation WHERE expires<?", clock() - 30L * 86400000)
    }
    /** Locked outbox row across a bounded SMTP attempt: replicas skip it; SMTP retries can duplicate mail, never access. */
    fun deliverOne(): Boolean = db.transaction { c ->
        clean(c)
        val item = c.query("SELECT id,mail_cipher,attempts FROM email_invitation WHERE delivery='queued' AND next_attempt<=? ORDER BY next_attempt,id LIMIT 1 FOR UPDATE SKIP LOCKED", clock()) {
            Triple(it.getObject(1).toString(), it.getBytes(2), it.getInt(3))
        }.singleOrNull() ?: return@transaction false
        val row = find(c, item.first)!!
        if (!valid(c, row)) { c.execute("UPDATE email_invitation SET delivery='cancelled',mail_cipher=NULL WHERE id=?", id(row.id)); return@transaction true }
        try {
            mail.send(Wire.json.decodeFromString<InvitationMail>(cipher.openMail(row.id, item.second).decodeToString()))
            c.execute("UPDATE email_invitation SET delivery='sent',mail_cipher=NULL WHERE id=?", id(row.id))
        } catch (_: Exception) {
            val attempts = item.third + 1
            c.execute("UPDATE email_invitation SET attempts=?,next_attempt=?,delivery=?,mail_cipher=CASE WHEN ? THEN NULL ELSE mail_cipher END WHERE id=?",
                attempts, clock() + minOf(3600000L, 30000L * (1L shl minOf(attempts - 1, 7))), if (attempts >= 8) "failed" else "queued", attempts >= 8, id(row.id))
        }
        true
    }
    fun start() {
        check(worker == null)
        worker = Thread({ while (!stopped) { try { if (!deliverOne()) Thread.sleep(1000) } catch (_: InterruptedException) { break } catch (_: Exception) { try { Thread.sleep(2000) } catch (_: InterruptedException) { break } } } }, "invitation-outbox").apply { isDaemon = true; start() }
    }
    override fun close() { stopped = true; worker?.interrupt(); mail.close() }
}
internal fun tokenShape(value: String) = value.length == 43 && value.all { it.code < 128 && (it.isLetterOrDigit() || it in "-_") }

package io.theficos.usuals.relay

import io.theficos.usuals.sync.Wire
import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection

internal fun accountRead(c: Connection) { c.query("SELECT pg_advisory_xact_lock_shared(740520261025)") { Unit } }
@Serializable data class ClosureRequest(val id: String, val confirm: String, val deleteOwnedLists: Boolean = false, val sharedConfirmation: String = "", val password: String = "") {
    override fun toString() = "ClosureRequest([redacted])"
}
@Serializable data class ClosureStatus(val id: String, val accountId: String, val state: String, val issuer: String? = null, val subject: String? = null)
@Serializable internal data class AccountExport(val account: WebUser, val lists: ListPage)

/** Generic account exit. The separate operator key can execute only a request freshly authorized by its account. */
class AccountLifecycle(private val relay: Relay, private val web: WebIdentity, key: ByteArray?, private val clock: () -> Long = System::currentTimeMillis) {
    private val authorization = key?.let { require(it.size == 32); ("Bearer " + Wire.encode(it)).toByteArray() }
    private val lists = Lists(relay, web.config)
    val serviceEnabled get() = authorization != null
    fun authenticate(header: String?) { expect(authorization != null && header != null && header.length <= 128 && MessageDigest.isEqual(authorization, header.toByteArray()), "unauthenticated", 401) }
    internal fun authorize(caller: WebCaller, request: ClosureRequest): ClosureStatus = relay.db.transaction { c ->
        web.current(c, caller); web.requireFresh(c, caller, request.password)
        id(request.id)
        expect(request.confirm == "DELETE", "deletion_confirmation_required")
        expect(!request.deleteOwnedLists || request.sharedConfirmation == "DELETE SHARED LISTS", "shared_deletion_confirmation_required")
        if (!request.deleteOwnedLists) lists.requireNoSharedOwned(c, caller.user.id)
        val prior = c.query("SELECT id,delete_owned_lists FROM account_closure WHERE user_id=?", id(caller.user.id)) { it.getObject(1).toString() to it.getBoolean(2) }.singleOrNull()
        if (prior != null) {
            expect(prior.first == request.id, "closure_already_pending", 409)
            c.execute("UPDATE account_closure SET delete_owned_lists=? WHERE id=? AND closed_at IS NULL", request.deleteOwnedLists, id(request.id))
        }
        else c.execute("INSERT INTO account_closure(id,user_id,delete_owned_lists,issuer,subject,created_at) VALUES (?,?,?,?,?,?)", id(request.id), id(caller.user.id), request.deleteOwnedLists, caller.user.issuer, caller.user.subject, clock())
        status(c, caller.user.id, request.id)
    }
    fun status(user: String, request: String): ClosureStatus = relay.db.transaction { status(it, user, request) }
    private fun status(c: Connection, user: String, request: String): ClosureStatus = c.query("SELECT closed_at,purged_at,issuer,subject FROM account_closure WHERE id=? AND user_id=?", id(request), id(user)) {
        ClosureStatus(request, user, if (it.getObject(2) != null) "purged" else if (it.getObject(1) != null) "closed" else "authorized", it.getString(3), it.getString(4))
    }.singleOrNull() ?: reject("not_found", 404)
    fun close(user: String, request: String): ClosureStatus {
        var woken = emptyList<String>()
        val result = relay.db.transaction { c ->
            c.query("SELECT pg_advisory_xact_lock(740520261025)") { Unit }
            val current = status(c, user, request)
            if (current.state != "authorized") return@transaction current
            val delete = c.query("SELECT delete_owned_lists FROM account_closure WHERE id=? FOR UPDATE", id(request)) { it.getBoolean(1) }.single()
            c.query("SELECT 1 FROM web_user WHERE id=? FOR UPDATE", id(user)) { Unit }
            if (!delete) lists.requireNoSharedOwned(c, user)
            woken = lists.closeAccount(c, user, clock())
            c.execute("DELETE FROM email_invitation WHERE sender=? OR accepted_by=? OR email=(SELECT email FROM web_user WHERE id=?)", id(user), id(user), id(user))
            for (table in listOf("connection_offer", "connection_ticket", "native_session", "web_session", "local_credential", "ai_preference", "ai_usage", "ai_usage_monthly", "ai_vision_check", "account_limit")) c.execute("DELETE FROM $table WHERE user_id=?", id(user))
            c.execute("UPDATE web_user SET disabled=true,display_name='',email=NULL,local_username=NULL,local_admin=false,last_login_at=0 WHERE id=?", id(user))
            c.execute("UPDATE account_closure SET closed_at=? WHERE id=?", clock(), id(request))
            status(c, user, request)
        }
        lists.waiters.changed(woken)
        return result
    }
    fun purgeIdentity(user: String, request: String): ClosureStatus = relay.db.transaction { c ->
        val current = status(c, user, request)
        expect(current.state != "authorized", "account_not_closed", 409)
        c.execute("UPDATE web_user SET issuer='urn:usuals:closed',subject=id::text WHERE id=? AND disabled", id(user))
        c.execute("UPDATE account_closure SET issuer=NULL,subject=NULL,purged_at=COALESCE(purged_at,?) WHERE id=?", clock(), id(request))
        status(c, user, request)
    }
    companion object {
        fun fromEnvironment(relay: Relay, web: WebIdentity, environment: Map<String, String> = System.getenv()) = AccountLifecycle(relay, web,
            environment["USUALS_ACCOUNT_LIFECYCLE_KEY_FILE"]?.let { AiConfiguration.readKey(Path.of(it)) })
    }
}

package io.theficos.usuals.relay

import io.theficos.usuals.sync.Wire
import java.nio.file.Path
import java.security.MessageDigest

/** A narrowly scoped, optional operator credential. It cannot create accounts or change list membership. */
class AccountPolicy(private val db: Database, private val limits: AccountLimits, key: ByteArray, private val clock: () -> Long = System::currentTimeMillis) {
    private val authorization = ("Bearer " + Wire.encode(key)).toByteArray()
    init { require(key.size == 32) }
    fun authenticate(header: String?) {
        expect(header != null && header.length <= 128 && MessageDigest.isEqual(authorization, header.toByteArray()), "unauthenticated", 401)
    }
    fun put(user: String, update: AccountPolicyUpdate): AccountLimitView = db.transaction { c ->
        expect(c.query("SELECT 1 FROM web_user WHERE id=? AND NOT disabled FOR NO KEY UPDATE", id(user)) { Unit }.isNotEmpty(), "not_found", 404)
        limits.lockMemberPolicy(c, user)
        val now = clock()
        expect(update.expiresAt > now && update.expiresAt <= now + 300000, "invalid_policy_expiry", 400)
        c.execute("INSERT INTO account_limit(user_id,max_owned_lists,max_list_members,fallback_owned_lists,fallback_list_members,expires_at) VALUES (?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET max_owned_lists=EXCLUDED.max_owned_lists,max_list_members=EXCLUDED.max_list_members,fallback_owned_lists=EXCLUDED.fallback_owned_lists,fallback_list_members=EXCLUDED.fallback_list_members,expires_at=EXCLUDED.expires_at",
            id(user), update.limits.maxOwnedLists, update.limits.maxListMembers, update.fallback.maxOwnedLists, update.fallback.maxListMembers, update.expiresAt)
        c.execute("UPDATE account_limit SET monthly_ai_requests=?,fallback_monthly_ai_requests=? WHERE user_id=?", update.limits.monthlyAiRequests, update.fallback.monthlyAiRequests, id(user))
        requireNotNull(limits.view(c, user))
    }
    override fun toString() = "AccountPolicy([redacted])"
    companion object {
        fun fromEnvironment(db: Database, limits: AccountLimits, environment: Map<String, String> = System.getenv()): AccountPolicy? =
            environment["USUALS_ACCOUNT_POLICY_KEY_FILE"]?.let { AccountPolicy(db, limits, AiConfiguration.readKey(Path.of(it))) }
    }
}

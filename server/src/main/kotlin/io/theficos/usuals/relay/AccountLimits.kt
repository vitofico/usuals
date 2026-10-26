package io.theficos.usuals.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.sql.Connection
import java.util.UUID

/** Optional operator policy keyed by the existing account id. No subscription or sharing identity is created. */
@Serializable data class AccountLimit(val maxOwnedLists: Int? = null, val maxListMembers: Int? = null, val monthlyAiRequests: Int? = null) {
    init {
        require(maxOwnedLists == null || maxOwnedLists in 0..500) { "invalid_account_limits" }
        require(maxListMembers == null || maxListMembers in 1..50) { "invalid_account_limits" }
        require(monthlyAiRequests == null || monthlyAiRequests in 0..10000) { "invalid_account_limits" }
        require(maxOwnedLists != null || maxListMembers != null) { "invalid_account_limits" }
    }
}
@Serializable data class AccountLimitView(val maxOwnedLists: Int? = null, val maxListMembers: Int? = null, val ownedLists: Int, val expiresAt: Long? = null, val monthlyAiRequests: Int? = null)
@Serializable data class AccountPolicyUpdate(val limits: AccountLimit, val fallback: AccountLimit, val expiresAt: Long) {
    init { require(listOf(limits.maxOwnedLists, limits.maxListMembers, fallback.maxOwnedLists, fallback.maxListMembers).all { it != null }) { "invalid_account_policy" } }
}

class AccountLimits(values: Map<String, AccountLimit> = emptyMap(), private val defaultLimit: AccountLimit? = null, private val clock: () -> Long = System::currentTimeMillis) {
    private val values = values.toMap()
    init { require(this.values.size <= 10000 && this.values.keys.all(::canonicalAccountId)) { "invalid_account_limits" } }
    fun ownedLists(user: String) = (values[user] ?: defaultLimit)?.maxOwnedLists ?: 500
    fun members(owner: String) = (values[owner] ?: defaultLimit)?.maxListMembers ?: 50
    private fun dynamic(c: Connection, user: String): Pair<AccountLimit, Long>? = c.query("SELECT max_owned_lists,max_list_members,fallback_owned_lists,fallback_list_members,expires_at,monthly_ai_requests,fallback_monthly_ai_requests FROM account_limit WHERE user_id=?", id(user)) {
        val active = it.getLong(5) > clock()
        AccountLimit(it.getInt(if (active) 1 else 3), it.getInt(if (active) 2 else 4), (it.getObject(if (active) 6 else 7) as? Number)?.toInt()) to it.getLong(5)
    }.singleOrNull()
    internal fun effective(c: Connection, user: String): AccountLimit? = dynamic(c, user)?.first ?: values[user] ?: defaultLimit
    internal fun ownedLists(c: Connection, user: String) = effective(c, user)?.maxOwnedLists ?: 500
    internal fun members(c: Connection, owner: String) = effective(c, owner)?.maxListMembers ?: 50
    internal fun view(c: Connection, user: String): AccountLimitView? {
        val dynamic = dynamic(c, user)
        val limit = dynamic?.first ?: values[user] ?: defaultLimit ?: return null
        return AccountLimitView(limit.maxOwnedLists, limit.maxListMembers,
            c.query("SELECT count(*) FROM list WHERE owner=? AND deleted_at IS NULL", id(user)) { it.getInt(1) }.single(), dynamic?.second, limit.monthlyAiRequests)
    }
    companion object {
        fun fromEnvironment(environment: Map<String, String>): AccountLimits {
            val text = environment["USUALS_ACCOUNT_LIMITS"] ?: "{}"
            require(text.length <= 1000000) { "invalid_account_limits" }
            val decoded = runCatching { Json.decodeFromString<Map<String, AccountLimit>>(text) }.getOrElse { throw IllegalArgumentException("invalid_account_limits") }
            val default = environment["USUALS_DEFAULT_ACCOUNT_LIMITS"]?.let { raw ->
                require(raw.length <= 1024) { "invalid_account_limits" }
                runCatching { Json.decodeFromString<AccountLimit>(raw) }.getOrElse { throw IllegalArgumentException("invalid_account_limits") }
            }
            return AccountLimits(decoded, defaultLimit = default)
        }
        private fun canonicalAccountId(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}

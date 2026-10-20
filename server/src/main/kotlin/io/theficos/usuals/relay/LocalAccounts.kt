package io.theficos.usuals.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import io.theficos.usuals.sync.Wire
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

@Serializable data class LocalChallenge(val csrfToken: String, val expiresAt: Long) { override fun toString() = "LocalChallenge([redacted])" }
@Serializable data class LocalLoginRequest(val username: String, val password: String) { override fun toString() = "LocalLoginRequest([redacted])" }
@Serializable data class LocalActivateRequest(val username: String, val setupToken: String, val password: String) { override fun toString() = "LocalActivateRequest([redacted])" }
@Serializable data class LocalPasswordRequest(val currentPassword: String, val newPassword: String) { override fun toString() = "LocalPasswordRequest([redacted])" }
@Serializable data class LocalCreateRequest(val username: String, val displayName: String = "")
@Serializable data class LocalSetup(val user: WebUser, val setupToken: String, val expiresAt: Long) { override fun toString() = "LocalSetup([redacted])" }
internal data class LocalChallengeStart(val browserToken: String, val result: LocalChallenge) { override fun toString() = "LocalChallengeStart([redacted])" }
internal data class LocalSetupValue(val userId: String, val token: String, val expires: Long) { override fun toString() = "LocalSetupValue([redacted])" }
@Serializable private data class OperatorSetup(val username: String, val setupToken: String, val expiresAt: Long) { override fun toString() = "OperatorSetup([redacted])" }

internal class LocalAccounts(private val config: WebIdentityConfig, private val db: Database, private val auth: Auth, private val clock: () -> Long) {
    private fun enabled() { expect(config.localEnabled, "not_found", 404) }
    fun challenge(ip: String): LocalChallengeStart {
        enabled(); auth.limit("local-challenge:$ip", 30)
        val browser = randomToken(); val csrf = randomToken(); val expires = clock() + 300000
        db.transaction { c ->
            c.execute("DELETE FROM local_login WHERE expires<=?", clock())
            expect(c.query("SELECT count(*) FROM local_login") { it.getLong(1) }.single() < 1000, "rate_limited", 429)
            c.execute("INSERT INTO local_login VALUES (?,?,?)", digest(browser), digest(csrf), expires)
        }
        return LocalChallengeStart(browser, LocalChallenge(csrf, expires))
    }
    fun consumeChallenge(browser: String, origin: String?, csrf: String?) {
        enabled()
        expect(browser.length == 43 && csrf != null && csrf.length == 43 && origin == config.origin, "forbidden", 403)
        val row = db.transaction { c -> c.query("DELETE FROM local_login WHERE browser_hash=? RETURNING csrf_hash,expires", digest(browser)) { it.getBytes(1) to it.getLong(2) }.singleOrNull() }
        expect(row != null && row.second > clock() && MessageDigest.isEqual(row.first, digest(requireNotNull(csrf))), "forbidden", 403)
    }
    fun login(request: LocalLoginRequest, ip: String, session: (Connection, String) -> WebLoginComplete): WebLoginComplete {
        enabled(); rate(ip, request.username)
        val username = try { LocalPasswords.username(request.username) } catch (_: RelayError) { "" }
        return db.transaction { c ->
            c.query("SELECT pg_advisory_xact_lock(740520261018)") { Unit }
            val account = c.query("SELECT id,disabled FROM web_user WHERE local_username=? AND issuer=? FOR UPDATE", username, WebIdentityConfig.LOCAL_ISSUER) { it.getObject(1).toString() to it.getBoolean(2) }.singleOrNull()
            val credential = account?.let { value -> c.query("SELECT salt,password_hash FROM local_credential WHERE user_id=?", id(value.first)) { it.getBytes(1) to it.getBytes(2) }.singleOrNull() }
            val matched = LocalPasswords.matches(request.password, credential?.first, credential?.second)
            expect(matched && account != null && !account.second, "unauthenticated", 401)
            c.execute("UPDATE web_user SET last_login_at=? WHERE id=?", clock(), id(requireNotNull(account).first))
            session(c, account.first)
        }
    }
    fun activate(request: LocalActivateRequest, ip: String, session: (Connection, String) -> WebLoginComplete): WebLoginComplete {
        enabled(); rate(ip, request.username); LocalPasswords.validate(request.password)
        val username = LocalPasswords.username(request.username)
        expect(request.setupToken.length == 43, "unauthenticated", 401)
        return db.transaction { c ->
            c.query("SELECT pg_advisory_xact_lock(740520261018)") { Unit }
            val account = c.query("SELECT id,disabled FROM web_user WHERE local_username=? AND issuer=? FOR UPDATE", username, WebIdentityConfig.LOCAL_ISSUER) { it.getObject(1).toString() to it.getBoolean(2) }.singleOrNull()
                ?: reject("unauthenticated", 401)
            val setup = c.query("SELECT setup_hash,setup_expires FROM local_credential WHERE user_id=?", id(account.first)) { it.getBytes(1) to it.getLong(2) }.single()
            expect(!account.second && setup.first != null && setup.second > clock() && MessageDigest.isEqual(setup.first, digest(request.setupToken)), "unauthenticated", 401)
            val salt = LocalPasswords.salt(); val hash = LocalPasswords.hash(request.password, salt)
            c.execute("UPDATE local_credential SET password_hash=?,salt=?,setup_hash=NULL,setup_expires=NULL WHERE user_id=?", hash, salt, id(account.first))
            c.execute("UPDATE web_session SET revoked=true WHERE user_id=?", id(account.first))
            c.execute("UPDATE native_session SET revoked=true WHERE user_id=?", id(account.first))
            c.execute("DELETE FROM connection_ticket WHERE user_id=?", id(account.first))
            c.execute("UPDATE web_user SET last_login_at=? WHERE id=?", clock(), id(account.first))
            session(c, account.first)
        }
    }
    fun password(caller: WebCaller, request: LocalPasswordRequest, ip: String) {
        enabled(); rate(ip, caller.user.id); LocalPasswords.validate(request.newPassword)
        expect(caller.user.issuer == WebIdentityConfig.LOCAL_ISSUER, "invalid", 400)
        db.transaction { c ->
            val enabled = c.query("SELECT disabled FROM web_user WHERE id=? FOR UPDATE", id(caller.user.id)) { !it.getBoolean(1) }.single()
            expect(enabled && c.query("SELECT 1 FROM web_session WHERE id=? AND user_id=? AND revoked=false AND expires>?", id(caller.session), id(caller.user.id), clock()) { true }.singleOrNull() == true, "unauthenticated", 401)
            val credential = c.query("SELECT salt,password_hash FROM local_credential WHERE user_id=?", id(caller.user.id)) { it.getBytes(1) to it.getBytes(2) }.single()
            expect(LocalPasswords.matches(request.currentPassword, credential.first, credential.second), "unauthenticated", 401)
            val salt = LocalPasswords.salt(); val hash = LocalPasswords.hash(request.newPassword, salt)
            c.execute("UPDATE local_credential SET password_hash=?,salt=?,setup_hash=NULL,setup_expires=NULL WHERE user_id=?", hash, salt, id(caller.user.id))
            c.execute("UPDATE web_session SET revoked=true WHERE user_id=?", id(caller.user.id))
            c.execute("UPDATE native_session SET revoked=true WHERE user_id=?", id(caller.user.id))
            c.execute("DELETE FROM connection_ticket WHERE user_id=?", id(caller.user.id))
        }
    }
    fun create(c: Connection, request: LocalCreateRequest, administrator: Boolean): LocalSetupValue {
        enabled(); val username = LocalPasswords.username(request.username)
        expect(request.displayName.length <= 200 && request.displayName.none(Char::isISOControl))
        c.query("SELECT pg_advisory_xact_lock(740520261018)") { Unit }
        expect(c.query("SELECT count(*) FROM web_user") { it.getLong(1) }.single() < 10000, "unavailable", 503)
        val userId = UUID.randomUUID(); val subject = UUID.randomUUID()
        c.execute("INSERT INTO web_user(id,issuer,subject,display_name,email,disabled,created_at,last_login_at,local_admin,local_username) VALUES (?,?,?,?,NULL,false,?,?,?,?)",
            userId, WebIdentityConfig.LOCAL_ISSUER, subject.toString(), request.displayName, clock(), 0L, administrator, username)
        c.execute("INSERT INTO local_credential(user_id) VALUES (?)", userId)
        return reset(c, userId.toString())
    }
    fun reset(c: Connection, target: String): LocalSetupValue {
        enabled()
        expect(c.query("SELECT 1 FROM web_user WHERE id=? AND issuer=? FOR UPDATE", id(target), WebIdentityConfig.LOCAL_ISSUER) { true }.singleOrNull() == true, "invalid", 400)
        val token = randomToken(); val expires = clock() + 86400000
        c.execute("UPDATE local_credential SET password_hash=NULL,salt=NULL,setup_hash=?,setup_expires=? WHERE user_id=?", digest(token), expires, id(target))
        c.execute("UPDATE web_session SET revoked=true WHERE user_id=?", id(target))
        c.execute("UPDATE native_session SET revoked=true WHERE user_id=?", id(target))
        c.execute("DELETE FROM connection_ticket WHERE user_id=?", id(target))
        return LocalSetupValue(target, token, expires)
    }
    fun operator(rawUsername: String, path: Path, reset: Boolean) {
        enabled(); val username = LocalPasswords.username(rawUsername)
        require(path.isAbsolute && path.normalize() == path)
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        var successful = false
        try {
            db.transaction { c ->
                val value = if (reset) {
                    val target = c.query("SELECT id FROM web_user WHERE local_username=? AND issuer=?", username, WebIdentityConfig.LOCAL_ISSUER) { it.getObject(1).toString() }.singleOrNull() ?: reject("not_found", 404)
                    reset(c, target)
                } else create(c, LocalCreateRequest(username), true)
                val bytes = Wire.json.encodeToString(OperatorSetup(username, value.token, value.expires)).toByteArray()
                FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> val buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true) }
                FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
            }
            successful = true
        } finally { if (!successful) Files.deleteIfExists(path) }
    }
    private fun rate(ip: String, username: String) {
        expect(username.length <= 256)
        auth.limit("local-password-ip:$ip", 10)
        auth.limit("local-password-user:${username.lowercase(java.util.Locale.ROOT)}", 10)
    }
}

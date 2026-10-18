package io.theficos.comanda.relay

import io.theficos.comanda.sync.*
import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.asContextElement

internal data class Caller(val device: String, val kind: String, val role: String = "", val member: String = "")
internal data class Household(val manifest: Manifest, val hash: String, val genesis: String, val frozen: Boolean)
internal fun Connection.household(household: String, lock: Boolean = true): Household = query(
    "SELECT body,manifest_hash,genesis,frozen FROM household WHERE id=?" + if (lock) " FOR UPDATE" else "", id(household),
) { row -> Household(Wire.json.decodeFromString<Manifest>(row.getBytes(1).toString(Charsets.UTF_8)), Wire.encode(row.getBytes(2)), Wire.encode(row.getBytes(3)), row.getBoolean(4)) }
    .singleOrNull() ?: reject("not_found", 404)

class Auth(private val db: Database, private val identity: Identity, val origin: String, private val webIssuer: String? = null, private val localIdentity: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val browserCookie = ThreadLocal<String?>()
    internal fun browserContext(cookie: String?) = browserCookie.asContextElement(cookie?.takeIf { it.length == 43 })
    internal fun rate(c: Connection, key: String, limit: Int) {
        val window = clock() / 60000
        c.execute("DELETE FROM rate_bucket WHERE minute < ?", window)
        expect(c.query("SELECT count(*) FROM rate_bucket") { it.getLong(1) }.single() < 20000, "rate_limited", 429)
        val count = c.query("INSERT INTO rate_bucket VALUES (?,?,1) ON CONFLICT(key) DO UPDATE SET count=CASE WHEN rate_bucket.minute=EXCLUDED.minute THEN rate_bucket.count+1 ELSE 1 END, minute=EXCLUDED.minute RETURNING count", Wire.decode(Wire.hash(key.toByteArray()), 32), window) { it.getInt(1) }.single()
        expect(count <= limit, "rate_limited", 429)
    }
    fun limit(key: String, count: Int) = db.transaction { rate(it, key, count) }
    fun challenge(request: ChallengeRequest, ip: String): Challenge {
        limit("challenge:$ip", 30)
        return db.transaction { c ->
        id(request.deviceId)
        expect(request.purpose in setOf("bootstrap", "session", "join", "recovery"))
        c.execute("DELETE FROM auth_challenge WHERE expires < ?", clock())
        expect(c.query("SELECT count(*) FROM auth_challenge") { it.getLong(1) }.single() < 20000, "rate_limited", 429)
        denyFrozen(c, request.deviceId)
        val known = request.purpose in setOf("session", "recovery")
        val signing = if (known) {
            val rows = c.query("SELECT p.signing,m.role FROM principal p JOIN membership m ON m.device=p.id WHERE p.id=? AND m.active=true", id(request.deviceId)) { Wire.encode(it.getBytes(1)) to it.getString(2) }
            expect(rows.any { (it.second == "recovery") == (request.purpose == "recovery") }, "forbidden", 403)
            rows.first().first.also { expect(request.signingPublicKey == null || request.signingPublicKey == it) }
        } else checkNotNull(request.signingPublicKey).also { Wire.decode(it, 32) }
        val result = Challenge(UUID.randomUUID().toString(), randomToken(), identity.serverId, clock() + 60000, request.purpose)
        c.execute("INSERT INTO auth_challenge VALUES (?,?,?,?,?,?)", id(result.challengeId), id(request.deviceId), request.purpose,
            Wire.decode(result.nonce, 32), Wire.decode(signing, 32), result.expiresAt)
        result
        }
    }
    internal fun consume(c: Connection, proof: Proof, purposes: Set<String>): Pair<String, String> {
        denyFrozen(c, proof.deviceId)
        val value = c.query("SELECT purpose,nonce,signing,expires,device FROM auth_challenge WHERE id=? FOR UPDATE", id(proof.challengeId)) {
            listOf(it.getString(1), Wire.encode(it.getBytes(2)), Wire.encode(it.getBytes(3)), it.getLong(4).toString(), it.getObject(5).toString())
        }.singleOrNull() ?: reject("unauthenticated", 401)
        expect(value[0] in purposes && value[4] == proof.deviceId && value[3].toLong() > clock(), "unauthenticated", 401)
        try { verify(value[2], proof.signature, Wire.auth(origin, identity.serverId, proof.deviceId, proof.challengeId, value[1], value[3].toLong(), value[0], value[2])) }
        catch (_: RelayError) { reject("unauthenticated", 401) }
        c.execute("DELETE FROM auth_challenge WHERE id=?", id(proof.challengeId))
        return value[0] to value[2]
    }
    fun session(proof: Proof, webSession: String? = null): Session = db.transaction { c ->
        // Account binding and session issuance share this lock. Android principals stay unbound.
        expect(c.query("SELECT id FROM principal WHERE id=? FOR UPDATE", id(proof.deviceId)) { true }.singleOrNull() == true, "unauthenticated", 401)
        val purpose = consume(c, proof, setOf("session", "recovery")).first
        val active = c.query("SELECT role FROM membership WHERE device=? AND active=true", id(proof.deviceId)) { it.getString(1) }
        expect(active.any { (it == "recovery") == (purpose == "recovery") }, "forbidden", 403)
        val token = randomToken(); val kind = if (purpose == "recovery") "recovery" else "device"
        val expiry = clock() + if (kind == "recovery") 300000 else 900000
        val bound = c.query("SELECT user_id FROM web_device WHERE device=?", id(proof.deviceId)) { it.getObject(1).toString() }.singleOrNull()
        if (bound != null) {
            expect(kind == "device" && webSession != null && (webIssuer != null || localIdentity), "unauthenticated", 401)
            expect(c.query("SELECT 1 FROM web_session s JOIN web_user u ON u.id=s.user_id WHERE s.id=? AND s.token_hash=? AND u.id=? AND u.disabled=false AND (u.issuer=? OR u.issuer=?) AND s.revoked=false AND s.expires>?", id(requireNotNull(webSession)), digest(browserCookie.get() ?: ""), id(bound), webIssuer ?: "", if (localIdentity) WebIdentityConfig.LOCAL_ISSUER else "", clock()) { true }.singleOrNull() == true, "unauthenticated", 401)
        }
        c.execute("DELETE FROM auth_session WHERE expires < ?", clock())
        c.execute("INSERT INTO auth_session(hash,device,kind,expires) VALUES (?,?,?,?)", tokenHash(token), id(proof.deviceId), kind, expiry)
        if (bound != null) c.execute("INSERT INTO web_device_session VALUES (?,?)", tokenHash(token), id(requireNotNull(webSession)))
        Session(token, expiry)
    }
    internal fun caller(c: Connection, token: String): Caller {
        val row = c.query("SELECT device,kind,expires,revoked FROM auth_session WHERE hash=?", tokenHash(token)) {
            listOf(it.getObject(1).toString(), it.getString(2), it.getLong(3).toString(), it.getBoolean(4).toString())
        }.singleOrNull() ?: reject("unauthenticated", 401)
        denyFrozen(c, row[0])
        expect(row[2].toLong() > clock(), "unauthenticated", 401)
        expect(row[3] == "false", "forbidden", 403)
        val bound = c.query("SELECT user_id FROM web_device WHERE device=?", id(row[0])) { it.getObject(1).toString() }.singleOrNull()
        if (bound != null) {
            expect((webIssuer != null || localIdentity) && c.query("SELECT 1 FROM web_device_session d JOIN web_session s ON s.id=d.web_session_id JOIN web_user u ON u.id=s.user_id WHERE d.auth_hash=? AND s.token_hash=? AND u.id=? AND u.disabled=false AND (u.issuer=? OR u.issuer=?) AND s.revoked=false AND s.expires>?", tokenHash(token), digest(browserCookie.get() ?: ""), id(bound), webIssuer ?: "", if (localIdentity) WebIdentityConfig.LOCAL_ISSUER else "", clock()) { true }.singleOrNull() == true, "unauthenticated", 401)
        }
        return Caller(row[0], row[1])
    }
    internal fun authorize(c: Connection, token: String, household: String, owner: Boolean = false, recovery: Boolean = false): Pair<Caller, Household> {
        val caller = caller(c, token)
        val state = c.household(household)
        val membership = c.query("SELECT role,member,active FROM membership WHERE household=? AND device=?", id(household), id(caller.device)) {
            Triple(it.getString(1), it.getObject(2).toString(), it.getBoolean(3))
        }.singleOrNull() ?: reject("not_found", 404)
        expect(!state.frozen, "recovery_required", 503)
        expect(membership.third, "forbidden", 403)
        expect((caller.kind == "recovery") == (membership.first == "recovery"), "forbidden", 403)
        expect(caller.kind != "recovery" || recovery, "forbidden", 403)
        expect(!owner || membership.first == "owner" || recovery && membership.first == "recovery", "forbidden", 403)
        return caller.copy(role = membership.first, member = membership.second) to state
    }
    private fun denyFrozen(c: Connection, device: String) {
        expect(c.query("SELECT 1 FROM membership m JOIN household h ON h.id=m.household WHERE m.device=? AND h.frozen=true LIMIT 1", id(device)) { true }.isEmpty(), "recovery_required", 503)
    }
    fun bootstrapToken(): String = randomToken().also { token -> db.transaction { c ->
        c.execute("DELETE FROM bootstrap_token WHERE expires < ?", clock())
        c.execute("INSERT INTO bootstrap_token VALUES (?,?)", tokenHash(token), clock() + 86400000)
    } }
    internal fun checkBootstrap(c: Connection, token: String) {
        expect(c.query("SELECT 1 FROM bootstrap_token WHERE hash=? AND expires > ?", tokenHash(token), clock()) { true }.singleOrNull() == true, "unauthenticated", 401)
    }
    internal fun consumeBootstrap(c: Connection, token: String) {
        expect(c.execute("DELETE FROM bootstrap_token WHERE hash=? AND expires > ?", tokenHash(token), clock()) == 1, "unauthenticated", 401)
    }
    internal fun now() = clock()
    private fun tokenHash(token: String) = Wire.decode(Wire.hash(Wire.decode(token, 32)), 32)
}

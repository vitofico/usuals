package io.theficos.usuals.relay

import com.google.crypto.tink.subtle.Random
import java.util.UUID

internal fun uuid() = UUID.randomUUID().toString()

/** A disposable schema in the PostgreSQL that server/scripts/test-local starts. */
internal class TestDatabase : AutoCloseable {
    private val schema = "test_" + uuid().replace("-", "")
    private val url = checkNotNull(System.getenv("USUALS_TEST_JDBC_URL")) { "Run server/scripts/test-local with disposable PostgreSQL" }
    private val user = checkNotNull(System.getenv("USUALS_TEST_DB_USER"))
    private val password = checkNotNull(System.getenv("USUALS_TEST_DB_PASSWORD"))
    private val admin = Database.pool(url, user, password).also { pool -> pool.connection.use { it.autoCommit = true; it.execute("CREATE SCHEMA $schema") } }
    val pool = Database.pool(url + (if ('?' in url) "&" else "?") + "currentSchema=$schema", user, password)
    val db = Database(pool)
    override fun close() {
        pool.close()
        admin.connection.use { it.autoCommit = true; it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
        admin.close()
    }
}

/** An account signed in on a browser (cookie and CSRF token) and connected on a phone (bearer). */
internal data class Account(val id: String, val cookie: String, val csrf: String, val bearer: String) {
    override fun toString() = "Account($id)"
}

internal class RelayFixture(val db: Database, val identity: Identity = Identity.generate(), val clock: () -> Long = System::currentTimeMillis) {
    val origin = "https://usuals.invalid"
    val auth = Auth(db, clock)
    val key: ByteArray = Random.randBytes(32)
    val relay = Relay(db, identity, auth, ListCipher(key))
    val web = WebIdentity(WebIdentityConfig(origin, null, true), db, auth, clock = clock)
    fun account(name: String, email: String? = null, username: String? = null): Account {
        val user = uuid()
        db.transaction { c ->
            c.execute("INSERT INTO web_user(id,issuer,subject,display_name,email,created_at,last_login_at,local_username) VALUES (?,?,?,?,?,?,?,?)",
                id(user), WebIdentityConfig.LOCAL_ISSUER, user, name, email, clock(), clock(), username)
        }
        return signIn(user)
    }
    /** Fresh credentials for an existing account, as after signing in again. */
    fun signIn(user: String): Account {
        val account = Account(user, randomToken(), randomToken(), randomToken())
        db.transaction { c ->
            c.execute("INSERT INTO web_session VALUES (?,?,?,?,?,?,false)", id(uuid()), digest(account.cookie), id(user), account.csrf, clock(), clock() + 30L * DAY)
            c.execute("INSERT INTO native_session(hash,ticket,user_id,expires) VALUES (?,?,?,?)", digest(account.bearer), id(uuid()), id(user), clock() + 180L * DAY)
        }
        return account
    }
    companion object { const val DAY = 86_400_000L }
}

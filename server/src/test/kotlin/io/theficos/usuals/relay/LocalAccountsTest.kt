package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.theficos.usuals.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class LocalAccountsTest {
    private lateinit var admin: HikariDataSource
    private lateinit var pool: HikariDataSource
    private lateinit var db: Database
    private lateinit var f: RelayFixture
    private lateinit var web: WebIdentity
    private lateinit var auth: Auth
    private val schema = "test_local_" + uuid().replace("-", "")
    private val directory = Files.createTempDirectory("usuals-local-account-test")
    private var now = 1_800_000_000_000L
    private val identity = Identity.generate()
    private val password = "  synthetic member password  "
    @Before fun before() {
        val url = checkNotNull(System.getenv("USUALS_TEST_JDBC_URL")) { "Run server/scripts/test-local with disposable PostgreSQL" }
        val user = checkNotNull(System.getenv("USUALS_TEST_DB_USER")); val secret = checkNotNull(System.getenv("USUALS_TEST_DB_PASSWORD"))
        admin = Database.pool(url, user, secret)
        admin.connection.use { it.autoCommit = true; it.execute("CREATE SCHEMA $schema") }
        pool = Database.pool(url + if ('?' in url) "&currentSchema=$schema" else "?currentSchema=$schema", user, secret)
        db = Database(pool); db.migrate(); db.fence(identity.serverId, "original")
        f = RelayFixture(db, identity) { now }
        auth = Auth(db) { now }
        web = WebIdentity(WebIdentityConfig(f.origin, null, true), db, auth, clock = { now })
    }
    @After fun after() {
        if (::pool.isInitialized) pool.close()
        if (::admin.isInitialized) {
            admin.connection.use { it.autoCommit = true; it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }; admin.close()
        }
        Files.list(directory).use { files -> files.forEach(Files::deleteIfExists) }; Files.delete(directory)
    }
    private fun denied(code: String, action: () -> Any?) = assertThat(assertThrows(RelayError::class.java) { action() }.code).isEqualTo(code)
    private fun localLogin(request: LocalLoginRequest, browser: String, origin: String?, csrf: String?, ip: String) = runBlocking {
        web.localLogin(browser, origin, csrf, ip) { request }
    }
    private fun localActivate(request: LocalActivateRequest, browser: String, origin: String?, csrf: String?, ip: String) = runBlocking {
        web.localActivate(browser, origin, csrf, ip) { request }
    }
    private fun setup(username: String = "operator", reset: Boolean = false): String {
        val path = directory.resolve(uuid() + ".json")
        web.localOperator(username, path, reset)
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(path))).isEqualTo("rw-------")
        return Wire.json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("setupToken").jsonPrimitive.content
    }
    private fun activate(username: String, token: String, value: String = password): String {
        val challenge = web.localChallenge("activation")
        return localActivate(LocalActivateRequest(username, token, value), challenge.browserToken, f.origin, challenge.result.csrfToken, "activation").token
    }
    private fun login(username: String, value: String = password): String {
        val challenge = web.localChallenge("login")
        return localLogin(LocalLoginRequest(username, value), challenge.browserToken, f.origin, challenge.result.csrfToken, "login").token
    }
    private fun caller(cookie: String) = web.me(cookie).let { web.csrf(cookie, f.origin, it.csrfToken) }
    @Test fun operatorBootstrapIsProtectedOneUseAndNeverCreatesAPasswordOrBrowserAdminByDefault() {
        val path = directory.resolve("operator.json")
        web.localOperator("Operator", path, false)
        val original = Files.readAllBytes(path)
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { web.localOperator("another", path, false) }
        assertThat(Files.readAllBytes(path)).isEqualTo(original)
        denied("unauthenticated") { login("operator") }
        denied("unauthenticated") { login("missing-user") }
        val token = Wire.json.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject.getValue("setupToken").jsonPrimitive.content
        val cookie = activate("operator", token)
        assertThat(web.me(cookie).user.admin).isTrue()
        assertThat(web.me(cookie).user.issuer).isEqualTo(WebIdentityConfig.LOCAL_ISSUER)
        assertThat(web.me(cookie).user.username).isEqualTo("operator")
        id(web.me(cookie).user.subject)
        denied("unauthenticated") { activate("operator", token) }
        val stored = db.transaction { it.query("SELECT salt,password_hash,setup_hash FROM local_credential") { row -> Triple(row.getBytes(1), row.getBytes(2), row.getBytes(3)) }.single() }
        assertThat(stored.first).hasLength(32); assertThat(stored.second).hasLength(32); assertThat(stored.third).isNull()
    }
    @Test fun loginChallengeRequiresOriginCookieAndSingleUseHeaderAndExpires() {
        val token = setup(); activate("operator", token)
        val challenge = web.localChallenge("challenge")
        denied("forbidden") { localLogin(LocalLoginRequest("operator", password), challenge.browserToken, "https://other.example", challenge.result.csrfToken, "login") }
        denied("forbidden") { localLogin(LocalLoginRequest("operator", password), randomToken(), f.origin, challenge.result.csrfToken, "login") }
        val cookie = localLogin(LocalLoginRequest("operator", password), challenge.browserToken, f.origin, challenge.result.csrfToken, "login").token
        assertThat(web.me(cookie).user.username).isEqualTo("operator")
        denied("forbidden") { localLogin(LocalLoginRequest("operator", password), challenge.browserToken, f.origin, challenge.result.csrfToken, "login") }
        val stale = web.localChallenge("challenge"); now += 300000
        denied("forbidden") { localLogin(LocalLoginRequest("operator", password), stale.browserToken, f.origin, stale.result.csrfToken, "login") }
    }
    @Test fun administratorInviteAndResetCannotPromoteMergeOrResurrectAccounts() {
        val administrator = activate("operator", setup())
        val invited = web.localCreate(caller(administrator), LocalCreateRequest("member", "Synthetic Member"))
        assertThat(invited.user.admin).isFalse(); assertThat(invited.user.email).isNull()
        val member = activate("member", invited.setupToken)
        denied("forbidden") { web.localCreate(caller(member), LocalCreateRequest("intruder")) }
        val replacement = web.localReset(caller(administrator), invited.user.id)
        assertThat(replacement.user.id).isEqualTo(invited.user.id)
        assertThat(replacement.user.subject).isEqualTo(invited.user.subject)
        denied("unauthenticated") { web.me(member) }
        denied("unauthenticated") { login("member") }
        denied("unauthenticated") { activate("member", invited.setupToken) }
        val restored = activate("member", replacement.setupToken, "another synthetic password")
        assertThat(web.me(restored).user.admin).isFalse()
        web.update(caller(administrator), invited.user.id, WebUserUpdate(true))
        val disabledReset = web.localReset(caller(administrator), invited.user.id)
        denied("unauthenticated") { activate("member", disabledReset.setupToken) }
    }
    @Test fun passwordChangesRevokeBrowserAndPhoneSessions() {
        val cookie = activate("operator", setup()); val account = caller(cookie)
        val phone = f.signIn(account.user.id)
        denied("unauthenticated") { web.localPassword(account, LocalPasswordRequest("wrong synthetic password", "new synthetic password"), "password") }
        assertThat(web.me(cookie).user.id).isEqualTo(account.user.id)
        web.localPassword(account, LocalPasswordRequest(password, "new synthetic password"), "password")
        denied("unauthenticated") { web.me(cookie) }
        denied("unauthenticated") { db.transaction { Connections(db, f.relay, web) { now }.caller(it, phone.bearer) } }
        denied("unauthenticated") { login("operator") }
        val fresh = login("operator", "new synthetic password")
        assertThat(web.me(fresh).user.id).isEqualTo(account.user.id)
    }
    @Test fun restoreInvalidatesSessionsChallengesAndPendingInvites() {
        val cookie = activate("operator", setup()); val account = caller(cookie)
        val invite = web.localCreate(account, LocalCreateRequest("member"))
        val pending = web.localChallenge("restore")
        db.fence(identity.serverId, uuid())
        denied("unauthenticated") { web.me(cookie) }
        denied("forbidden") { localActivate(LocalActivateRequest("member", invite.setupToken, password), pending.browserToken, f.origin, pending.result.csrfToken, "activation") }
        denied("unauthenticated") { activate("member", invite.setupToken) }
        denied("unauthenticated") { login("operator") }
        val recovered = activate("operator", setup(reset = true), "post-restore synthetic password")
        assertThat(web.me(recovered).user.id).isEqualTo(account.user.id)
        assertThat(web.me(recovered).user.subject).isEqualTo(account.user.subject)
        assertThat(web.me(recovered).user.admin).isTrue()
    }
    @Test fun setupExpiryRequiresExplicitOperatorResetAndDoesNotChangeTheSubject() {
        val expired = setup(); now += 86400000
        denied("unauthenticated") { activate("operator", expired) }
        val subject = db.transaction { it.query("SELECT subject FROM web_user WHERE local_username='operator'") { row -> row.getString(1) }.single() }
        val cookie = activate("operator", setup(reset = true))
        assertThat(web.me(cookie).user.subject).isEqualTo(subject)
    }
    @Test fun realHttpLocalSessionsGuardListRoutes() = testApplication {
        val token = setup()
        application { relayModule(f.relay, webIdentity = web) }
        val browser = createClient { followRedirects = false }
        val challenge = browser.get("/auth/local/challenge")
        val csrf = Wire.json.parseToJsonElement(challenge.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
        val binding = challenge.headers.getAll(HttpHeaders.SetCookie)!!.single().substringBefore(';')
        val activated = browser.post("/auth/local/activate") {
            header(HttpHeaders.Cookie, binding); header(HttpHeaders.Origin, f.origin); header("X-CSRF-Token", csrf)
            contentType(ContentType.Application.Json); setBody(Wire.json.encodeToString(LocalActivateRequest("operator", token, password)))
        }
        assertThat(activated.status.value).isEqualTo(204)
        val session = activated.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("__Host-usuals-session=") }
        assertThat(session).contains("Max-Age=2592000")
        val cookie = session.substringBefore(';')
        val me = browser.get("/api/me") { header(HttpHeaders.Cookie, cookie) }
        val sessionCsrf = Wire.json.parseToJsonElement(me.bodyAsText()).jsonObject.getValue("csrfToken").jsonPrimitive.content
        suspend fun create(withCsrf: Boolean) = browser.put("/api/lists/${uuid()}") {
            header(HttpHeaders.Cookie, cookie); if (withCsrf) { header(HttpHeaders.Origin, f.origin); header("X-CSRF-Token", sessionCsrf) }
            contentType(ContentType.Application.Json); setBody("""{"projection":{"name":"Groceries"}}""")
        }
        assertThat(create(false).status.value).isEqualTo(403)
        assertThat(create(true).status.value).isEqualTo(201)
        assertThat(browser.get("/api/lists/changes") { header(HttpHeaders.Cookie, cookie) }.status.value).isEqualTo(200)
        assertThat(browser.post("/auth/logout") { header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, f.origin); header("X-CSRF-Token", sessionCsrf) }.status.value).isEqualTo(204)
        assertThat(browser.get("/api/lists/changes") { header(HttpHeaders.Cookie, cookie) }.status.value).isEqualTo(401)
        assertThat(browser.post("/auth/local/signup").status.value).isEqualTo(404)
    }
    @Test fun invalidLocalLoginCsrfIsRejectedBeforeInvokingBodyReader() {
        val challenge = web.localChallenge("lazy-body")
        var read = false
        denied("forbidden") {
            runBlocking {
                web.localLogin(challenge.browserToken, "https://other.example", challenge.result.csrfToken, "lazy-body") {
                    read = true
                    error("untrusted_body_read")
                }
            }
        }
        assertThat(read).isFalse()
    }
}

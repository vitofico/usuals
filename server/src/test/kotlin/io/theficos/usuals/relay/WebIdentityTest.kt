package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.theficos.usuals.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebIdentityTest {
    private lateinit var admin: HikariDataSource
    private lateinit var pool: HikariDataSource
    private lateinit var db: Database
    private lateinit var f: RelayFixture
    private lateinit var provider: OidcFixture
    private lateinit var web: WebIdentity
    private lateinit var auth: Auth
    private val schema = "test_web_" + uuid().replace("-", "")
    private var now = 1_800_000_000_000L
    private val identity = Identity.generate()
    @Before fun before() {
        val url = checkNotNull(System.getenv("USUALS_TEST_JDBC_URL")) { "Run server/scripts/test-local with disposable PostgreSQL" }
        val user = checkNotNull(System.getenv("USUALS_TEST_DB_USER")); val password = checkNotNull(System.getenv("USUALS_TEST_DB_PASSWORD"))
        admin = Database.pool(url, user, password)
        admin.connection.use { it.autoCommit = true; it.execute("CREATE SCHEMA $schema") }
        pool = Database.pool(url + if ('?' in url) "&currentSchema=$schema" else "?currentSchema=$schema", user, password)
        db = Database(pool); db.migrate(); db.fence(identity.serverId, "original")
        f = RelayFixture(db, identity) { now }
        provider = OidcFixture { now }
        auth = Auth(db) { now }
        web = WebIdentity(provider.config(), db, auth, OidcClient(provider.config(), provider.transport) { now }) { now }
    }
    @After fun after() {
        if (::pool.isInitialized) pool.close()
        if (::admin.isInitialized) {
            admin.connection.use { it.autoCommit = true; it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }; admin.close()
        }
    }
    private fun denied(code: String, action: () -> Any?) = assertThat(assertThrows(RelayError::class.java) { action() }.code).isEqualTo(code)
    private fun login(subject: String = "member-subject"): String {
        provider.subject = subject
        val start = web.begin("synthetic-ip")
        val request = provider.authorize(start)
        return web.complete("synthetic-code", request.getValue("state"), start.browserToken, "synthetic-ip").token
    }
    private fun caller(token: String) = web.me(token).let { web.csrf(token, f.origin, it.csrfToken) }
    @Test fun loginIsBrowserBoundSingleUseAndConsumesFailedProviderExchanges() {
        val start = web.begin("synthetic-ip"); val request = provider.authorize(start)
        denied("unauthenticated") { web.complete("synthetic-code", request.getValue("state"), randomToken(), "synthetic-ip") }
        denied("unauthenticated") { web.complete("synthetic-code", request.getValue("state"), start.browserToken, "synthetic-ip") }
        val stale = web.begin("synthetic-ip"); val pending = provider.authorize(stale)
        now += 300001
        denied("unauthenticated") { web.complete("synthetic-code", pending.getValue("state"), stale.browserToken, "synthetic-ip") }
        val invalid = web.begin("synthetic-ip"); val invalidRequest = provider.authorize(invalid)
        provider.tokenOverride = "not-a-jwt"
        denied("unauthenticated") { web.complete("synthetic-code", invalidRequest.getValue("state"), invalid.browserToken, "synthetic-ip") }
        provider.tokenOverride = null
        denied("unauthenticated") { web.complete("synthetic-code", invalidRequest.getValue("state"), invalid.browserToken, "synthetic-ip") }
        assertThat(db.transaction { it.query("SELECT count(*) FROM web_session") { row -> row.getLong(1) }.single() }).isEqualTo(0)
    }
    @Test fun identityIsImmutableIssuerSubjectNotEmailAndNoFirstUserAdmin() {
        val first = web.me(login())
        val same = web.me(login())
        val other = web.me(login("another-subject"))
        assertThat(first.user.id).isEqualTo(same.user.id)
        assertThat(first.user.id).isNotEqualTo(other.user.id)
        assertThat(first.user.email).isEqualTo(other.user.email)
        assertThat(first.user.admin).isFalse()
        assertThat(first.user.issuer).isEqualTo(provider.issuer)
        assertThat(first.user.subject).isEqualTo("member-subject")
        val administrator = web.me(login("admin-subject"))
        assertThat(administrator.user.admin).isTrue()
        assertThat(administrator.user.id).isNotEqualTo(first.user.id)
    }
    @Test fun sessionCsrfExpiryAndAdministrativeDisablingAreFailClosed() {
        val member = login(); val account = web.me(member)
        denied("forbidden") { web.csrf(member, "https://other.example", account.csrfToken) }
        denied("forbidden") { web.csrf(member, null, account.csrfToken) }
        denied("forbidden") { web.csrf(member, f.origin, "other") }
        denied("forbidden") { web.users(member, null) }
        val administrator = login("admin-subject"); val adminCaller = caller(administrator)
        assertThat(web.users(administrator, null).users).hasSize(2)
        assertThat(web.update(adminCaller, account.user.id, WebUserUpdate(true)).disabled).isTrue()
        denied("unauthenticated") { web.me(member) }
        denied("forbidden") { login() }
        web.update(adminCaller, account.user.id, WebUserUpdate(false))
        val fresh = login()
        assertThat(web.me(fresh).user.id).isEqualTo(account.user.id)
        now += 30 * RelayFixture.DAY
        denied("unauthenticated") { web.me(fresh) }
    }
    @Test fun aTwentyFirstSignInRetiresTheLeastRecentlyUsedSession() {
        val oldest = login(); now += 60000
        val others = (1..19).map { login().also { now += 60000 } }
        val newest = login()
        denied("unauthenticated") { web.me(oldest) }
        (others + newest).forEach { web.me(it) }
        assertThat(web.sessions(newest).sessions).hasSize(20)
    }
    @Test fun sessionRevocationIsOwnedAndLogoutRevokesOnlyThatSession() {
        val first = login(); val second = login()
        val other = login("another-subject")
        val otherId = web.sessions(other).sessions.single().id
        denied("not_found") { web.revoke(caller(first), otherId) }
        val sessions = web.sessions(first).sessions
        assertThat(sessions).hasSize(2)
        assertThat(sessions.count { it.current }).isEqualTo(1)
        web.logout(caller(first))
        denied("unauthenticated") { web.me(first) }
        assertThat(web.me(second).user.id).isNotEqualTo(web.me(other).user.id)
        val administrator = login("admin-subject")
        web.revokeUser(caller(administrator), web.me(second).user.id)
        denied("unauthenticated") { web.me(second) }
    }
    @Test fun httpFlowSetsProtectedCookiesNoStoreAndRequiresOriginCsrfForMutations() = testApplication {
        application { relayModule(Relay(db, identity, auth, f.relay.cipher), webIdentity = web) }
        val browser = createClient { followRedirects = false }
        assertThat(browser.get("/auth/config").bodyAsText()).contains("\"enabled\":true")
        val start = browser.get("/auth/login?returnTo=https://attacker.example")
        val query = OidcFixture.query(start.headers[HttpHeaders.Location]!!); provider.nonce = query.getValue("nonce")
        val loginCookie = start.headers.getAll(HttpHeaders.SetCookie)!!.single()
        assertThat(loginCookie).contains("HttpOnly"); assertThat(loginCookie).contains("Secure"); assertThat(loginCookie).contains("SameSite=Lax")
        val complete = browser.get("/auth/callback?state=${query.getValue("state")}&code=synthetic-code") { header(HttpHeaders.Cookie, loginCookie.substringBefore(';')) }
        assertThat(complete.status.value).isEqualTo(302); assertThat(complete.headers[HttpHeaders.Location]).isEqualTo("/")
        val session = complete.headers.getAll(HttpHeaders.SetCookie)!!.single { it.startsWith("__Host-usuals-session=") }.substringBefore(';')
        val me = browser.get("/api/me") { header(HttpHeaders.Cookie, session) }
        assertThat(me.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        assertThat(me.headers["Referrer-Policy"]).isEqualTo("no-referrer")
        val body = Wire.json.parseToJsonElement(me.bodyAsText()).jsonObject
        assertThat(body["user"]!!.jsonObject["subject"]!!.jsonPrimitive.content).isEqualTo("member-subject")
        val csrf = body["csrfToken"]!!.jsonPrimitive.content
        assertThat(browser.post("/auth/logout") { header(HttpHeaders.Cookie, session) }.status.value).isEqualTo(403)
        val loggedOut = browser.post("/auth/logout") { header(HttpHeaders.Cookie, session); header(HttpHeaders.Origin, f.origin); header("X-CSRF-Token", csrf) }
        assertThat(loggedOut.status.value).isEqualTo(204)
        assertThat(browser.get("/api/me") { header(HttpHeaders.Cookie, session) }.status.value).isEqualTo(401)
        assertThat(browser.get("/auth/callback?state=${query.getValue("state")}&code=synthetic-code") { header(HttpHeaders.Cookie, loginCookie.substringBefore(';')) }.status.value).isEqualTo(401)
    }
    @Test fun withoutAccountsNoAccountOrListRoutesExist() = testApplication {
        application { relayModule(f.relay) }
        assertThat(client.get("/auth/config").bodyAsText()).contains("\"enabled\":false")
        assertThat(client.get("/api/me").status.value).isEqualTo(404)
        assertThat(client.get("/api/lists/changes").status.value).isEqualTo(404)
    }
    @Test fun aHeldIdentityProviderCannotOccupyListWorkersOrQueueMoreLogins() = testApplication {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val slow = OidcTransport { url, form, basic ->
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); provider.transport.json(url, form, basic)
        }
        val isolated = WebIdentity(provider.config(), db, auth, OidcClient(provider.config(), slow) { now }) { now }
        application { relayModule(Relay(db, identity, auth, f.relay.cipher), webIdentity = isolated) }
        val browser = createClient { followRedirects = false }
        coroutineScope {
            val held = async { browser.get("/auth/login") }
            try {
                assertThat(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) }).isTrue()
                val catchUp = withTimeout(2000) { browser.get("/readyz") }
                assertThat(catchUp.status.value).isEqualTo(200)
                assertThat(withTimeout(1000) { browser.get("/auth/login") }.status.value).isEqualTo(503)
            } finally { release.countDown(); held.await() }
        }
    }
}

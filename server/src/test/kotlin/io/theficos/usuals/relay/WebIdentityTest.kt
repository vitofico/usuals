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
        f = RelayFixture(db, identity) { now }.bootstrap()
        provider = OidcFixture { now }
        auth = Auth(db, identity, f.origin, provider.issuer) { now }
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
    private fun proof(device: TestDevice = f.owner): Proof {
        val challenge = f.auth.challenge(ChallengeRequest(device.keys.id, "session"), "synthetic-proof")
        return f.proof(device, challenge)
    }
    private fun webCreation(): Pair<RelayFixture, CreateHousehold> {
        val fixture = RelayFixture(db, identity) { now }
        return fixture to fixture.bootstrapRequest().second
    }
    private fun freshBootstrap(fixture: RelayFixture, request: CreateHousehold): CreateHousehold {
        val challenge = auth.challenge(ChallengeRequest(fixture.owner.keys.id, "bootstrap", fixture.owner.keys.signingPublicKey), uuid())
        return request.copy(bootstrapProof = fixture.proof(fixture.owner, challenge))
    }
    @Test fun webCreationIsAtomicBoundIdempotentAndDoesNotConsumeOperatorCodes() = runBlocking {
        val cookie = login(); val account = caller(cookie)
        val (fixture, request) = webCreation(); val relay = Relay(db, identity, auth)
        val count = db.transaction { it.query("SELECT count(*) FROM bootstrap_token") { r -> r.getLong(1) }.single() }
        val created = web.createHousehold(account, request, relay)
        assertThat(created.signed().hash()).isEqualTo(request.manifest.hash())
        assertThat(web.createHousehold(account, freshBootstrap(fixture, request), relay)).isEqualTo(created)
        assertThat(db.transaction { it.query("SELECT count(*) FROM web_created_household WHERE user_id=?", id(account.user.id)) { r -> r.getLong(1) }.single() }).isEqualTo(1)
        assertThat(db.transaction { it.query("SELECT count(*) FROM bootstrap_token") { r -> r.getLong(1) }.single() }).isEqualTo(count)
        denied("unauthenticated") { fixture.session(fixture.owner) }
        val otherCookie = login("other-subject")
        denied("conflict") { web.createHousehold(caller(otherCookie), freshBootstrap(fixture, request), relay) }
        withContext(auth.browserContext(cookie)) {
            val challenge = auth.challenge(ChallengeRequest(fixture.owner.keys.id, "session"), uuid())
            val token = auth.session(fixture.proof(fixture.owner, challenge), account.session).token
            assertThat(relay.lists(token, fixture.household).listIds).isEmpty()
        }
    }
    @Test fun webCreationRejectsUsedKeysTamperingDisabledAndRevokedAccounts() {
        val cookie = login(); val account = caller(cookie); val relay = Relay(db, identity, auth)
        val (_, request) = webCreation()
        denied("forbidden") { web.createHousehold(account, request.copy(manifest = request.manifest.copy(signature = Wire.encode(ByteArray(64)))), relay) }
        val existing = f.current.body().copy(householdId = uuid())
        val challenge = auth.challenge(ChallengeRequest(f.owner.keys.id, "bootstrap", f.owner.keys.signingPublicKey), uuid())
        denied("conflict") { web.createHousehold(account, CreateHousehold(f.signed(existing), f.proof(f.owner, challenge)), relay) }
        web.logout(account)
        denied("unauthenticated") { web.createHousehold(account, request, relay) }
        val fresh = caller(login())
        db.transaction { it.execute("UPDATE web_user SET disabled=true WHERE id=?", id(fresh.user.id)) }
        denied("unauthenticated") { web.createHousehold(fresh, request, relay) }
        assertThat(db.transaction { it.query("SELECT count(*) FROM web_created_household") { r -> r.getLong(1) }.single() }).isEqualTo(0)
    }
    @Test fun webCreationRateAndPermanentQuotaAreBoundedPerAccount() {
        val cookie = login(); val account = caller(cookie); val relay = Relay(db, identity, auth)
        repeat(5) { val (_, request) = webCreation(); web.createHousehold(account, request, relay) }
        val (_, limited) = webCreation()
        denied("rate_limited") { web.createHousehold(account, limited, relay) }
        now += 60001
        repeat(5) { val (_, request) = webCreation(); web.createHousehold(account, request, relay) }
        now += 60001
        val (_, overflow) = webCreation()
        denied("quota_exceeded") { web.createHousehold(account, overflow, relay) }
        assertThat(db.transaction { it.query("SELECT count(*) FROM web_created_household") { r -> r.getLong(1) }.single() }).isEqualTo(10)
    }
    @Test fun webCreationHttpRequiresAccountOriginAndCsrfBeforeReadingBody() = testApplication {
        application { relayModule(Relay(db, identity, auth), webIdentity = web) }
        val cookie = login(); val me = web.me(cookie)
        val session = "__Host-usuals-session=$cookie"
        assertThat(client.post("/api/households") { setBody("bad-body") }.status.value).isEqualTo(401)
        for ((origin, csrf) in listOf(f.origin to "wrong", "https://other.example" to me.csrfToken, null to me.csrfToken)) {
            assertThat(client.post("/api/households") {
                header(HttpHeaders.Cookie, session); if (origin != null) header(HttpHeaders.Origin, origin)
                header("X-CSRF-Token", csrf); setBody("bad-body")
            }.status.value).isEqualTo(403)
        }
        val (_, request) = webCreation()
        val response = client.post("/api/households") {
            header(HttpHeaders.Cookie, session); header(HttpHeaders.Origin, f.origin); header("X-CSRF-Token", me.csrfToken)
            contentType(ContentType.Application.Json); setBody(Wire.json.encodeToString(CreateHousehold.serializer(), request))
        }
        assertThat(response.status.value).isEqualTo(201)
        assertThat(response.headers[HttpHeaders.CacheControl]).isEqualTo("no-store")
        assertThat(Wire.json.decodeFromString(ManifestProof.serializer(), response.bodyAsText()).signed().hash()).isEqualTo(request.manifest.hash())
    }
    @Test fun webHouseholdMigrationPreservesVersionThreeDataAndIdentityBindings() {
        val cookie = login(); val account = caller(cookie); web.link(account, proof())
        val previous = db.transaction { c ->
            c.execute("DROP TABLE connection_offer,ai_vision_check,ai_usage,ai_preference,native_session,connection_ticket,web_created_household"); c.execute("DELETE FROM schema_migration WHERE version>=4")
            c.execute("ALTER TABLE invitation DROP COLUMN descriptor"); c.execute("ALTER TABLE join_request DROP COLUMN mac")
            c.query("SELECT version,checksum FROM schema_migration ORDER BY version") { it.getInt(1) to it.getString(2) }
        }
        db.migrate(); db.migrate(); db.checkSchema()
        assertThat(db.transaction { it.query("SELECT version,checksum FROM schema_migration WHERE version<4 ORDER BY version") { r -> r.getInt(1) to r.getString(2) } }).isEqualTo(previous)
        assertThat(web.me(cookie).user.id).isEqualTo(account.user.id)
        assertThat(db.transaction { it.query("SELECT count(*) FROM web_device") { r -> r.getLong(1) }.single() }).isEqualTo(1)
    }
    private suspend fun boundSession(token: String, device: TestDevice = f.owner): String = withContext(auth.browserContext(token)) {
        auth.session(proof(device), caller(token).session).token
    }

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
        // No browser account creates or changes any household authority.
        assertThat(db.transaction { it.query("SELECT count(*) FROM principal") { row -> row.getLong(1) }.single() }).isEqualTo(2)
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
        now += 900000
        denied("unauthenticated") { web.me(fresh) }
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
    @Test fun signedDeviceLinkIsPermanentRejectsOtherKeysAndRevokesOldBearerAuthority() = runBlocking {
        val cookie = login(); val account = caller(cookie)
        val challenge = f.auth.challenge(ChallengeRequest(f.owner.keys.id, "session"), "link")
        val wrong = f.proof(TestDevice(), challenge).copy(deviceId = f.owner.keys.id)
        denied("unauthenticated") { web.link(account, wrong) }
        web.link(account, f.proof(f.owner, challenge))
        denied("forbidden") { db.transaction { auth.caller(it, f.token) } }
        denied("unauthenticated") { auth.session(proof()) }
        val otherCookie = login("another-subject")
        withContext(auth.browserContext(otherCookie)) { denied("unauthenticated") { auth.session(proof(), caller(otherCookie).session) } }
        denied("conflict") { web.link(account, proof()) }
        val bearer = boundSession(cookie)
        denied("unauthenticated") { db.transaction { auth.caller(it, bearer) } }
        withContext(auth.browserContext(otherCookie)) { denied("unauthenticated") { db.transaction { auth.caller(it, bearer) } } }
        withContext(auth.browserContext(cookie)) { assertThat(db.transaction { auth.caller(it, bearer) }.device).isEqualTo(f.owner.keys.id) }
        web.logout(account)
        withContext(auth.browserContext(cookie)) { denied("unauthenticated") { db.transaction { auth.caller(it, bearer) } } }
    }
    @Test fun mappedSessionsFailAfterDisableExpiryIssuerChangeAndRestoreFence() = runBlocking {
        val cookie = login(); val account = caller(cookie); web.link(account, proof())
        val bearer = boundSession(cookie)
        val changedIssuer = Auth(db, identity, f.origin, "https://other.example/") { now }
        withContext(changedIssuer.browserContext(cookie)) { denied("unauthenticated") { db.transaction { changedIssuer.caller(it, bearer) } } }
        val disabledProvider = Auth(db, identity, f.origin, clock = { now })
        withContext(disabledProvider.browserContext(cookie)) { denied("unauthenticated") { db.transaction { disabledProvider.caller(it, bearer) } } }
        db.transaction { it.execute("UPDATE web_user SET disabled=true WHERE id=?", id(account.user.id)) }
        withContext(auth.browserContext(cookie)) { denied("unauthenticated") { db.transaction { auth.caller(it, bearer) } } }
        db.transaction { it.execute("UPDATE web_user SET disabled=false WHERE id=?", id(account.user.id)) }
        db.transaction { it.execute("UPDATE web_session SET expires=? WHERE id=?", now + 1, id(account.session)) }
        now += 2
        withContext(auth.browserContext(cookie)) { denied("unauthenticated") { db.transaction { auth.caller(it, bearer) } } }
        val secondCookie = login()
        val secondAccount = caller(secondCookie)
        val secondBearer = boundSession(secondCookie)
        val administrator = login("admin-subject")
        web.update(caller(administrator), account.user.id, WebUserUpdate(true))
        withContext(auth.browserContext(secondCookie)) { denied("unauthenticated") { db.transaction { auth.caller(it, secondBearer) } } }
        web.update(caller(administrator), account.user.id, WebUserUpdate(false))
        val fresh = login(); val refreshed = boundSession(fresh)
        now += 900000
        withContext(auth.browserContext(fresh)) { denied("unauthenticated") { db.transaction { auth.caller(it, refreshed) } } }
        val restored = login(); val pending = web.begin("restore")
        db.fence(identity.serverId, uuid())
        denied("unauthenticated") { web.me(restored) }
        assertThat(db.transaction { it.query("SELECT count(*) FROM web_login") { row -> row.getLong(1) }.single() }).isEqualTo(0)
        assertThat(db.transaction { it.query("SELECT count(*) FROM web_device") { row -> row.getLong(1) }.single() }).isEqualTo(1)
        assertThat(pending.browserToken).isNotEmpty()
        assertThat(secondAccount.user.id).isEqualTo(account.user.id)
    }
    @Test fun concurrentCookieContextsNeverLeakBetweenSignedRelayRequests() = runBlocking {
        val cookie = login(); web.link(caller(cookie), proof()); val bearer = boundSession(cookie)
        val other = login("another-subject")
        coroutineScope {
            listOf(cookie, other, null, cookie).map { value -> async(Dispatchers.IO + auth.browserContext(value)) {
                repeat(5) { yield(); if (value == cookie) assertThat(db.transaction { auth.caller(it, bearer) }.device).isEqualTo(f.owner.keys.id)
                    else denied("unauthenticated") { db.transaction { auth.caller(it, bearer) } } }
            } }.awaitAll()
        }
        denied("unauthenticated") { db.transaction { auth.caller(it, bearer) } }
    }
    @Test fun migrationFromVersionOnePreservesExistingHouseholdsAndChecksums() {
        db.transaction { c ->
            listOf("connection_offer", "ai_vision_check", "ai_usage", "ai_preference", "native_session", "connection_ticket", "web_created_household", "local_login", "local_credential", "web_device_session", "web_device", "web_session", "web_login", "web_user").forEach { c.execute("DROP TABLE $it") }
            c.execute("DELETE FROM schema_migration WHERE version>=2")
            c.execute("ALTER TABLE invitation DROP COLUMN descriptor"); c.execute("ALTER TABLE join_request DROP COLUMN mac")
        }
        val checksum = db.transaction { it.query("SELECT checksum FROM schema_migration WHERE version=1") { row -> row.getString(1) }.single() }
        assertThrows(IllegalStateException::class.java) { db.checkSchema() }
        db.migrate(); db.migrate(); db.checkSchema()
        assertThat(db.transaction { it.query("SELECT checksum FROM schema_migration WHERE version=1") { row -> row.getString(1) }.single() }).isEqualTo(checksum)
        assertThat(f.relay.lists(f.token, f.household)).isNotNull()
    }
    @Test fun httpFlowSetsProtectedCookiesNoStoreAndRequiresOriginCsrfForMutations() = testApplication {
        application { relayModule(Relay(db, identity, auth), webIdentity = web) }
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
    @Test fun disabledIdentityAndUnboundAndroidContinueToUseIndependentAuthority() = testApplication {
        application { relayModule(f.relay) }
        assertThat(client.get("/auth/config").bodyAsText()).contains("\"enabled\":false")
        assertThat(client.get("/api/me").status.value).isEqualTo(404)
        assertThat(client.get("/v1/households/${f.household}/lists") { bearerAuth(f.token) }.status.value).isEqualTo(200)
    }
    @Test fun aHeldIdentityProviderCannotOccupyEncryptedSyncWorkersOrQueueMoreLogins() = testApplication {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val slow = OidcTransport { url, form, basic ->
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); provider.transport.json(url, form, basic)
        }
        val isolated = WebIdentity(provider.config(), db, auth, OidcClient(provider.config(), slow) { now }) { now }
        application { relayModule(Relay(db, identity, auth), webIdentity = isolated) }
        val browser = createClient { followRedirects = false }
        coroutineScope {
            val held = async { browser.get("/auth/login") }
            try {
                assertThat(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) }).isTrue()
                val catchUp = withTimeout(2000) { browser.get("/v1/households/${f.household}/lists") { bearerAuth(f.token) } }
                assertThat(catchUp.status.value).isEqualTo(200)
                assertThat(withTimeout(1000) { browser.get("/auth/login") }.status.value).isEqualTo(503)
            } finally { release.countDown(); held.await() }
        }
    }
}

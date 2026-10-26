package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.subtle.Random
import com.zaxxer.hikari.HikariDataSource
import io.theficos.usuals.llm.*
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.*
import org.junit.Assert.assertThrows

class ConnectionAiTest {
    private lateinit var admin: HikariDataSource
    private lateinit var pool: HikariDataSource
    private lateinit var db: Database
    private lateinit var f: RelayFixture
    private lateinit var web: WebIdentity
    private lateinit var connections: Connections
    private lateinit var cookieToken: String
    private lateinit var user: String
    private lateinit var caller: WebCaller
    private val schema = "test_connection_" + uuid().replace("-", "")
    private var now = 1_800_000_000_000L
    @Before fun before() {
        val url = checkNotNull(System.getenv("USUALS_TEST_JDBC_URL"))
        val dbUser = checkNotNull(System.getenv("USUALS_TEST_DB_USER")); val password = checkNotNull(System.getenv("USUALS_TEST_DB_PASSWORD"))
        admin = Database.pool(url, dbUser, password)
        admin.connection.use { it.autoCommit = true; it.execute("CREATE SCHEMA $schema") }
        pool = Database.pool(url + if ('?' in url) "&currentSchema=$schema" else "?currentSchema=$schema", dbUser, password)
        db = Database(pool); db.migrate()
        f = RelayFixture(db, Identity.generate()) { now }
        web = WebIdentity(WebIdentityConfig(f.origin, null, true), db, f.auth, clock = { now })
        connections = Connections(db, f.relay, web) { now }
        user = uuid(); val token = randomToken(); cookieToken = token; val csrf = randomToken()
        db.transaction { c ->
            c.execute("INSERT INTO web_user(id,issuer,subject,display_name,created_at,last_login_at) VALUES (?,?,?,?,?,?)", id(user), WebIdentityConfig.LOCAL_ISSUER, user, "Synthetic", now, now)
            c.execute("INSERT INTO web_session VALUES (?,?,?,?,?,?,false)", id(uuid()), digest(token), id(user), csrf, now, now + 900000)
        }
        caller = web.csrf(token, f.origin, csrf)
    }
    @After fun after() {
        if (::pool.isInitialized) pool.close()
        if (::admin.isInitialized) { admin.connection.use { it.autoCommit = true; it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }; admin.close() }
    }
    private fun ticket(): Triple<ConnectTicket, String, DeviceSecrets> {
        val device = SyncCrypto.device(); val verifier = randomToken()
        return Triple(connections.start(ConnectStart(device.publicKeys, Wire.hash(Wire.decode(verifier, 32))), "test"), verifier, device)
    }
    @Test fun handoffIsBoundToVerifierAndExactKeysAndRetriesDoNotCreateMoreSessions() {
        val (ticket, verifier, device) = ticket()
        assertThat(connections.view(caller, ticket.id).fingerprint).isEqualTo(connectionFingerprint(f.identity.serverId, ticket.id, device.publicKeys))
        val request = ConnectFinish(ticket.pollToken, verifier)
        assertThat(connections.complete(ticket.id, request, "test").approved).isFalse()
        connections.approve(caller, ticket.id)
        assertThat(assertThrows(RelayError::class.java) { connections.complete(ticket.id, request.copy(verifier = randomToken()), "test") }.code).isEqualTo("unauthenticated")
        val first = connections.complete(ticket.id, request, "test")
        assertThat(first.userId).isEqualTo(user)
        assertThat(connections.complete(ticket.id, request, "test")).isEqualTo(first)
        val token = connectionToken(ticket.id, verifier, ticket.pollToken)
        assertThat(db.transaction { connections.caller(it, token) }).isEqualTo(user)
        assertThat(db.transaction { it.query("SELECT count(*) FROM native_session") { r -> r.getInt(1) }.single() }).isEqualTo(1)
        connections.logout(token)
        assertThrows(RelayError::class.java) { db.transaction { connections.caller(it, token) } }
    }
    @Test fun expiredHandoffAndDisabledAccountsCannotConnect() {
        val (ticket, verifier) = ticket(); connections.approve(caller, ticket.id)
        db.transaction { it.execute("UPDATE web_user SET disabled=true WHERE id=?", id(user)) }
        assertThrows(RelayError::class.java) { connections.complete(ticket.id, ConnectFinish(ticket.pollToken, verifier), "test") }
        now = ticket.expiresAt
        assertThrows(RelayError::class.java) { connections.view(caller, ticket.id) }
    }
    @Test fun revokeAllInvalidatesApprovedButUncompletedHandoffs() {
        val (ticket, verifier) = ticket(); connections.approve(caller, ticket.id)
        db.transaction { it.execute("UPDATE web_user SET local_admin=true WHERE id=?", id(user)) }
        val admin = web.csrf(cookieToken, f.origin, caller.csrf)
        web.revokeUser(admin, user)
        assertThat(assertThrows(RelayError::class.java) { connections.complete(ticket.id, ConnectFinish(ticket.pollToken, verifier), "test") }.code).isEqualTo("unauthenticated")
        assertThat(db.transaction { it.query("SELECT count(*) FROM native_session") { r -> r.getInt(1) }.single() }).isEqualTo(0)
    }
    @Test fun previouslyAuthenticatedBrowserCannotApproveAfterItsSessionWasRevoked() {
        val (ticket) = ticket()
        web.logout(caller)
        assertThat(assertThrows(RelayError::class.java) { connections.approve(caller, ticket.id) }.code).isEqualTo("unauthenticated")
        assertThat(db.transaction { it.query("SELECT user_id FROM connection_ticket WHERE id=?", id(ticket.id)) { r -> r.getObject(1) }.single() }).isNull()
    }
    private fun scan(offer: ConnectOffer, secret: ByteArray): Triple<ConnectStart, String, DeviceSecrets> {
        val device = SyncCrypto.device(); val verifier = randomToken()
        return Triple(ConnectStart(device.publicKeys, Wire.hash(Wire.decode(verifier, 32)), OfferClaim(offer.id, Wire.encode(secret))), verifier, device)
    }
    @Test fun offerClaimApprovesOnlyThePhoneHoldingItsSecretAtOnce() {
        val secret = Random.randBytes(32); val offer = connections.offer(caller, ConnectOfferRequest(Wire.hash(secret)))
        assertThat(connections.offerView(caller, offer.id)).isEqualTo(ConnectOfferView(offer.id, offer.expiresAt, null))
        val (start, verifier, device) = scan(offer, secret)
        assertThat(assertThrows(RelayError::class.java) { connections.start(start.copy(offer = OfferClaim(offer.id, randomToken())), "test") }.code).isEqualTo("not_found")
        val ticket = connections.start(start, "test")
        val view = connections.offerView(caller, offer.id)
        assertThat(view.ticket).isEqualTo(ConnectView(ticket.id, connectionFingerprint(f.identity.serverId, ticket.id, device.publicKeys), ticket.expiresAt))
        // One phone per code: scanning the same QR again cannot replace the phone already connecting.
        assertThat(assertThrows(RelayError::class.java) { connections.start(scan(offer, secret).first, "test") }.code).isEqualTo("not_found")
        assertThat(connections.complete(ticket.id, ConnectFinish(ticket.pollToken, verifier), "test").userId).isEqualTo(user)
        assertThat(db.transaction { it.query("SELECT count(*) FROM connection_ticket") { r -> r.getInt(1) }.single() }).isEqualTo(1)
    }
    @Test fun anOfferDiesWithTheBrowserSessionThatShowedIt() {
        val secret = Random.randBytes(32); val offer = connections.offer(caller, ConnectOfferRequest(Wire.hash(secret)))
        web.logout(caller)
        assertThat(assertThrows(RelayError::class.java) { connections.start(scan(offer, secret).first, "test") }.code).isEqualTo("not_found")
        assertThat(db.transaction { it.query("SELECT count(*) FROM connection_ticket") { r -> r.getInt(1) }.single() }).isEqualTo(0)
    }
    @Test fun offerIsPrivateToItsAccountExpiresAndIsBounded() {
        val secret = Random.randBytes(32); val offer = connections.offer(caller, ConnectOfferRequest(Wire.hash(secret)))
        val stranger = uuid(); val token = randomToken(); val csrf = randomToken()
        db.transaction { c ->
            c.execute("INSERT INTO web_user(id,issuer,subject,display_name,created_at,last_login_at) VALUES (?,?,?,?,?,?)", id(stranger), WebIdentityConfig.LOCAL_ISSUER, stranger, "Other", now, now)
            c.execute("INSERT INTO web_session VALUES (?,?,?,?,?,?,false)", id(uuid()), digest(token), id(stranger), csrf, now, now + 900000)
        }
        assertThat(assertThrows(RelayError::class.java) { connections.offerView(web.csrf(token, f.origin, csrf), offer.id) }.code).isEqualTo("not_found")
        repeat(4) { connections.offer(caller, ConnectOfferRequest(Wire.hash(Random.randBytes(32)))) }
        assertThat(assertThrows(RelayError::class.java) { connections.offer(caller, ConnectOfferRequest(Wire.hash(Random.randBytes(32)))) }.code).isEqualTo("rate_limited")
        now = offer.expiresAt
        assertThat(assertThrows(RelayError::class.java) { connections.start(scan(offer, secret).first, "test") }.code).isEqualTo("not_found")
        assertThat(assertThrows(RelayError::class.java) { connections.offerView(caller, offer.id) }.code).isEqualTo("not_found")
        connections.offer(caller, ConnectOfferRequest(Wire.hash(Random.randBytes(32))))
    }
    @Test fun claimedOfferLivesExactlyAsLongAsItsTicket() {
        val secret = Random.randBytes(32); val offer = connections.offer(caller, ConnectOfferRequest(Wire.hash(secret)))
        now = offer.expiresAt - 1000
        val ticket = connections.start(scan(offer, secret).first, "test")
        now = offer.expiresAt + 1000
        assertThat(connections.offerView(caller, offer.id).ticket?.id).isEqualTo(ticket.id)
        now = ticket.expiresAt
        assertThat(assertThrows(RelayError::class.java) { connections.offerView(caller, offer.id) }.code).isEqualTo("not_found")
    }
    @Test fun nativeAuthorizationRechecksRevocationAfterWaitingForTheAccountLock(): Unit = runBlocking {
        val (ticket, verifier) = ticket(); connections.approve(caller, ticket.id)
        connections.complete(ticket.id, ConnectFinish(ticket.pollToken, verifier), "test")
        val token = connectionToken(ticket.id, verifier, ticket.pollToken)
        pool.connection.use { blocking ->
            blocking.autoCommit = false
            blocking.query("SELECT id FROM web_user WHERE id=? FOR UPDATE", id(user)) { true }
            val backend = CompletableDeferred<Int>()
            val authorization = async(Dispatchers.IO) { runCatching { db.transaction { c ->
                backend.complete(c.query("SELECT pg_backend_pid()") { it.getInt(1) }.single())
                connections.caller(c, token)
            } } }
            try {
                val pid = backend.await()
                withTimeout(5000) { while (!db.transaction { c -> c.query("SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=?", pid) { it.getBoolean(1) }.singleOrNull() == true }) delay(10) }
                blocking.execute("UPDATE native_session SET revoked=true WHERE hash=?", digest(token))
                blocking.commit()
                assertThat((authorization.await().exceptionOrNull() as RelayError).code).isEqualTo("unauthenticated")
            } finally { blocking.rollback() }
        }
    }
    @Test fun authorizationRevokedAfterAuthenticationCannotDispatchOrChangeCredentials(): Unit = runBlocking {
        val ai = gateway(); val revision = ai.settings(user).revision
        // The caller was authenticated before reading a request body, then logged out.
        web.logout(caller)
        val error = runCatching { ai.execute(user, "text", AiRequest(text = "synthetic", revision = revision)) { c -> web.current(c, caller); Unit } }.exceptionOrNull()
        assertThat((error as RelayError).code).isEqualTo("unauthenticated")
        assertThat(ai.settings(user).used).isEqualTo(0)
        assertThat(assertThrows(RelayError::class.java) { ai.select(user, AiSelection("private", key = "personal")) { c -> web.current(c, caller); Unit } }.code).isEqualTo("unauthenticated")
        assertThat(ai.settings(user).personal).isFalse()
    }
    @Test fun cookieSettingsReadNeedsNoOriginButMutationStillRequiresCsrf() = testApplication {
        application { relayModule(f.relay, webIdentity = web, ai = gateway()) }
        val cookie = "__Host-usuals-session=$cookieToken"
        val read = client.get("/v1/ai/settings") { header(HttpHeaders.Cookie, cookie) }
        assertThat(read.status.value).isEqualTo(200)
        assertThat(read.bodyAsText()).contains("Private")
        val refused = client.post("/v1/ai/settings") { header(HttpHeaders.Cookie, cookie); contentType(ContentType.Application.Json); setBody("{}") }
        assertThat(refused.status.value).isEqualTo(403)
        val accepted = client.post("/v1/ai/settings") { header(HttpHeaders.Cookie, cookie); header(HttpHeaders.Origin, f.origin); header("X-CSRF-Token", caller.csrf); contentType(ContentType.Application.Json); setBody("{}") }
        assertThat(accepted.status.value).isEqualTo(200)
    }
    private fun gateway(limit: Int = 20, keys: MutableList<String?> = mutableListOf()) = AiGateway(db,
        AiConfiguration(listOf(AiProvider("private", "Private", "http://ollama:11434/v1", "text", "vision", true)), wrappingKey = ByteArray(32) { 42 }, dailyLimit = limit), { now }) { config ->
        keys += config.apiKey
        object : LlmClient {
            override suspend fun probe() = ModelCapabilities(true, true, config.model)
            override suspend fun probeImages() = ModelCapabilities(true, true, config.model, supportsImages = true)
            override suspend fun parseUtterance(utterance: String) = listOf(ParsedItem("milk", Quantity(1.5, MeasureUnit.LITRE)))
            override suspend fun parseImage(image: ModelImage) = listOf(ParsedItem("bread"))
        }
    }
    @Test fun imagesRequireCurrentProbeAndProviderChangesInvalidateConsent() = runBlocking {
        val gateway = gateway(); val revision = gateway.settings(user).revision
        val image = AiRequest(image = "AQ==", mimeType = "image/jpeg", revision = revision)
        assertThat(assertThrows(RelayError::class.java) { runBlocking { gateway.execute(user, "image", image) } }.code).isEqualTo("ai_images_unverified")
        assertThat(gateway.execute(user, "probe-image", AiRequest(revision = revision)).images).isTrue()
        assertThat(gateway.execute(user, "image", image).items.single().name).isEqualTo("bread")
        gateway.select(user, AiSelection("private", key = "synthetic-private-key"))
        assertThat(assertThrows(RelayError::class.java) { runBlocking { gateway.execute(user, "image", image) } }.code).isEqualTo("ai_configuration_changed")
    }
    @Test fun personalKeysAreEncryptedBoundToAccountAndNeverReadBack() = runBlocking {
        val keys = mutableListOf<String?>(); val gateway = gateway(keys = keys)
        val settings = gateway.select(user, AiSelection("private", key = "synthetic-secret"))
        assertThat(settings.hasKey).isTrue(); assertThat(settings.toString()).doesNotContain("synthetic-secret")
        val encrypted = db.transaction { it.query("SELECT credential FROM ai_preference WHERE user_id=?", id(user)) { r -> r.getBytes(1) }.single() }
        assertThat(encrypted.toString(Charsets.UTF_8)).doesNotContain("synthetic-secret")
        gateway.execute(user, "text", AiRequest(text = "milk", revision = settings.revision))
        assertThat(keys).containsExactly("synthetic-secret")
        gateway.select(user, AiSelection())
        assertThat(gateway.settings(user).personal).isFalse()
    }
    @Test fun budgetPersistsAcrossGatewayInstancesAndResetsOnNextUtcDay() = runBlocking {
        val gateway = gateway(limit = 1); val request = AiRequest(text = "milk", revision = gateway.settings(user).revision)
        assertThat(gateway.execute(user, "text", request).items.single().amount).isEqualTo("1.5")
        assertThat(assertThrows(RelayError::class.java) { runBlocking { gateway(1).execute(user, "text", request) } }.code).isEqualTo("ai_budget")
        now += 86400000
        gateway(1).execute(user, "text", request)
        assertThat(gateway.settings(user).used).isEqualTo(1)
    }
    @Test fun monthlyProviderRequestsIncludeProbesPersistAcrossInstancesAndUseExpiredFallback() = runBlocking {
        val limits = AccountLimits(clock = { now })
        val policy = AccountPolicy(db, limits, ByteArray(32)) { now }
        policy.put(user, AccountPolicyUpdate(AccountLimit(25, 6, 20), AccountLimit(1, 2, 2), now + 300000))
        val calls = mutableListOf<String?>()
        val gateway = gateway(limit = 100, keys = calls)
        val request = AiRequest(text = "milk", revision = gateway.settings(user).revision)
        gateway.execute(user, "probe", AiRequest(revision = request.revision))
        repeat(19) { gateway.execute(user, "text", request) }
        assertThat(assertThrows(RelayError::class.java) { runBlocking { gateway(limit = 100, keys = calls).execute(user, "text", request) } }.code).isEqualTo("ai_monthly_budget")
        assertThat(calls).hasSize(20)
        now += 300000
        assertThat(db.transaction { limits.effective(it, user)?.monthlyAiRequests }).isEqualTo(2)
        now = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).plusMonths(1).withDayOfMonth(1).toInstant().toEpochMilli()
        repeat(2) { gateway.execute(user, "text", request) }
        assertThat(assertThrows(RelayError::class.java) { runBlocking { gateway.execute(user, "text", request) } }.code).isEqualTo("ai_monthly_budget")
    }
    @Test fun providerSelectionCannotInjectEndpointOrUnsupportedModels() {
        assertThrows(RelayError::class.java) { gateway().select(user, AiSelection("http://169.254.169.254")) }
        assertThrows(RelayError::class.java) { gateway().select(user, AiSelection("private", model = "cloud-model")) }
        assertThrows(IllegalArgumentException::class.java) { gateway().select(user, AiSelection("private", key = "bad\r\nkey")) }
    }
    @Test fun wrappingKeyRotationReencryptsCredentialsAndInvalidatesConsent(): Unit = runBlocking {
        val gateway = gateway()
        gateway.select(user, AiSelection("private", key = "synthetic-secret"))
        gateway.rewrap(ByteArray(32) { 9 })
        val keys = mutableListOf<String?>()
        val replacement = AiGateway(db, AiConfiguration(listOf(AiProvider("private", "Private", "http://ollama:11434/v1", "text", "vision", true)), wrappingKey = ByteArray(32) { 9 }), { now }) { config ->
            keys += config.apiKey
            object : LlmClient {
                override suspend fun probe() = ModelCapabilities(true, true, "text")
                override suspend fun parseUtterance(utterance: String) = emptyList<ParsedItem>()
            }
        }
        replacement.execute(user, "text", AiRequest(text = "milk", revision = replacement.settings(user).revision))
        assertThat(keys).containsExactly("synthetic-secret")
    }
    @Test fun httpRejectsAnonymousAndCrossOriginRequestsBeforeReadingImageBodies() = testApplication {
        application { relayModule(f.relay, webIdentity = web, ai = gateway()) }
        val anonymous = client.post("/v1/ai/image") { contentType(ContentType.Application.Json); setBody("invalid image body") }
        assertThat(anonymous.status.value).isEqualTo(401)
        assertThat(anonymous.bodyAsText()).doesNotContain("invalid image body")
        val csrf = client.post("/api/connect/${uuid()}/approve") { header(HttpHeaders.Origin, "https://evil.invalid") }
        assertThat(csrf.status.value).isEqualTo(401)
    }
    @Test fun saturatedInferenceLeavesRelayReadinessResponsive() = testApplication {
        val started = Channel<Unit>(2); val release = CompletableDeferred<Unit>()
        val gateway = AiGateway(db, AiConfiguration(listOf(AiProvider("private", "Private", "https://models.invalid/v1", "model"))), { now }) {
            object : LlmClient {
                override suspend fun probe() = ModelCapabilities(true, true, "model")
                override suspend fun parseUtterance(utterance: String): List<ParsedItem> { started.send(Unit); release.await(); return emptyList() }
            }
        }
        application { relayModule(f.relay, webIdentity = web, ai = gateway) }
        val (ticket, verifier) = ticket(); connections.approve(caller, ticket.id)
        connections.complete(ticket.id, ConnectFinish(ticket.pollToken, verifier), "test")
        val token = connectionToken(ticket.id, verifier, ticket.pollToken)
        val request = Wire.json.encodeToString(AiRequest.serializer(), AiRequest(text = "milk", revision = gateway.settings(user).revision))
        suspend fun inference() = client.post("/v1/ai/text") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(request) }
        coroutineScope {
            val first = async { inference() }; val second = async { inference() }
            try {
                withTimeout(10000) { repeat(2) { started.receive() } }
                assertThat(inference().status.value).isEqualTo(503)
                assertThat(client.get("/readyz").status.value).isEqualTo(200)
            } finally { release.complete(Unit) }
            assertThat(first.await().status.value).isEqualTo(200)
            assertThat(second.await().status.value).isEqualTo(200)
        }
    }
}

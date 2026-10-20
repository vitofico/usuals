package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.*
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.zaxxer.hikari.HikariDataSource

class RelayIntegrationTest {
    private lateinit var admin: HikariDataSource
    private lateinit var pool: HikariDataSource
    private lateinit var db: Database
    private lateinit var f: RelayFixture
    private val schema = "test_" + uuid().replace("-", "")
    private var now = 1_800_000_000_000L
    private val identity = Identity.generate()
    @Before fun before() {
        val url = checkNotNull(System.getenv("USUALS_TEST_JDBC_URL")) { "Run server/scripts/test-local with disposable PostgreSQL" }
        val user = checkNotNull(System.getenv("USUALS_TEST_DB_USER"))
        val password = checkNotNull(System.getenv("USUALS_TEST_DB_PASSWORD"))
        admin = Database.pool(url, user, password)
        admin.connection.use { c -> c.autoCommit = true; c.execute("CREATE SCHEMA $schema") }
        pool = Database.pool(url + if ('?' in url) "&currentSchema=$schema" else "?currentSchema=$schema", user, password)
        db = Database(pool); db.migrate(); db.fence(identity.serverId, "original")
        f = RelayFixture(db, identity) { now }.bootstrap()
    }
    @After fun after() {
        if (::pool.isInitialized) pool.close()
        if (::admin.isInitialized) {
            admin.connection.use { it.autoCommit = true; it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            admin.close()
        }
    }
    private fun denied(code: String, action: () -> Any?) {
        assertThat(assertThrows(RelayError::class.java) { action() }.code).isEqualTo(code)
    }
    @Test fun normalStartupAndRestorePreflightRejectUnsupportedSchemaWithoutChangingEitherBoundary() {
        db.checkSchema()
        val directory = java.nio.file.Files.createTempDirectory("usuals-schema-fence")
        val file = directory.resolve("fence"); val original = uuid()
        java.nio.file.Files.writeString(file, original)
        try {
            db.transaction { c -> c.execute("INSERT INTO schema_migration VALUES (6,'future')") }
            assertThat(assertThrows(IllegalStateException::class.java) { db.checkSchema() }.message).isEqualTo("migration_mismatch")
            assertThrows(IllegalStateException::class.java) { RestoreFence.apply(db, identity.serverId, file) }
            assertThat(java.nio.file.Files.readString(file)).isEqualTo(original)
            java.nio.file.Files.delete(file)
            assertThrows(IllegalStateException::class.java) { RestoreFence.apply(db, identity.serverId, file) }
            assertThat(java.nio.file.Files.exists(file)).isFalse()
            db.transaction { c -> c.execute("DELETE FROM schema_migration WHERE version=6"); c.execute("UPDATE schema_migration SET checksum='changed' WHERE version=1") }
            assertThrows(IllegalStateException::class.java) { db.checkSchema() }
            assertThrows(IllegalStateException::class.java) { RestoreFence.apply(db, identity.serverId, file) }
            assertThat(java.nio.file.Files.exists(file)).isFalse()
            assertThat(db.transaction { c -> c.query("SELECT checksum FROM schema_migration WHERE version=1") { it.getString(1) }.single() }).isEqualTo("changed")
            val checksum = Wire.hash(checkNotNull(javaClass.getResourceAsStream("/db/001_relay.sql")).use { it.readBytes() })
            db.transaction { it.execute("UPDATE schema_migration SET checksum=? WHERE version=1", checksum) }
            java.nio.file.Files.writeString(file, original)
            assertThrows(IllegalStateException::class.java) { RestoreFence.apply(db, Identity.generate().serverId, file) }
            assertThat(java.nio.file.Files.readString(file)).isEqualTo(original)
            assertThat(db.transaction { c -> c.query("SELECT frozen FROM household") { it.getBoolean(1) }.single() }).isFalse()
        } finally { java.nio.file.Files.deleteIfExists(file); java.nio.file.Files.delete(directory) }
    }
    @Test fun anAnonymousHeldBulkBodyCannotBlockAuthenticatedCatchUp() = testApplication {
        val list = f.createList(); val operations = RelayMetrics(db)
        application { relayModule(f.relay, operations) }
        coroutineScope {
        val held = io.ktor.utils.io.ByteChannel()
        val attack = async {
            client.put("/v1/lists/$list/snapshot") {
                bearerAuth(Wire.encode(ByteArray(32)))
                setBody(object : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
                    override val contentType = ContentType.Application.Json
                    override fun readFrom(): io.ktor.utils.io.ByteReadChannel = held
                })
            }
        }
        try {
            delay(100)
            val response = withTimeout(2000) { client.get("/v1/lists/$list/operations") { bearerAuth(f.token) } }
            assertThat(response.status.value).isEqualTo(200)
        } finally { held.cancel(null); attack.cancelAndJoin() }
        }
    }
    @Test fun operatorMetricsArePrivateAggregateAndReportErrorsWithoutRoutingLabels() = testApplication {
        val operations = RelayMetrics(db)
        application { relayModule(f.relay, operations) }
        assertThat(client.get("/metrics").status.value).isEqualTo(404)
        assertThat(client.get("/v1/households/${f.household}/lists").status.value).isEqualTo(401)
        assertThat(client.get("/readyz").status.value).isEqualTo(200)
        val monitor = operations.listen("127.0.0.1", 0)
        try {
            val connection = java.net.URI("http://127.0.0.1:${monitor.address.port}/metrics").toURL().openConnection() as java.net.HttpURLConnection
            val metrics = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            assertThat(metrics).contains("usuals_requests_total 2")
            assertThat(metrics).contains("usuals_request_client_errors_total 1")
            assertThat(metrics).contains("usuals_requests_active 0")
            assertThat(metrics).contains("usuals_storage_probe_ok 1")
            assertThat(metrics).doesNotContain(f.household); assertThat(metrics).doesNotContain(f.token)
            assertThat(metrics).doesNotContain("device"); assertThat(metrics).doesNotContain("household")
        } finally { monitor.stop(0) }
    }
    @Test fun restoreFenceInvalidatesBootstrapAuthorityAndMissingMetadataCannotBootstrapExistingRows() {
        f.auth.bootstrapToken()
        db.fence(identity.serverId, uuid())
        assertThat(db.transaction { c -> c.query("SELECT count(*) FROM bootstrap_token") { it.getLong(1) }.single() }).isEqualTo(0)
        denied("recovery_required") { f.auth.challenge(ChallengeRequest(f.owner.keys.id, "session"), "after-fence") }
        db.transaction { it.execute("DELETE FROM relay_meta") }
        assertThrows(IllegalStateException::class.java) { db.fence(identity.serverId, uuid()) }
        assertThat(db.transaction { c -> c.query("SELECT count(*) FROM relay_meta") { it.getLong(1) }.single() }).isEqualTo(0)
    }
    @Test fun challengeProofsArePurposeBoundSingleUseAndExpire() {
        val challenge = f.auth.challenge(ChallengeRequest(f.owner.keys.id, "session"), "proof")
        val proof = f.proof(f.owner, challenge)
        f.auth.session(proof)
        denied("unauthenticated") { f.auth.session(proof) }
        val next = f.auth.challenge(ChallengeRequest(f.owner.keys.id, "session"), "proof")
        denied("unauthenticated") { f.auth.session(f.proof(f.owner, next.copy(purpose = "recovery"))) }
        now += 60001
        denied("unauthenticated") { f.auth.session(f.proof(f.owner, next)) }
        db.transaction { c ->
            val sessions = c.query("SELECT hash FROM auth_session") { it.getBytes(1) }
            assertThat(sessions.none { it.contentEquals(f.token.toByteArray()) }).isTrue()
        }
    }
    @Test fun appendRetryConflictAndBatchRollbackPreserveCursor() {
        val list = f.createList()
        val first = f.envelope(list)
        val receipt = f.relay.append(f.token, list, Append(listOf(first))).results.single().receipt
        val retry = f.relay.append(f.token, list, Append(listOf(first, first)))
        assertThat(retry.currentSeq).isEqualTo(1)
        assertThat(retry.results.map { it.receipt }).containsExactly(receipt, receipt)
        val different = f.envelope(list, intent = first.parsed().intentId)
        val fresh = f.envelope(list)
        denied("intent_exists") { f.relay.append(f.token, list, Append(listOf(fresh, different))) }
        assertThat(f.relay.operations(f.token, list, 0).entries).hasSize(1)
        val changed = f.envelope(list, envelope = first.parsed().envelopeId)
        denied("envelope_conflict") { f.relay.append(f.token, list, Append(listOf(changed))) }
        val accepted = f.relay.append(f.token, list, Append(listOf(first, fresh, fresh)))
        assertThat(accepted.results.map { it.status }).containsExactly("duplicate", "accepted", "duplicate").inOrder()
        assertThat(accepted.currentSeq).isEqualTo(2)
        val entries = f.relay.operations(f.token, list, 0, 1)
        assertThat(entries.nextCursor).isEqualTo(1); assertThat(entries.hasMore).isTrue()
        verify(Wire.encode(identity.publicKey), receipt.signature, Wire.receipt(identity.serverId, f.household, list, receipt.seq, receipt.head))
        assertThat(f.relay.intent(f.token, list, first.parsed().intentId).envelope).isEqualTo(first)
    }
    @Test fun concurrentWritersSerializeAtCommitAndRestartRetainsExactReceipts() {
        val list = f.createList()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val holder = executor.submit {
                db.transaction { c -> c.household(f.household); started.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
            }
            check(started.await(1, TimeUnit.SECONDS))
            val first = f.envelope(list); val second = f.envelope(list)
            val pending = executor.submit<AppendResults> { f.relay.append(f.token, list, Append(listOf(first, second))) }
            assertThat(db.transaction { c -> c.query("SELECT last_seq FROM shared_list WHERE id=?", id(list)) { it.getLong(1) }.single() }).isEqualTo(0)
            assertThat(pending.isDone).isFalse()
            release.countDown(); holder.get(3, TimeUnit.SECONDS)
            val result = pending.get(5, TimeUnit.SECONDS)
            val a = executor.submit<AppendResults> { f.relay.append(f.token, list, Append(listOf(f.envelope(list)))) }
            val b = executor.submit<AppendResults> { f.relay.append(f.token, list, Append(listOf(f.envelope(list)))) }
            assertThat(listOf(a.get().currentSeq, b.get().currentSeq)).containsExactly(3L, 4L)
            val restarted = Relay(Database(pool), identity, Auth(Database(pool), identity, f.origin) { now })
            assertThat(restarted.append(f.token, list, Append(listOf(first))).results.single().receipt).isEqualTo(result.results.first().receipt)
            val log = restarted.operations(f.token, list, 0).entries
            assertThat(log.map { it.receipt.seq }).containsExactly(1L, 2L, 3L, 4L).inOrder()
            log.zipWithNext().forEach { (x,y) -> assertThat(y.receipt.previousHead).isEqualTo(x.receipt.head) }
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun crossHouseholdAndEditorControlRoutesDenyAndRevocationWins() {
        val list = f.createList()
        val other = RelayFixture(db, identity) { now }.bootstrap()
        denied("not_found") { f.relay.operations(other.token, list, 0) }
        denied("not_found") { f.relay.append(other.token, list, Append(listOf(f.envelope(list)))) }
        val editor = TestDevice(); val editorToken = f.addMember(editor)
        denied("forbidden") { f.membership.invite(editorToken, f.household, InviteRequest(uuid(), "editor")) }
        val beforeRemoval = f.envelope(list, editor)
        f.relay.append(editorToken, list, Append(listOf(beforeRemoval)))
        val removed = f.next("remove", members = f.current.body().members.filter { member -> member.devices.none { it.id == editor.keys.id } })
        f.membership.change(f.token, f.household, removed); f.current = removed
        denied("forbidden") { f.relay.snapshot(editorToken, list) }
        denied("forbidden") { f.relay.append(editorToken, list, Append(listOf(beforeRemoval))) }
        denied("forbidden") { f.session(editor) }
    }
    @Test fun membershipChecksMissingPackagesHeadRacesAndStaleEpochWithoutPartialWrites() {
        val list = f.createList()
        val oldEnvelope = f.envelope(list)
        val proposed = f.next("rotate")
        denied("invalid") { f.membership.change(f.token, f.household, proposed.copy(packages = emptyList())) }
        f.relay.append(f.token, list, Append(listOf(oldEnvelope)))
        denied("membership_conflict") { f.membership.change(f.token, f.household, proposed) }
        val next = f.next("rotate"); f.membership.change(f.token, f.household, next); f.current = next
        assertThat(f.relay.append(f.token, list, Append(listOf(oldEnvelope))).results.single().status).isEqualTo("duplicate")
        denied("epoch_stale") { f.relay.append(f.token, list, Append(listOf(f.envelope(list, epoch = 2, manifest = oldEnvelope.parsed().manifestHash)))) }
        assertThat(f.relay.operations(f.token, list, 0).entries).hasSize(1)
    }
    @Test fun recoveryIsRestrictedAndRestoreFenceBlocksFreshProofsAndOldSessions() {
        val list = f.createList()
        val recovery = f.session(f.recovery, "recovery")
        assertThat(f.relay.lists(recovery, f.household).listIds).containsExactly(list)
        assertThat(f.relay.packages(recovery, f.household, list, f.current.body().epoch).packages).hasSize(1)
        denied("forbidden") { f.relay.append(recovery, list, Append(listOf(f.envelope(list, f.recovery)))) }
        denied("forbidden") { f.membership.invite(recovery, f.household, InviteRequest(uuid(), "editor")) }
        val invalid = f.next("recover", author = f.recovery, session = recovery)
        denied("forbidden") { f.membership.change(recovery, f.household, invalid) }
        val newOwner = TestDevice()
        val recovered = f.next("recover", members = listOf(RosterMember(uuid(), "owner", listOf(newOwner.keys))), author = f.recovery, session = recovery)
        f.membership.change(recovery, f.household, recovered); f.current = recovered
        denied("forbidden") { f.relay.lists(f.token, f.household) }
        val newToken = f.session(newOwner)
        assertThat(f.relay.lists(newToken, f.household).listIds).containsExactly(list)
        db.fence(identity.serverId, "after-restore")
        denied("recovery_required") { f.session(f.owner) }
        denied("recovery_required") { f.relay.lists(newToken, f.household) }
        denied("recovery_required") { f.session(f.recovery, "recovery") }
        db.fence(identity.serverId, "after-restore")
        assertThat(assertThrows(IllegalStateException::class.java) { db.fence(Identity.generate().serverId, "after-restore") }.message).isEqualTo("identity_mismatch")
    }
    @Test fun rateAndQuotaFailuresAreDurableAndPreservePendingCiphertext() {
        val list = f.createList()
        repeat(30) { f.auth.challenge(ChallengeRequest(uuid(), "bootstrap", f.owner.keys.signingPublicKey), "rate") }
        denied("rate_limited") { f.auth.challenge(ChallengeRequest(uuid(), "bootstrap", f.owner.keys.signingPublicKey), "rate") }
        val e = f.envelope(list)
        db.transaction { c -> c.execute("UPDATE shared_list SET storage_bytes=? WHERE id=?", 100L * 1024 * 1024, id(list)) }
        denied("quota_exceeded") { f.relay.append(f.token, list, Append(listOf(e))) }
        assertThat(f.relay.operations(f.token, list, 0).entries).isEmpty()
        db.transaction { c -> c.execute("UPDATE shared_list SET storage_bytes=0 WHERE id=?", id(list)) }
        assertThat(f.relay.append(f.token, list, Append(listOf(e))).currentSeq).isEqualTo(1)
        now += 60001
        f.auth.challenge(ChallengeRequest(uuid(), "bootstrap", f.owner.keys.signingPublicKey), "rate")
    }
    @Test fun databaseContainsOnlyOpaqueShoppingPayloadAndMigrationChecksumIsEnforced() {
        val list = f.createList(); f.relay.append(f.token, list, Append(listOf(f.envelope(list))))
        db.transaction { c ->
            for ((table, column) in listOf("operation" to "data", "published_snapshot" to "data", "manifest" to "proof", "key_package" to "data")) {
                c.query("SELECT $column FROM $table") { it.getBytes(1) }.forEach { assertThat(it.toString(Charsets.UTF_8)).doesNotContain("synthetic-secret-milk") }
            }
        }
        db.migrate()
        db.transaction { it.execute("UPDATE schema_migration SET checksum='modified'") }
        assertThrows(IllegalStateException::class.java) { db.migrate() }
    }
    @Test fun httpBoundsStrictParsingAuthAndWireResponsesMatchContract() = testApplication {
        application { relayModule(f.relay) }
        val list = f.createList()
        assertThat(client.get("/healthz").status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.get("/readyz").status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.get("/v1/lists/$list/operations").status).isEqualTo(HttpStatusCode.Unauthorized)
        val duplicate = client.post("/v1/devices/challenge") { contentType(ContentType.Application.Json); setBody("{\"purpose\":\"join\",\"purpose\":\"bootstrap\"}") }
        assertThat(duplicate.status).isEqualTo(HttpStatusCode.BadRequest)
        val tooLarge = client.post("/v1/lists/$list/operations") { contentType(ContentType.Application.Json); bearerAuth(f.token); setBody(" ".repeat(1024 * 1024 + 1)) }
        assertThat(tooLarge.status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        val response = client.post("/v1/lists/$list/operations") { contentType(ContentType.Application.Json); bearerAuth(f.token); setBody(Wire.json.encodeToString(Append.serializer(), Append(listOf(f.envelope(list))))) }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        assertThat(Wire.json.parseToJsonElement(response.bodyAsText()).jsonObject.keys).containsExactly("results", "head", "lastSeq")
        val pulled = client.get("/v1/lists/$list/operations?after=0&limit=100") { bearerAuth(f.token) }
        val result = Wire.json.decodeFromString<Operations>(pulled.bodyAsText())
        assertThat(result.entries).hasSize(1); assertThat(result.nextCursor).isEqualTo(1); assertThat(result.hasMore).isFalse()
    }
    @Test fun signedManifestSchemaAndFreshKeysAreEnforcedBeforeAuthorityChanges() {
        f.createList()
        val rotate = f.next("rotate")
        val objectValue = Wire.json.parseToJsonElement(Wire.decode(rotate.manifestBytes).toString(Charsets.UTF_8)).jsonObject
        fun modified(fields: Map<String, JsonElement>): SignedManifest {
            val draft = rotate.copy(manifestBytes = Wire.encode(JsonObject(fields).toString().toByteArray()))
            return draft.copy(signature = f.owner.sign(draft.signingBytes()))
        }
        for (key in listOf("version", "lists", "packageHashes", "approvedJoins")) {
            assertThrows(IllegalArgumentException::class.java) { f.membership.change(f.token, f.household, modified(objectValue - key)) }
        }
        for (key in listOf("createdListId", "transferToDeviceId")) {
            assertThrows(IllegalArgumentException::class.java) { f.membership.change(f.token, f.household, modified(objectValue + (key to JsonNull))) }
        }
        val replacement = TestDevice()
        for (keys in listOf(replacement.keys.copy(signingPublicKey = f.recovery.keys.signingPublicKey), replacement.keys.copy(recipientPublicKey = f.recovery.keys.recipientPublicKey))) {
            denied("invalid") { f.membership.change(f.token, f.household, f.next("replace_recovery", newRecovery = keys)) }
            // Failed candidates remain inspectable and bounded until TTL, as real clients must retry from fresh cutoffs.
            db.transaction { it.execute("DELETE FROM snapshot_candidate") }
        }
        val accepted = f.next("replace_recovery", newRecovery = replacement.keys)
        f.membership.change(f.token, f.household, accepted); f.current = accepted
        denied("forbidden") { f.session(f.recovery, "recovery") }
        assertThat(f.session(replacement, "recovery")).isNotEmpty()
    }
    @Test fun denyingOneApplicantInvalidatesAllRequestsForTheConsumedInvitation() {
        val member = uuid(); val invite = f.membership.invite(f.token, f.household, InviteRequest(member, "editor"))
        val first = f.requestJoin(invite, TestDevice(), member)
        f.requestJoin(invite, TestDevice(), member)
        assertThat(f.membership.pending(f.token, f.household).requests).hasSize(2)
        f.membership.deny(f.token, f.household, first.first)
        assertThat(f.membership.pending(f.token, f.household).requests).isEmpty()
        denied("invalid") { f.requestJoin(invite, TestDevice(), member) }
    }
    @Test fun consumedInviteCannotApproveASecondDeviceAndExpiredApprovalsFail() {
        val member = uuid(); val invite = f.membership.invite(f.token, f.household, InviteRequest(member, "editor"))
        val a = TestDevice(); val b = TestDevice()
        val first = f.requestJoin(invite, a, member); val second = f.requestJoin(invite, b, member)
        val accepted = f.next("approve_join", members = f.current.body().members + RosterMember(member, "editor", listOf(a.keys)), joins = mapOf(first))
        f.membership.change(f.token, f.household, accepted); f.current = accepted
        val again = f.next("approve_join", members = f.current.body().members.map { if (it.id == member) it.copy(devices = it.devices + b.keys) else it }, joins = mapOf(second))
        denied("membership_conflict") { f.membership.change(f.token, f.household, again) }
        val expiredDevice = TestDevice(); val newMember = uuid(); val pending = f.join(expiredDevice, newMember)
        now += 600001
        val expired = f.next("approve_join", members = f.current.body().members + RosterMember(newMember, "editor", listOf(expiredDevice.keys)), joins = mapOf(pending))
        denied("invalid") { f.membership.change(f.token, f.household, expired) }
    }
    @Test fun expiredApprovalWitnessIsOwnerScopedSignedAndSurvivesRequestCleanup() = testApplication {
        val member = uuid(); val pending = f.join(TestDevice(), member)
        val witness = f.membership.pending(f.token, f.household).requests.single()
        denied("membership_conflict") { f.membership.expired(f.token, f.household, pending.first, witness) }
        application { relayModule(f.relay) }
        val path = "/v1/households/${f.household}/join-requests/${pending.first}/expired"
        assertThat(client.post(path) { contentType(ContentType.Application.Json); setBody(encode(witness)) }.status.value).isEqualTo(401)
        now += 600001
        // Invitation creation performs TTL cleanup. Recovery must not depend on retained rows.
        f.membership.invite(f.token, f.household, InviteRequest(uuid(), "editor"))
        assertThat(db.transaction { c -> c.query("SELECT count(*) FROM join_request") { it.getLong(1) }.single() }).isEqualTo(0)
        denied("invalid") { f.membership.expired(f.token, f.household, uuid(), witness) }
        denied("forbidden") { f.membership.expired(f.token, f.household, pending.first, witness.copy(signature = Wire.encode(ByteArray(64)))) }
        val result = client.post(path) { bearerAuth(f.token); contentType(ContentType.Application.Json); setBody(encode(witness)) }
        assertThat(result.status.value).isEqualTo(200)
        assertThat(Wire.json.decodeFromString<JoinPending>(result.bodyAsText())).isEqualTo(JoinPending(pending.first, now - 1))
        val editor = TestDevice(); val nextMember = uuid(); val accepted = f.join(editor, nextMember)
        val manifest = f.next("approve_join", members = f.current.body().members + RosterMember(nextMember, "editor", listOf(editor.keys)), joins = mapOf(accepted))
        f.membership.change(f.token, f.household, manifest)
        denied("forbidden") { f.membership.expired(f.session(editor), f.household, pending.first, witness) }
    }
    @Test fun intentConflictReturnsOnlyACommittedReceiptEvenWhenTheBatchRollsBack() {
        val list = f.createList(); val original = f.envelope(list)
        val receipt = f.relay.append(f.token, list, Append(listOf(original))).results.single().receipt
        val fresh = f.envelope(list)
        val error = assertThrows(RelayError::class.java) { f.relay.append(f.token, list, Append(listOf(fresh, f.envelope(list, intent = original.parsed().intentId)))) }
        assertThat(error.inputIndex).isEqualTo(1)
        assertThat(error.existingEnvelopeId).isEqualTo(original.parsed().envelopeId)
        assertThat(error.existingReceipt).isEqualTo(receipt)
        val collision = assertThrows(RelayError::class.java) { f.relay.append(f.token, list, Append(listOf(fresh, f.envelope(list, intent = fresh.parsed().intentId)))) }
        assertThat(collision.existingEnvelopeId).isNull(); assertThat(collision.existingReceipt).isNull()
        assertThat(f.relay.operations(f.token, list, 0).entries).hasSize(1)
    }
    @Test fun everyHttpDataRouteRequiresAuthenticationAndMembershipWritesUseTheResponseWrapper() = testApplication {
        application { relayModule(f.relay) }
        val list = f.createList()
        val api = Wire.json.parseToJsonElement(java.io.File(System.getProperty("usuals.root"), "docs/sync/openapi.json").readText()).jsonObject
        val public = setOf("/healthz", "/readyz", "/v1/server-info", "/v1/devices/challenge", "/v1/devices/session", "/v1/join-requests")
        for ((path, methods) in api.getValue("paths").jsonObject) {
            if (path in public || path == "/v1/changes") continue
            val resolved = path.replace("{h}", f.household).replace("{l}", list).replace("{r}", uuid()).replace("{i}", uuid()).replace("{c}", uuid())
            for (method in methods.jsonObject.keys) {
                val response = client.request(resolved) { this.method = HttpMethod.parse(method.uppercase()) }
                assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
            }
        }
        val rotate = f.next("rotate")
        val response = client.post("/v1/households/${f.household}/membership") { bearerAuth(f.token); contentType(ContentType.Application.Json); setBody(Wire.json.encodeToString(SignedManifest.serializer(), rotate)) }
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val result = Wire.json.decodeFromString<Membership>(response.bodyAsText())
        assertThat(result.manifests.single().signed().hash()).isEqualTo(rotate.hash())
        assertThat(result.nextEpoch).isEqualTo(rotate.body().epoch)
        val missingList = client.get("/v1/households/${f.household}/key-packages?epoch=${rotate.body().epoch}") { bearerAuth(f.token) }
        assertThat(missingList.status).isEqualTo(HttpStatusCode.BadRequest)
        val packages = client.get("/v1/households/${f.household}/key-packages?epoch=${rotate.body().epoch}&listId=$list") { bearerAuth(f.token) }
        val keys = Wire.json.decodeFromString<KeyPackages>(packages.bodyAsText()).packages
        assertThat(keys.map { it.listId }).containsExactly(list)
        f.current = rotate
        val newList = uuid()
        val proposed = f.next("create_list", listIds = f.current.body().lists.map { it.listId } + newList)
        val createdList = client.post("/v1/households/${f.household}/lists") { bearerAuth(f.token); contentType(ContentType.Application.Json); setBody(Wire.json.encodeToString(SignedManifest.serializer(), proposed)) }
        assertThat(createdList.status).isEqualTo(HttpStatusCode.Created)
        assertThat(Wire.json.decodeFromString<Membership>(createdList.bodyAsText()).manifests.single().signed().hash()).isEqualTo(proposed.hash())
        val other = RelayFixture(db, identity) { now }
        val (bootstrap, request) = other.bootstrapRequest()
        val createdHousehold = client.post("/v1/households") { bearerAuth(bootstrap); contentType(ContentType.Application.Json); setBody(Wire.json.encodeToString(CreateHousehold.serializer(), request)) }
        assertThat(createdHousehold.status).isEqualTo(HttpStatusCode.Created)
        assertThat(Wire.json.decodeFromString<Membership>(createdHousehold.bodyAsText()).manifests.single().signed().hash()).isEqualTo(request.manifest.hash())
    }

    @Test fun socketsHintAfterCommitAndCloseOnRevocationWithoutShoppingChanges() = testApplication {
        application { relayModule(f.relay) }
        val list = f.createList(); val editor = TestDevice(); val editorToken = f.addMember(editor)
        val socketClient = createClient { install(io.ktor.client.plugins.websocket.WebSockets) }
        socketClient.webSocket(urlString = "/v1/changes", request = { bearerAuth(editorToken) }) {
            send(Frame.Text("[\"${f.household}\"]"))
            val connected = withTimeout(5000) { incoming.receive() } as Frame.Text
            assertThat(connected.readText()).contains(f.household)
            f.relay.append(f.token, list, Append(listOf(f.envelope(list))))
            val hint = withTimeout(5000) { incoming.receive() } as Frame.Text
            assertThat(hint.readText()).contains(f.household)
            assertThat(f.relay.operations(editorToken, list, 0).entries).hasSize(1)
            val next = f.next("remove", members = f.current.body().members.filter { member -> member.devices.none { it.id == editor.keys.id } })
            f.membership.change(f.token, f.household, next); f.current = next
            assertThat(withTimeout(5000) { closeReason.await() }?.code).isEqualTo(CloseReason.Codes.VIOLATED_POLICY.code)
        }
        socketClient.close()
    }

    @Test fun frozenExportIsRecipientScopedAndCreatesOnlyAVerifiedLocalCopyWithoutAuthentication() = testApplication {
        application { relayModule(f.relay) }
        var requests = 0
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                requests++
                val response = client.request(path) {
                    this.method = HttpMethod.parse(method)
                    if (token != null) bearerAuth(token)
                    if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                }
                return WireResponse(response.status.value, response.bodyAsText().toByteArray().also { require(it.size <= maxResponse) })
            }
        }
        val state = MemorySyncStore(); val source = CheckpointSource { _, _ -> ListCheckpoint(0, Wire.zero, SnapshotCodec.encode(Projection(name = "Encrypted groceries"))) }
        val owner = HouseholdClient(state, transport, source) { now }
        val setup = owner.prepare(f.origin); val household = owner.bootstrap(setup.verifySaved(setup.file, setup.secret), f.auth.bootstrapToken())
        val list = uuid(); owner.createList(household, list)
        val shopping = MemoryReplica()
        shopping.commands += Intent(uuid(), list, "capture", buildJsonObject { put("proposedId", uuid()); put("name", "Private milk"); put("key", "private milk") })
        owner.synchronize(household, list, shopping)
        val before = state.read(); val installed = before.households.getValue(household)
        val directory = java.nio.file.Files.createTempDirectory("usuals-frozen-export-test")
        val grantFile = directory.resolve("grant.json"); val archive = directory.resolve("list.usuals")
        val exporter = FrozenExporter(db, identity, f.origin)
        fun grant(recipient: DeviceKeys): ExportGrant {
            val fingerprint = Wire.fingerprint(identity.serverId, household, recipient.signingPublicKey, recipient.recipientPublicKey)
            return ExportGrant(1, f.origin, identity.serverId, household, list, recipient, Wire.decode(fingerprint, 32).joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        try {
            java.nio.file.Files.createFile(grantFile, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
            java.nio.file.Files.write(grantFile, encode(grant(installed.device.publicKeys)))
            assertThrows(IllegalArgumentException::class.java) { exporter.export(grantFile, archive) }
            db.fence(identity.serverId, uuid())
            denied("recovery_required") { f.auth.challenge(ChallengeRequest(installed.device.publicKeys.id, "session"), "frozen-owner") }
            exporter.export(grantFile, archive)
            assertThat(java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(archive))).isEqualTo("rw-------")
            java.nio.file.Files.setPosixFilePermissions(grantFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----"))
            assertThrows(IllegalArgumentException::class.java) { exporter.export(grantFile, directory.resolve("unprotected")) }
            java.nio.file.Files.setPosixFilePermissions(grantFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
            val link = directory.resolve("grant-link")
            java.nio.file.Files.createSymbolicLink(link, grantFile)
            assertThrows(IllegalArgumentException::class.java) { exporter.export(link, directory.resolve("symlink")) }
            java.nio.file.Files.delete(link)
            java.nio.file.Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"))
            assertThrows(IllegalArgumentException::class.java) { exporter.export(grantFile, directory.resolve("public-parent")) }
            java.nio.file.Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            val bytes = java.nio.file.Files.readAllBytes(archive)
            assertThat(bytes.toString(Charsets.UTF_8)).doesNotContain("Private milk")
            val networkBefore = requests
            val copy = owner.previewFrozen(java.io.ByteArrayInputStream(bytes))
            assertThat(copy.name).isEqualTo("Encrypted groceries"); assertThat(copy.items).isEqualTo(1)
            assertThat(copy.older).isFalse(); assertThat(copy.ancestryUnverifiable).isFalse()
            assertThat(state.read()).isEqualTo(before); assertThat(requests).isEqualTo(networkBefore)
            assertThrows(IllegalArgumentException::class.java) { exporter.export(grantFile, archive) }
            java.nio.file.Files.delete(archive)
            java.nio.file.Files.write(grantFile, encode(grant(installed.device.publicKeys).copy(verifiedFingerprint = "0".repeat(64))))
            assertThrows(IllegalArgumentException::class.java) { exporter.export(grantFile, archive) }
            val kit = RecoveryKits.open(setup.file, setup.secret)
            java.nio.file.Files.write(grantFile, encode(grant(kit.recovery.publicKeys)))
            exporter.export(grantFile, archive)
            val freshState = MemorySyncStore(); val fresh = HouseholdClient(freshState, transport, source) { now }
            val recovered = java.nio.file.Files.newInputStream(archive).use { fresh.previewFrozen(it, setup.file, setup.secret) }
            assertThat(recovered.items).isEqualTo(1); assertThat(freshState.read().households).isEmpty()
            assertThat(requests).isEqualTo(networkBefore)
            assertThat(db.transaction { c -> c.query("SELECT frozen FROM household WHERE id=?", id(household)) { it.getBoolean(1) }.single() }).isTrue()
        } finally {
            java.nio.file.Files.list(directory).use { paths -> paths.forEach { java.nio.file.Files.deleteIfExists(it) } }
            java.nio.file.Files.deleteIfExists(directory)
        }
    }

    @Test fun realClientsVerifyJoinFingerprintsRotateKeysAndResolveALostMembershipAck() = testApplication {
        application { relayModule(f.relay) }
        var loseOperationAck = false
        var truncateAfterAppend = false
        var blockAppend = false
        val appendBodies = mutableListOf<ByteArray>()
        var loseAck = false
        var expireCandidates = false
        var afterStage: (() -> Unit)? = null
        var truncateOperations = false
        var breakBridge = false
        var failKeyRead = false
        var interruptRecovery = false
        var recoveryDevice: String? = null
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse {
                require(origin == f.origin)
                if (method == "POST" && path.endsWith("/operations")) {
                    appendBodies += requireNotNull(body).copyOf()
                    if (blockAppend) throw RelayProblem("offline")
                }
                if (failKeyRead && path.contains("/key-packages?")) { failKeyRead = false; throw RelayProblem("offline") }
                if (interruptRecovery && method == "POST" && path.endsWith("/membership") && body != null && Wire.json.decodeFromString(SignedManifest.serializer(), body.toString(Charsets.UTF_8)).body().action == "recover") {
                    interruptRecovery = false; throw RelayProblem("offline")
                }
                if (path == "/v1/devices/challenge" && recoveryDevice != null && body != null) {
                    val challenge = Wire.json.decodeFromString(ChallengeRequest.serializer(), body.toString(Charsets.UTF_8))
                    require(challenge.deviceId != recoveryDevice || challenge.purpose == "recovery")
                }
                if (expireCandidates && path.endsWith("/membership") && method == "POST") {
                    expireCandidates = false
                    f.db.transaction { c -> c.execute("UPDATE snapshot_candidate SET expires=0") }
                }
                val response = client.request(path) {
                    this.method = HttpMethod.parse(method)
                    if (token != null) bearerAuth(token)
                    if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                }
                if (loseAck && path.endsWith("/membership") && method == "POST" && response.status == HttpStatusCode.OK) {
                    loseAck = false; throw RelayProblem("offline")
                }
                if (method == "POST" && path.endsWith("/operations") && response.status == HttpStatusCode.OK) {
                    if (loseOperationAck) { loseOperationAck = false; throw RelayProblem("offline") }
                    if (truncateAfterAppend) { truncateAfterAppend = false; truncateOperations = true }
                }
                var bytes = response.bodyAsText().toByteArray()
                if (method == "PUT" && path.contains("/snapshot-candidates/")) afterStage?.let { action -> afterStage = null; action() }
                if (path.contains("/operations?") && response.status == HttpStatusCode.OK) {
                    if (truncateOperations) bytes = Wire.json.encodeToString(Operations.serializer(), Operations(emptyList(), path.substringAfter("after=").substringBefore('&').toLong(), false)).toByteArray()
                    if (breakBridge && path.endsWith("after=3&limit=1")) {
                        val page = Wire.json.decodeFromString(Operations.serializer(), bytes.toString(Charsets.UTF_8))
                        bytes = Wire.json.encodeToString(Operations.serializer(), page.copy(entries = page.entries.map { it.copy(receipt = it.receipt.copy(previousHead = Wire.zero)) })).toByteArray()
                    }
                }
                if (recoveryDevice != null && path == "/v1/devices/session" && response.status == HttpStatusCode.OK && body != null) {
                    val proof = Wire.json.decodeFromString(Proof.serializer(), body.toString(Charsets.UTF_8))
                    if (proof.deviceId == recoveryDevice) {
                        val session = Wire.json.decodeFromString(Session.serializer(), bytes.toString(Charsets.UTF_8))
                        bytes = Wire.json.encodeToString(Session.serializer(), session.copy(expiresAt = now + 1)).toByteArray()
                    }
                }
                require(bytes.size <= maxResponse)
                return WireResponse(response.status.value, bytes)
            }
        }
        val source = CheckpointSource { _, _ -> ListCheckpoint(0, Wire.zero, SnapshotCodec.encode(Projection(name = "synthetic-shared-snapshot"))) }
        val ownerState = MemorySyncStore(); val owner = HouseholdClient(ownerState, transport, source) { now }
        val prepared = owner.prepare(f.origin)
        assertThrows(java.security.GeneralSecurityException::class.java) { prepared.verifySaved(prepared.file, Wire.encode(SyncCrypto.randomKey())) }
        val household = owner.bootstrap(prepared.verifySaved(prepared.file, prepared.secret), f.auth.bootstrapToken())
        val list = uuid(); owner.createList(household, list)
        val beforeDuplicateBootstrap = ownerState.read()
        try { owner.bootstrap(prepared.verifySaved(prepared.file, prepared.secret), f.auth.bootstrapToken()); error("Bootstrap cannot reset existing pins") }
        catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("already_joined") }
        assertThat(ownerState.read()).isEqualTo(beforeDuplicateBootstrap)
        fun appendSynthetic() {
            val saved = ownerState.read().households.getValue(household)
            val token = requireNotNull(saved.session).token
            val latest = f.relay.operations(token, list, 0).entries.lastOrNull()?.receipt
            val intent = Intent(uuid(), list, "capture", kotlinx.serialization.json.buildJsonObject {
                put("proposedId", kotlinx.serialization.json.JsonPrimitive(uuid())); put("name", kotlinx.serialization.json.JsonPrimitive("Synthetic")); put("key", kotlinx.serialization.json.JsonPrimitive(uuid()))
            })
            val header = Header(saved.server.serverId, household, list, saved.device.publicKeys.id, intent.id, uuid(), saved.epoch, saved.hash, latest?.seq ?: 0, latest?.head ?: Wire.zero)
            val envelope = SyncCrypto.seal(header, Wire.json.encodeToString(Intent.serializer(), intent).toByteArray(), Wire.decode(saved.keys.getValue("$list:${saved.epoch}"), 32), saved.device) { ownerState.reserve("test-operation") }
            f.relay.append(token, list, Append(listOf(envelope)))
        }
        appendSynthetic()
        assertThat(owner.checkpoint(household, list).seq).isEqualTo(1)
        truncateOperations = true
        try { owner.checkpoint(household, list); error("Known head rollback must fail") } catch (_: IllegalArgumentException) { }
        truncateOperations = false
        appendSynthetic()
        val invitation = owner.invitation(household)
        val applicantState = MemorySyncStore(); val applicant = HouseholdClient(applicantState, transport, source) { now }
        val pending = applicant.requestJoin(invitation.uri())
        applicant.resumeJoin()
        val requests = owner.pending(household)
        assertThat(requests).hasSize(1)
        assertThrows(IllegalArgumentException::class.java) { owner.verifyApplicant(household, requests.single(), "0".repeat(64)) }
        val verified = owner.verifyReverseQr(household, requests.single(), applicant.reverseQr(pending))
        afterStage = { appendSynthetic() }
        try { owner.approve(household, verified); error("Concurrent append must invalidate the snapshot") }
        catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("membership_conflict") }
        assertThat(owner.discardInvalidPending(household)).isTrue()
        assertThat(ownerState.read().households.getValue(household).pending).isNull()
        loseAck = true
        try { owner.approve(household, verified); error("Expected ambiguous acknowledgement") } catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("offline") }
        assertThat(ownerState.read().households.getValue(household).pending).isNotNull()
        val resumed = HouseholdClient(ownerState, transport, source) { now }
        resumed.resume(household)
        assertThat(ownerState.read().households.getValue(household).pending).isNull()
        failKeyRead = true
        try { applicant.completeJoin(); error("Key fetch must fail once") } catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("offline") }
        assertThat(applicantState.read().households).doesNotContainKey(household)
        assertThat(applicant.joining()).isNotNull()
        assertThat(applicant.completeJoin()).isEqualTo(household)
        val beforeDuplicateJoin = applicantState.read()
        try { applicant.requestJoin(invitation.uri()); error("An existing local identity must not be overwritten") }
        catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("already_joined") }
        assertThat(applicantState.read()).isEqualTo(beforeDuplicateJoin)
        val beforeRenewal = applicantState.read().households.getValue(household)
        resumed.rotate(household); resumed.rotate(household); resumed.resume(household)
        val applicantDevice = beforeRenewal.device.publicKeys.id
        fun sessionCount() = f.db.transaction { c -> c.query("SELECT count(*) FROM auth_session WHERE device=?", id(applicantDevice)) { it.getLong(1) }.single() }
        val sessionsBefore = sessionCount()
        f.db.transaction { c -> c.execute("UPDATE auth_session SET revoked=true WHERE device=?", id(applicantDevice)) }
        applicant.resume(household)
        val afterRenewal = applicantState.read().households.getValue(household)
        assertThat(afterRenewal.epoch).isEqualTo(beforeRenewal.epoch + 2)
        assertThat(afterRenewal.session!!.token).isNotEqualTo(beforeRenewal.session!!.token)
        assertThat(sessionCount()).isEqualTo(sessionsBefore + 1)
        val a = ownerState.read().households.getValue(household)
        val b = applicantState.read().households.getValue(household)
        assertThat(a.hash).isEqualTo(b.hash)
        assertThat(a.keys.getValue("$list:${a.epoch}")).isEqualTo(b.keys.getValue("$list:${b.epoch}"))
        val memberSession = requireNotNull(b.session).token
        val snapshot = f.relay.snapshot(memberSession, list)
        val before = f.relay.membership(memberSession, household, snapshot.envelope.parsed().epoch - 2).manifests.single().signed().body()
        val signing = before.members.flatMap { it.devices }.first { it.id == snapshot.envelope.parsed().authorDeviceId }
        val clear = SyncCrypto.open(snapshot.envelope, Wire.decode(b.keys.getValue("$list:${b.epoch}"), 32), signing.signingPublicKey, 10 * 1024 * 1024)
        assertThat(SnapshotCodec.decode(clear).name).isEqualTo("synthetic-shared-snapshot")
        assertThat(applicant.checkpoint(household, list).seq).isEqualTo(3)
        val retainedBeforeTransfer = applicantState.read()
        appendSynthetic()
        val proposal = resumed.prepareTransfer(household, b.device.publicKeys.id)
        breakBridge = true
        try { applicant.reviewTransfer(proposal); error("A broken bridge must fail before ownership acceptance") } catch (_: IllegalArgumentException) { }
        breakBridge = false
        val review = applicant.reviewTransfer(proposal)
        val acceptance = applicant.acceptTransfer(review)
        expireCandidates = true
        resumed.completeTransfer(acceptance)
        val renewedProposal = requireNotNull(HouseholdClient(ownerState, transport, source) { now }.pendingTransfer(household))
        assertThat(renewedProposal).isNotEqualTo(proposal)
        resumed.completeTransfer(applicant.acceptTransfer(applicant.reviewTransfer(renewedProposal)))
        applicant.resume(household)
        assertThat(applicant.households().single().role).isEqualTo("owner")
        assertThat(resumed.households().single().role).isEqualTo("editor")
        val replacement = applicant.prepareReplacement(household)
        expireCandidates = true
        applicant.replaceRecovery(replacement.verifySaved(replacement.file, replacement.secret))
        val reinstalledState = MemorySyncStore()
        val reinstalled = HouseholdClient(reinstalledState, transport, source) { now }
        try { reinstalled.recover(prepared.file, prepared.secret); error("Replaced kit must fail") }
        catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("forbidden") }
        recoveryDevice = RecoveryKits.open(replacement.file, replacement.secret).recovery.publicKeys.id
        val retainedState = MemorySyncStore().also { it.update { retainedBeforeTransfer } }
        val retainedClient = HouseholdClient(retainedState, transport, source) { now }
        try { retainedClient.recover(replacement.file, replacement.secret); error("Restricted recovery cannot discard an unbridged known head") }
        catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("history_unverifiable") }
        assertThat(retainedState.read()).isEqualTo(retainedBeforeTransfer)
        interruptRecovery = true
        try { reinstalled.recover(replacement.file, replacement.secret); error("Recovery interrupted before commit") }
        catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("offline") }
        assertThat(reinstalledState.read().households.getValue(household).pending?.draft?.action).isEqualTo("recover")
        applicant.rotate(household)
        val restored = reinstalled.recover(replacement.file, replacement.secret)
        assertThat(restored).isEqualTo(household)
        assertThat(SnapshotCodec.decode(reinstalled.checkpoint(household, list).payload).name).isEqualTo("synthetic-shared-snapshot")
        val installed = reinstalledState.read().households.getValue(household)
        assertThat(installed.current.signed().body().members).hasSize(1)
        assertThat(installed.device.publicKeys).isNotEqualTo(b.device.publicKeys)
        assertThat(Wire.json.encodeToString(ClientState.serializer(), reinstalledState.read())).doesNotContain(RecoveryKits.open(replacement.file, replacement.secret).recovery.signingSeed)
        // A real encrypted shopping pump preserves source and ciphertext across ambiguous delivery.
        val shopping = MemoryReplica()
        f.db.transaction { c -> c.execute("UPDATE auth_session SET revoked=true WHERE device=?", id(installed.device.publicKeys.id)) }
        reinstalled.synchronize(household, list, shopping)
        fun capture(name: String): Intent = Intent(uuid(), list, "capture", kotlinx.serialization.json.buildJsonObject {
            put("proposedId", kotlinx.serialization.json.JsonPrimitive(uuid())); put("name", kotlinx.serialization.json.JsonPrimitive(name)); put("key", kotlinx.serialization.json.JsonPrimitive(name.lowercase()))
        })
        val milk = capture("Milk"); shopping.commands += milk
        blockAppend = true
        try { reinstalled.synchronize(household, list, shopping); error("Upload interrupted before commit") } catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("offline") }
        val ciphertext = shopping.envelopes.getValue(milk.id).copyOf()
        blockAppend = false
        var rebooted = HouseholdClient(reinstalledState, transport, source) { now }
        rebooted.synchronize(household, list, shopping)
        assertThat(appendBodies.takeLast(2)[0]).isEqualTo(appendBodies.last())
        assertThat(shopping.envelopes.getValue(milk.id)).isEqualTo(ciphertext)
        assertThat(shopping.pending(list)).isEmpty()
        val bread = capture("Bread"); shopping.commands += bread
        val beforeLoss = f.relay.operations(reinstalledState.read().households.getValue(household).session!!.token, list, 0).entries.size
        loseOperationAck = true
        try { rebooted.synchronize(household, list, shopping); error("Lost append acknowledgement") } catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("offline") }
        rebooted = HouseholdClient(reinstalledState, transport, source) { now }
        rebooted.synchronize(household, list, shopping)
        assertThat(shopping.pending(list)).isEmpty()
        assertThat(f.relay.operations(reinstalledState.read().households.getValue(household).session!!.token, list, 0).entries).hasSize(beforeLoss + 1)
        val apples = capture("Apples"); shopping.commands += apples
        truncateAfterAppend = true
        try { rebooted.synchronize(household, list, shopping); error("A signed acknowledgement cannot be forgotten by a stale read") } catch (_: IllegalArgumentException) { }
        val witness = reinstalledState.read().households.getValue(household).receiptPins.getValue(list)
        assertThat(witness).hasSize(1)
        rebooted = HouseholdClient(reinstalledState, transport, source) { now }
        try { rebooted.download(household, list); error("Restart must still reject a truncated log") } catch (_: IllegalArgumentException) { }
        assertThat(reinstalledState.read().households.getValue(household).receiptPins[list]).isEqualTo(witness)
        truncateOperations = false
        // Current snapshot can legitimately predate the acknowledgement. Its log must bridge it.
        rebooted.download(household, list)
        rebooted.synchronize(household, list, shopping)
        assertThat(shopping.pending(list)).isEmpty()
        assertThat(reinstalledState.read().households.getValue(household).receiptPins[list]).isNull()
        val rice = capture("Rice"); shopping.commands += rice
        blockAppend = true
        try { rebooted.synchronize(household, list, shopping); error("Offline old-epoch envelope") } catch (_: RelayProblem) { }
        val stale = shopping.envelopes.getValue(rice.id).copyOf(); blockAppend = false
        rebooted.rotate(household)
        rebooted.synchronize(household, list, shopping)
        assertThat(shopping.envelopes.getValue(rice.id)).isNotEqualTo(stale)
        assertThat(shopping.pending(list)).isEmpty()
        assertThat(SnapshotCodec.decode(shopping.saved!!.checkpoint.payload).rows.values.count { it.facts["name"]?.jsonPrimitive?.content == "Rice" }).isEqualTo(1)
        try { applicant.resume(household); error("Removed device must not refresh") } catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("forbidden") }
        try { resumed.resume(household); error("Removed device must not refresh") } catch (problem: RelayProblem) { assertThat(problem.code).isEqualTo("forbidden") }
    }

}

private class MemoryReplica : SharedReplica {
    var saved: ReplicaCheckpoint? = null
    val commands = mutableListOf<Intent>()
    val envelopes = mutableMapOf<String, ByteArray>()
    override suspend fun confirmed(list: String) = saved
    override suspend fun pending(list: String): List<PendingIntent> {
        val seen = saved?.checkpoint?.payload?.let { SnapshotCodec.decode(it).seen }.orEmpty()
        return commands.filter { it.id !in seen }.map { PendingIntent(it, envelopes[it.id]) }
    }
    override suspend fun saveEnvelope(list: String, intent: Intent, bytes: ByteArray) { require(intent in commands); envelopes[intent.id] = bytes.copyOf() }
    override suspend fun confirm(list: String, checkpoint: ListCheckpoint, epoch: Long, manifestHash: String) { saved = ReplicaCheckpoint(checkpoint, epoch, manifestHash) }
}

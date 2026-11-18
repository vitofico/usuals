package io.theficos.usuals.relay

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows

class RelayIntegrationTest {
    private val database = TestDatabase()
    private val db = database.db
    private var now = 1_800_000_000_000L
    private val identity = Identity.generate()
    private lateinit var f: RelayFixture
    @Before fun before() {
        db.migrate(); db.fence(identity.serverId, "original")
        f = RelayFixture(db, identity) { now }
    }
    @After fun after() = database.close()

    @Test fun normalStartupAndRestorePreflightRejectUnsupportedSchemaWithoutChangingEitherBoundary() {
        db.checkSchema()
        val directory = java.nio.file.Files.createTempDirectory("usuals-schema-fence")
        val file = directory.resolve("fence"); val original = uuid()
        java.nio.file.Files.writeString(file, original)
        try {
            db.transaction { c -> c.execute("INSERT INTO schema_migration SELECT max(version)+1,'future' FROM schema_migration") }
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
        } finally { java.nio.file.Files.deleteIfExists(file); java.nio.file.Files.delete(directory) }
    }
    @Test fun aVersionSevenDatabaseDropsTheHouseholdRelayAndKeepsAccounts() {
        TestDatabase().use { old ->
            old.pool.connection.use { c ->
                c.autoCommit = true
                c.execute("CREATE TABLE schema_migration (version integer PRIMARY KEY, checksum text NOT NULL)")
                for ((version, name) in listOf("001_relay", "002_web_identity", "003_local_identity", "004_web_household", "005_connection_ai", "006_pairing_v2", "007_connection_offer").withIndex()) {
                    val sql = checkNotNull(javaClass.getResourceAsStream("/db/$name.sql")).use { it.readBytes() }
                    c.createStatement().use { it.execute(sql.decodeToString()) }
                    c.execute("INSERT INTO schema_migration VALUES (?,?)", version + 1, Wire.hash(sql))
                }
                c.execute("INSERT INTO relay_meta VALUES (1,?,'original')", Wire.decode(identity.serverId, 32))
                c.execute("INSERT INTO household VALUES (?,?,1,?,?,false)", id(uuid()), ByteArray(32), ByteArray(32), ByteArray(1))
                c.execute("INSERT INTO web_user(id,issuer,subject,display_name,created_at,last_login_at) VALUES (?,?,'s','Kept',0,0)", id(uuid()), WebIdentityConfig.LOCAL_ISSUER)
            }
            old.db.migrate(); old.db.checkSchema(); old.db.fence(identity.serverId, "original")
            old.db.transaction { c ->
                assertThat(c.query("SELECT display_name FROM web_user") { it.getString(1) }).containsExactly("Kept")
                assertThat(c.query("SELECT to_regclass('household') IS NULL AND to_regclass('list') IS NOT NULL") { it.getBoolean(1) }.single()).isTrue()
            }
        }
    }
    @Test fun heldUploadBodiesCannotBlockCatchUp() = testApplication {
        val account = f.account("Synthetic")
        application { relayModule(f.relay, webIdentity = f.web) }
        coroutineScope {
            // One anonymous sender and two signed-in ones, as many as there are list-work permits.
            val held = List(3) { io.ktor.utils.io.ByteChannel() }
            val senders = held.mapIndexed { index, channel ->
                async {
                    client.put("/api/lists/${uuid()}") {
                        bearerAuth(if (index == 0) Wire.encode(ByteArray(32)) else account.bearer)
                        setBody(object : io.ktor.http.content.OutgoingContent.ReadChannelContent() {
                            override val contentType = ContentType.Application.Json
                            override fun readFrom(): io.ktor.utils.io.ByteReadChannel = channel
                        })
                    }
                }
            }
            try {
                delay(200)
                val response = withTimeout(2000) { client.get("/api/lists/changes") { bearerAuth(account.bearer) } }
                assertThat(response.status.value).isEqualTo(200)
            } finally { held.forEach { it.cancel(null) }; senders.forEach { it.cancelAndJoin() } }
        }
    }
    @Test fun operatorMetricsArePrivateAggregateAndReportErrorsWithoutRoutingLabels() = testApplication {
        val operations = RelayMetrics(db)
        val account = f.account("Synthetic")
        application { relayModule(f.relay, operations, webIdentity = f.web) }
        assertThat(client.get("/metrics").status.value).isEqualTo(404)
        assertThat(client.get("/api/lists/changes").status.value).isEqualTo(401)
        assertThat(client.get("/readyz").status.value).isEqualTo(200)
        val monitor = operations.listen("127.0.0.1", 0)
        try {
            val connection = java.net.URI("http://127.0.0.1:${monitor.address.port}/metrics").toURL().openConnection() as java.net.HttpURLConnection
            val metrics = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            assertThat(metrics).contains("usuals_requests_total 2")
            assertThat(metrics).contains("usuals_request_client_errors_total 1")
            assertThat(metrics).contains("usuals_requests_active 0")
            assertThat(metrics).contains("usuals_requests_waiting 0")
            assertThat(metrics).contains("usuals_storage_probe_ok 1")
            assertThat(metrics).doesNotContain(account.id); assertThat(metrics).doesNotContain(account.bearer)
        } finally { monitor.stop(0) }
    }
    @Test fun missingRestoreMetadataCannotAdoptExistingRowsAndMigrationChecksumIsEnforced() {
        f.account("Synthetic")
        db.transaction { it.execute("DELETE FROM relay_meta") }
        assertThrows(IllegalStateException::class.java) { db.fence(identity.serverId, uuid()) }
        assertThat(db.transaction { c -> c.query("SELECT count(*) FROM relay_meta") { it.getLong(1) }.single() }).isEqualTo(0)
        db.migrate()
        db.transaction { it.execute("UPDATE schema_migration SET checksum='modified'") }
        assertThrows(IllegalStateException::class.java) { db.migrate() }
    }
    @Test fun httpBoundsAndStrictParsing() = testApplication {
        val account = f.account("Synthetic")
        application { relayModule(f.relay, webIdentity = f.web) }
        assertThat(client.get("/healthz").status).isEqualTo(HttpStatusCode.OK)
        assertThat(client.get("/readyz").status).isEqualTo(HttpStatusCode.OK)
        suspend fun put(body: String) = client.put("/api/lists/${uuid()}") { bearerAuth(account.bearer); contentType(ContentType.Application.Json); setBody(body) }
        assertThat(put("""{"projection":{"name":"a","name":"b"}}""").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(put("""{"projection":{"name":"a"},"extra":1}""").status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(put(" ".repeat(8 * 1024 * 1024 + 1)).status).isEqualTo(HttpStatusCode.PayloadTooLarge)
        val compressed = client.put("/api/lists/${uuid()}") { bearerAuth(account.bearer); contentType(ContentType.Application.Json); header(HttpHeaders.ContentEncoding, "gzip"); setBody("{}") }
        assertThat(compressed.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(put("""{"projection":{"name":"a"}}""").status).isEqualTo(HttpStatusCode.Created)
    }
}

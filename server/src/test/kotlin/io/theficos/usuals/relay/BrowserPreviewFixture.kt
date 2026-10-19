package io.theficos.usuals.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.netty.Netty
import io.theficos.usuals.model.NORMALIZER_VERSION
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.sync.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID

/** Explicit disposable browser QA only. No test endpoint or keys enter the runtime distribution. */
object BrowserPreviewFixture {
    @JvmStatic fun main(args: Array<String>) {
        var output: Path? = null
        try {
            require(args.size == 2)
            val port = args[0].toInt().also { require(it in 1024..65535) }
            val directory = Path.of(args[1]).toAbsolutePath().normalize()
            output = directory
            requirePrivateDirectory(directory)
            runBlocking { preview(port, directory) }
        } catch (error: Exception) {
            output?.let { runCatching { writePrivate(it, "fixture-status.json", buildJsonObject { put("status", "failed"); put("code", errorCode(error)) }) } }
            System.err.println("browser_preview_failed")
            kotlin.system.exitProcess(1)
        }
    }
    private suspend fun preview(port: Int, directory: Path) {
        require(listOf("browser-setup.json", "ready.json", "stop", "approve.json", "native-command.json").none { Files.exists(directory.resolve(it), LinkOption.NOFOLLOW_LINKS) })
        val url = checkNotNull(System.getenv("USUALS_TEST_JDBC_URL")) { "missing_test_database" }
        require(!url.contains("currentSchema", ignoreCase = true))
        val user = checkNotNull(System.getenv("USUALS_TEST_DB_USER")) { "missing_test_database" }
        val password = checkNotNull(System.getenv("USUALS_TEST_DB_PASSWORD")) { "missing_test_database" }
        val schema = "browser_preview_" + fresh().replace("-", "")
        val origin = canonicalOrigin("http://localhost:$port", true)
        Database.pool(url, user, password).use { admin ->
            admin.connection.use { it.autoCommit = true; it.execute("CREATE SCHEMA $schema") }
            try {
                Database.pool(url + if ('?' in url) "&currentSchema=$schema" else "?currentSchema=$schema", user, password).use { pool ->
                    val db = Database(pool); val identity = Identity.generate()
                    db.migrate(); db.fence(identity.serverId, fresh())
                    val auth = Auth(db, identity, origin, localIdentity = true)
                    val web = WebIdentity(WebIdentityConfig(origin, null, true), db, auth)
                    val operatorFile = directory.resolve("operator-setup.json")
                    web.localOperator("syntheticpreview_admin", operatorFile, false)
                    val operator = readPrivate(operatorFile, 4096)
                    Files.delete(operatorFile)
                    val relay = Relay(db, identity, auth)
                    val server = embeddedServer(Netty, configure = {
                        connector { host = "0.0.0.0"; this.port = port }
                        connectionGroupSize = 1; workerGroupSize = 2; callGroupSize = 4
                    }) { relayModule(relay, webIdentity = web) }.start(wait = false)
                    try {
                        val store = MemorySyncStore()
                        val list = fresh()
                        val initial = initialProjection(list)
                        val replica = PreviewReplica()
                        val native = HouseholdClient(store, transport(origin, port), CheckpointSource { _, _ -> ListCheckpoint(0, Wire.zero, SnapshotCodec.encode(initial)) })
                        val setup = native.prepare(origin)
                        val household = native.bootstrap(setup.verifySaved(setup.file, setup.secret), auth.bootstrapToken())
                        native.createList(household, list)
                        native.synchronize(household, list, replica)
                        val invitation = native.invitation(household)
                        writePrivate(directory, "browser-setup.json", buildJsonObject {
                            put("origin", origin); put("username", "syntheticpreview_admin"); put("setupToken", operator.getValue("setupToken"))
                            put("expiresAt", operator.getValue("expiresAt")); put("invitationUri", invitation.uri()); put("householdId", household); put("listId", list)
                        })
                        writeState(directory, household, list, replica)
                        writePrivate(directory, "ready.json", buildJsonObject { put("status", "ready"); put("synthetic", true); put("origin", origin) })
                        loop(directory, native, household, list, replica)
                        writePrivate(directory, "fixture-status.json", buildJsonObject { put("status", "stopped") })
                    } finally { server.stop(1000, 3000) }
                }
            } finally { admin.connection.use { it.autoCommit = true; it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
    private suspend fun loop(directory: Path, native: HouseholdClient, household: String, list: String, replica: PreviewReplica) {
        val processed = mutableMapOf<String, Pair<JsonObject, JsonObject>>()
        while (!Files.exists(directory.resolve("stop"), LinkOption.NOFOLLOW_LINKS)) {
            try {
            native.synchronize(household, list, replica)
            writeState(directory, household, list, replica)
            val pendingRequests = native.pending(household)
            writePrivate(directory, "native-pending.json", buildJsonObject {
                put("requestIds", buildJsonArray { pendingRequests.forEach { add(it.requestId) } })
            })
            if (Files.exists(directory.resolve("approve.json"), LinkOption.NOFOLLOW_LINKS)) {
                val result = try {
                    val command = takeCommand(directory, "approve.json")
                    require(command.keys == setOf("requestId", "fingerprint"))
                    val request = command.getValue("requestId").jsonPrimitive.content.also { Wire.uuid(it) }
                    val fingerprint = command.getValue("fingerprint").jsonPrimitive.content.also { require(it.length <= 128) }
                    val pending = native.pending(household).single { it.requestId == request }
                    val verified = native.verifyApplicant(household, pending, fingerprint)
                    native.approve(household, verified)
                    buildJsonObject { put("status", "approved"); put("requestId", request) }
                } catch (error: Exception) { buildJsonObject { put("status", "rejected"); put("code", errorCode(error)) } }
                writePrivate(directory, "approval-result.json", result)
            }
            if (Files.exists(directory.resolve("native-command.json"), LinkOption.NOFOLLOW_LINKS)) {
                val result = try {
                    val command = takeCommand(directory, "native-command.json")
                    val id = command.getValue("id").jsonPrimitive.content.also { Wire.uuid(it) }
                    processed[id]?.let { prior -> require(prior.first == command); prior.second } ?: run {
                        require(processed.size < 100)
                        val intent = commandIntent(command, list, requireNotNull(replica.saved).checkpoint)
                        val projection = SnapshotCodec.decode(requireNotNull(replica.saved).checkpoint.payload)
                        require(Reducer(NORMALIZER_VERSION).apply(projection, intent).outcome in setOf(Outcome.APPLIED, Outcome.MERGED, Outcome.NO_OP))
                        replica.commands += intent
                        native.synchronize(household, list, replica)
                        val confirmed = SnapshotCodec.decode(requireNotNull(replica.saved).checkpoint.payload)
                        val code = when { id in confirmed.rejected -> "rejected"; id in confirmed.conflicts -> "conflict"; id in confirmed.seen -> "applied"; else -> "pending" }
                        buildJsonObject { put("id", id); put("status", code); put("seq", requireNotNull(replica.saved).checkpoint.seq) }.also { processed[id] = command to it }
                    }
                } catch (error: Exception) { buildJsonObject { put("status", "rejected"); put("code", errorCode(error)) } }
                writePrivate(directory, "native-command-result.json", result)
                writeState(directory, household, list, replica)
            }
            } catch (error: RelayProblem) {
                // The preview peer shares bounded relay admission with the browser under test.
                if (error.code !in setOf("unavailable", "offline", "rate_limited")) throw error
            }
            delay(1000)
        }
        requirePrivateFile(directory.resolve("stop"), 16)
    }
    private fun commandIntent(command: JsonObject, list: String, checkpoint: ListCheckpoint): Intent {
        val id = command.getValue("id").jsonPrimitive.content
        val kind = command.getValue("kind").jsonPrimitive.content
        val now = System.currentTimeMillis()
        val payload = when (kind) {
            "capture" -> {
                require(command.keys == setOf("id", "kind", "name"))
                val name = command.getValue("name").jsonPrimitive.content.also { require(it.isNotBlank() && it.length <= 100) }
                buildJsonObject { put("proposedId", id); put("name", name); put("key", Normalizer.normalize(name)); put("position", now.toDouble()); put("createdAt", now); put("updatedAt", now) }
            }
            "check" -> {
                require(command.keys == setOf("id", "kind", "itemId", "checked"))
                val item = command.getValue("itemId").jsonPrimitive.content.also { Wire.uuid(it) }
                val row = SnapshotCodec.decode(checkpoint.payload).rows.getValue(item)
                val checked = command.getValue("checked").jsonPrimitive.boolean
                buildJsonObject {
                    put("target", item); put("checked", checked); put("checkedAt", if (checked) JsonPrimitive(now) else JsonNull); put("updatedAt", now)
                    put("expected", buildJsonObject { put("check", row.revisions["check"]?.let(::JsonPrimitive) ?: JsonNull) })
                }
            }
            else -> error("invalid_command")
        }
        return Intent(id, list, kind, payload, normalizer = NORMALIZER_VERSION)
    }
    private fun initialProjection(list: String): Projection {
        var projection = Projection(name = "Usuals market")
        listOf("Milk", "Bread", "Lemons").forEachIndexed { index, name ->
            val command = Intent(fresh(), list, "capture", buildJsonObject {
                put("proposedId", fresh()); put("key", Normalizer.normalize(name)); put("name", name); put("position", index.toDouble())
                if (name == "Milk") { put("quantity", "2.0"); put("unit", "LITRE") }
            }, normalizer = NORMALIZER_VERSION)
            val reduced = Reducer(NORMALIZER_VERSION).apply(projection, command)
            require(reduced.outcome == Outcome.APPLIED); projection = reduced.projection
        }
        return projection
    }
    private fun writeState(directory: Path, household: String, list: String, replica: PreviewReplica) {
        val saved = requireNotNull(replica.saved)
        val projection = SnapshotCodec.decode(saved.checkpoint.payload)
        writePrivate(directory, "native-state.json", buildJsonObject {
            put("synthetic", true); put("householdId", household); put("listId", list); put("seq", saved.checkpoint.seq); put("epoch", saved.epoch); put("name", projection.name)
            put("rows", buildJsonArray { projection.rows.values.filter { !it.archived && it.id !in projection.tombstones }.sortedBy { it.id }.forEach { row ->
                add(buildJsonObject { put("id", row.id); put("facts", row.facts) })
            } })
            put("pending", replica.commands.count { it.id !in projection.seen })
        })
    }
    private fun transport(expectedOrigin: String, port: Int): RelayTransport {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()
        return object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse = withContext(Dispatchers.IO) {
                require(origin == expectedOrigin && path.startsWith("/v1/") && maxResponse in 1..10 * 1024 * 1024)
                val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(15))
                    .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
                if (body != null) request.header("Content-Type", "application/json")
                if (token != null) request.header("Authorization", "Bearer $token")
                val response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                val bytes = response.body().use { it.readNBytes(maxResponse + 1) }
                require(bytes.size <= maxResponse)
                WireResponse(response.statusCode(), bytes)
            }
        }
    }
    private fun takeCommand(directory: Path, name: String): JsonObject {
        val file = directory.resolve(name)
        val value = readPrivate(file, 4096)
        Files.delete(file)
        return value
    }
    private fun readPrivate(path: Path, max: Int): JsonObject {
        requirePrivateFile(path, max)
        return Wire.strictJson(Files.readAllBytes(path), max) as? JsonObject ?: error("invalid_command")
    }
    private fun writePrivate(directory: Path, name: String, value: JsonObject) {
        requirePrivateDirectory(directory)
        val target = directory.resolve(name)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requirePrivateFile(target, 10 * 1024 * 1024)
        val temporary = Files.createTempFile(directory, ".browser-preview-", ".json", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(value.toString().toByteArray())
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun requirePrivateDirectory(path: Path) {
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
        require(Files.getPosixFilePermissions(path).none { it in publicPermissions })
    }
    private fun requirePrivateFile(path: Path, max: Int) {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.size(path) <= max)
        require(Files.getPosixFilePermissions(path).none { it in publicPermissions })
    }
    private val publicPermissions = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
    private fun errorCode(error: Exception) = when (error) { is RelayProblem -> error.code.takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "failed"; is RelayError -> error.code; else -> "invalid" }
    private fun fresh() = UUID.randomUUID().toString()
    private class PreviewReplica : SharedReplica {
        var saved: ReplicaCheckpoint? = null
        val commands = mutableListOf<Intent>()
        private val envelopes = mutableMapOf<String, ByteArray>()
        override suspend fun confirmed(list: String) = saved
        override suspend fun pending(list: String): List<PendingIntent> {
            val seen = saved?.checkpoint?.payload?.let { SnapshotCodec.decode(it).seen }.orEmpty()
            return commands.filter { it.id !in seen }.map { PendingIntent(it, envelopes[it.id]) }
        }
        override suspend fun saveEnvelope(list: String, intent: Intent, bytes: ByteArray) { require(intent in commands); envelopes[intent.id] = bytes.copyOf() }
        override suspend fun confirm(list: String, checkpoint: ListCheckpoint, epoch: Long, manifestHash: String) { saved = ReplicaCheckpoint(checkpoint, epoch, manifestHash) }
    }
}

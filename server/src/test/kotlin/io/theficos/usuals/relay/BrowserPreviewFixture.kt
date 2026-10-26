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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Explicit disposable browser QA only. No test endpoint, password or key enters the runtime distribution. */
object BrowserPreviewFixture {
    @JvmStatic fun main(args: Array<String>) {
        var output: Path? = null
        try {
            require(args.size in 2..3)
            val port = args[0].toInt().also { require(it in 1024..65535) }
            val directory = Path.of(args[1]).toAbsolutePath().normalize()
            output = directory
            requirePrivateDirectory(directory)
            val models = args.getOrNull(2)?.let { origin ->
                require(URI(origin).host in setOf("localhost", "127.0.0.1"))
                BrowserModels(listOf(origin))
            } ?: BrowserModels()
            runBlocking { preview(port, directory, models) }
        } catch (error: Exception) {
            output?.let { runCatching { writePrivate(it, "fixture-status.json", buildJsonObject { put("status", "failed"); put("code", errorCode(error)) }) } }
            System.err.println("browser_preview_failed")
            kotlin.system.exitProcess(1)
        }
    }
    private suspend fun preview(port: Int, directory: Path, models: BrowserModels) {
        require(listOf("credentials.json", "ready.json", "stop").none { Files.exists(directory.resolve(it), LinkOption.NOFOLLOW_LINKS) })
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
                    val auth = Auth(db)
                    val web = WebIdentity(WebIdentityConfig(origin, null, true, accountLimits = AccountLimits.fromEnvironment(System.getenv())), db, auth)
                    // A throwaway list key that lives only in this process and dies with the schema.
                    val relay = Relay(db, identity, auth, ListCipher(com.google.crypto.tink.subtle.Random.randBytes(32)))
                    val accounts = seed(directory, origin, web, relay)
                    val server = embeddedServer(Netty, configure = {
                        connector { host = "0.0.0.0"; this.port = port }
                        connectionGroupSize = 1; workerGroupSize = 2; callGroupSize = 4
                    }) { relayModule(relay, webIdentity = web, browserModels = models, ai = AiGateway(db, AiConfiguration(models.origins.map { AiProvider("preview", "Preview", it.replace("://localhost:", "://host.docker.internal:").replace("://127.0.0.1:", "://host.docker.internal:") + "/v1", "scripted", isLocal = true) }, wrappingKey = ByteArray(32) { 7 })), accountPolicy = AccountPolicy.fromEnvironment(db, web.config.accountLimits), accountLifecycle = AccountLifecycle.fromEnvironment(relay, web)) }.start(wait = false)
                    try {
                        writePrivate(directory, "credentials.json", buildJsonObject { put("origin", origin); put("accounts", accounts) })
                        writePrivate(directory, "ready.json", buildJsonObject { put("status", "ready"); put("synthetic", true); put("origin", origin) })
                        while (!Files.exists(directory.resolve("stop"), LinkOption.NOFOLLOW_LINKS)) delay(1000)
                        requirePrivateFile(directory.resolve("stop"), 16)
                        writePrivate(directory, "fixture-status.json", buildJsonObject { put("status", "stopped") })
                    } finally { server.stop(1000, 3000) }
                }
            } finally { admin.connection.use { it.autoCommit = true; it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
    /** Two signed-up local accounts with generated passwords, and a list the first shares with the second. */
    private suspend fun seed(directory: Path, origin: String, web: WebIdentity, relay: Relay): JsonArray {
        suspend fun activate(username: String, setupToken: String, password: String): String {
            val challenge = web.localChallenge("preview")
            return web.localActivate(challenge.browserToken, origin, challenge.result.csrfToken, "preview") { LocalActivateRequest(username, setupToken, password) }.token
        }
        val operatorFile = directory.resolve("operator-setup.json")
        web.localOperator("preview_alice", operatorFile, false)
        val setup = readPrivate(operatorFile, 4096).getValue("setupToken").jsonPrimitive.content
        Files.delete(operatorFile)
        val alicePassword = randomToken(); val bobPassword = randomToken()
        val cookie = activate("preview_alice", setup, alicePassword)
        val alice = web.csrf(cookie, origin, web.me(cookie).csrfToken)
        val bob = web.localCreate(alice, LocalCreateRequest("preview_bob", "Bob Preview"))
        activate("preview_bob", bob.setupToken, bobPassword)
        val lists = Lists(relay, web.config); val access = AccountAccess(alice.user.id) { }
        val list = fresh()
        lists.create(access, list, CreateList(initialProjection(list)))
        lists.add(access, list, AddMember(bob.user.id))
        return buildJsonArray {
            add(buildJsonObject { put("username", "preview_alice"); put("password", alicePassword) })
            add(buildJsonObject { put("username", "preview_bob"); put("password", bobPassword) })
        }
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
    private fun errorCode(error: Exception) = if (error is RelayError) error.code else "invalid"
    private fun fresh() = UUID.randomUUID().toString()
}

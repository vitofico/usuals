package io.theficos.comanda.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.netty.Netty
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

fun main(args: Array<String>) {
    val identityPath = Path.of(required("COMANDA_IDENTITY_FILE"))
    val fencePath = Path.of(required("COMANDA_FENCE_FILE"))
    if (args.contentEquals(arrayOf("init-identity"))) {
        Identity.create(identityPath)
        Files.createFile(fencePath, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
        java.nio.channels.FileChannel.open(fencePath, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(UUID.randomUUID().toString().toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        java.nio.channels.FileChannel.open(fencePath.toAbsolutePath().parent, java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        return
    }
    val identity = Identity.load(identityPath)
    val restoring = args.contentEquals(arrayOf("restore-fence"))
    val fence = if (restoring) null else Files.readString(fencePath).trim().also { UUID.fromString(it) }
    val local = System.getenv("COMANDA_LOCAL_ONLY") == "true"
    val origin = canonicalOrigin(required("COMANDA_ORIGIN"), local)
    Database.pool(required("COMANDA_JDBC_URL"), required("COMANDA_DB_USER"), Files.readString(Path.of(required("COMANDA_DB_PASSWORD_FILE"))).trim()).use { pool ->
        val db = Database(pool)
        if (args.contentEquals(arrayOf("migrate"))) { db.migrate(); return }
        if (restoring) { RestoreFence.apply(db, identity.serverId, fencePath); return }
        db.checkSchema()
        db.fence(identity.serverId, requireNotNull(fence))
        if (args.contentEquals(arrayOf("bootstrap-token"))) {
            // Only this explicit operator command emits a bearer secret. Redirect to a protected file.
            print(Auth(db, identity, origin).bootstrapToken()); return
        }
        if (args.size == 3 && args[0] == "recovery-export") {
            FrozenExporter(db, identity, origin).export(Path.of(args[1]), Path.of(args[2])); return
        }
        val identityConfig = WebIdentityConfig.fromEnvironment(origin)
        val auth = Auth(db, identity, origin, webIssuer = identityConfig?.oidc?.issuer, localIdentity = identityConfig?.localEnabled == true)
        val webIdentity = identityConfig?.let { WebIdentity.create(it, db, auth) }
        if (args.size == 3 && args[0] in setOf("local-admin", "local-reset")) {
            requireNotNull(webIdentity).localOperator(args[1], Path.of(args[2]), args[0] == "local-reset"); return
        }
        require(args.isEmpty())
        val relay = Relay(db, identity, auth)
        val ingress = TrustedIngress(System.getenv("COMANDA_TRUSTED_PROXY_CIDRS")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty())
        val operations = RelayMetrics(db)
        val monitor = operations.listen(System.getenv("COMANDA_OPS_BIND") ?: "127.0.0.1", System.getenv("COMANDA_OPS_PORT")?.toInt() ?: 9090)
        Runtime.getRuntime().addShutdownHook(Thread { monitor.stop(0) })
        embeddedServer(Netty, configure = {
            connector { host = if (local) "127.0.0.1" else "0.0.0.0"; port = System.getenv("PORT")?.toInt() ?: 8080 }
            connectionGroupSize = 1; workerGroupSize = 2; callGroupSize = 4
        }) { relayModule(relay, operations, ingress, webIdentity) }.start(wait = true)
    }
}
private fun required(name: String) = requireNotNull(System.getenv(name)) { "missing_config:$name" }.also { require(it.isNotBlank()) }

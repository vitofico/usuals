package io.theficos.usuals.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.netty.Netty
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

fun main(args: Array<String>) {
    val identityPath = Path.of(required("USUALS_IDENTITY_FILE"))
    val fencePath = Path.of(required("USUALS_FENCE_FILE"))
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
    val local = System.getenv("USUALS_LOCAL_ONLY") == "true"
    val origin = canonicalOrigin(required("USUALS_ORIGIN"), local)
    Database.pool(required("USUALS_JDBC_URL"), required("USUALS_DB_USER"), Files.readString(Path.of(required("USUALS_DB_PASSWORD_FILE"))).trim()).use { pool ->
        val db = Database(pool)
        if (args.contentEquals(arrayOf("migrate"))) { db.migrate(); return }
        if (restoring) { RestoreFence.apply(db, identity.serverId, fencePath); return }
        db.checkSchema()
        db.fence(identity.serverId, requireNotNull(fence))
        if (args.size == 3 && args[0] == "ai-rewrap") {
            AiGateway(db, AiConfiguration(wrappingKey = AiConfiguration.readKey(Path.of(args[1])))).rewrap(AiConfiguration.readKey(Path.of(args[2]))); return
        }
        if (args.size == 3 && args[0] == "list-rewrap") {
            ListCipher(AiConfiguration.readKey(Path.of(args[1]))).rewrap(db, ListCipher(AiConfiguration.readKey(Path.of(args[2])))); return
        }
        val identityConfig = WebIdentityConfig.fromEnvironment(origin)
        val auth = Auth(db)
        if (args.size == 3 && args[0] in setOf("local-admin", "local-reset")) {
            WebIdentity.create(requireNotNull(identityConfig), db, auth).localOperator(args[1], Path.of(args[2]), args[0] == "local-reset"); return
        }
        require(args.isEmpty())
        // Lists are stored encrypted: the server does not start without the key that opens them.
        val cipher = ListCipher.fromEnvironment().also { it.check(db) }
        val relay = Relay(db, identity, auth, cipher)
        val invitations = MailConfiguration.fromEnvironment(origin)?.let { mail ->
            require(identityConfig?.oidc != null) { "email_invitations_require_oidc" }
            Invitations(relay, identityConfig, SmtpInvitationMailer(mail), mail)
        }
        require(identityConfig?.invitationOnly != true || invitations != null) { "invitation_admission_requires_email" }
        val webIdentity = identityConfig?.let { WebIdentity(it, db, auth, invitations = invitations) }
        invitations?.start()
        Runtime.getRuntime().addShutdownHook(Thread { invitations?.close() })
        val ingress = TrustedIngress(System.getenv("USUALS_TRUSTED_PROXY_CIDRS")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty())
        val operations = RelayMetrics(db)
        val browserModels = BrowserModels.fromEnvironment()
        val monitor = operations.listen(System.getenv("USUALS_OPS_BIND") ?: "127.0.0.1", System.getenv("USUALS_OPS_PORT")?.toInt() ?: 9090)
        Runtime.getRuntime().addShutdownHook(Thread { monitor.stop(0) })
        embeddedServer(Netty, configure = {
            connector { host = if (local) "127.0.0.1" else "0.0.0.0"; port = System.getenv("PORT")?.toInt() ?: 8080 }
            connectionGroupSize = 1; workerGroupSize = 2; callGroupSize = 4
        }) { relayModule(relay, operations, ingress, webIdentity, browserModels, AiGateway(db, AiConfiguration.fromEnvironment(), accountLimits = identityConfig?.accountLimits ?: AccountLimits()), identityConfig?.let { AccountPolicy.fromEnvironment(db, it.accountLimits) }, webIdentity?.let { AccountLifecycle.fromEnvironment(relay, it) }) }.start(wait = true)
    }
}
private fun required(name: String) = requireNotNull(System.getenv(name)) { "missing_config:$name" }.also { require(it.isNotBlank()) }

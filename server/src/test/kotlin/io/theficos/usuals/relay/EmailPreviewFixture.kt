package io.theficos.usuals.relay

import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.netty.Netty
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Local QA entry point, excluded from the production image. Uses the actual OIDC and SMTP implementations. */
object EmailPreviewFixture {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2)
        val port = args[0].toInt().also { require(it in 1024..65535) }
        val output = Path.of(args[1])
        require(Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(output))
        require(Files.getPosixFilePermissions(output) == PosixFilePermissions.fromString("rwx------"))
        val origin = canonicalOrigin("http://localhost:$port", true)
        TestDatabase().use { database ->
            val db = database.db; val identity = Identity.generate()
            db.migrate(); db.fence(identity.serverId, java.util.UUID.randomUUID().toString())
            val auth = Auth(db)
            val relay = Relay(db, identity, auth, ListCipher(com.google.crypto.tink.subtle.Random.randBytes(32)))
            val config = requireNotNull(WebIdentityConfig.fromEnvironment(origin))
            val mail = requireNotNull(MailConfiguration.fromEnvironment(origin))
            Invitations(relay, config, SmtpInvitationMailer(mail), mail).use { invitations ->
                val web = WebIdentity(config, db, auth, invitations = invitations)
                invitations.start()
                val server = embeddedServer(Netty, configure = {
                    connector { host = "0.0.0.0"; this.port = port }
                    connectionGroupSize = 1; workerGroupSize = 2; callGroupSize = 4
                }) { relayModule(relay, webIdentity = web) }.start(wait = false)
                try {
                    val ready = output.resolve("ready.json")
                    Files.createFile(ready, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    Files.writeString(ready, "{\"status\":\"ready\",\"synthetic\":true}", StandardOpenOption.WRITE)
                    while (!Files.exists(output.resolve("stop"))) delay(1000)
                } finally { server.stop(1000, 3000) }
            }
        }
    }
}

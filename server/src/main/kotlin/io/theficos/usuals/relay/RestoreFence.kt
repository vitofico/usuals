package io.theficos.usuals.relay

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Offline operator action. Traffic must be stopped before replacing this external file. */
object RestoreFence {
    fun apply(db: Database, serverId: String, path: Path) {
        db.checkSchema()
        db.readSnapshot { c ->
            val existing = c.query("SELECT server_id FROM relay_meta WHERE singleton=1") { io.theficos.usuals.sync.Wire.encode(it.getBytes(1)) }.singleOrNull()
            check(existing == serverId) { "identity_mismatch" }
        }
        // Preflight must succeed before changing either durable boundary. Traffic remains stopped.
        val fresh = replace(path)
        db.fence(serverId, fresh)
    }
    fun replace(path: Path): String {
        val target = path.toAbsolutePath()
        require(!Files.isSymbolicLink(target))
        val exists = Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (exists) { require(Files.isRegularFile(target)); UUID.fromString(Files.readString(target).trim()) }
        val temporary = Files.createTempFile(target.parent, ".usuals-fence-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        val fence = UUID.randomUUID().toString()
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val data = ByteBuffer.wrap(fence.toByteArray())
                while (data.hasRemaining()) channel.write(data)
                channel.force(true)
            }
            if (exists) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else Files.createLink(target, temporary)
            FileChannel.open(target.parent, StandardOpenOption.READ).use { it.force(true) }
            return fence
        } finally { Files.deleteIfExists(temporary) }
    }
}

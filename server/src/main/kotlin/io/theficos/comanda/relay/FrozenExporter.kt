package io.theficos.comanda.relay

import io.theficos.comanda.sync.*
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable data class ExportGrant(val version: Int, val origin: String, val serverId: String, val household: String,
    val list: String, val recipient: DeviceKeys, val verifiedFingerprint: String)

/** Local operator capability only. No HTTP route, bearer token or membership is issued. */
class FrozenExporter(private val db: Database, private val identity: Identity, private val origin: String) {
    fun export(grantFile: Path, output: Path) {
        val grant = Wire.json.decodeFromJsonElement<ExportGrant>(Wire.strictJson(protectedGrant(grantFile), 16384))
        require(grant.version == 1 && grant.origin == origin && grant.serverId == identity.serverId)
        id(grant.household); id(grant.list)
        val recipientFingerprint = Wire.fingerprint(identity.serverId, grant.household, grant.recipient.signingPublicKey, grant.recipient.recipientPublicKey)
        require(grant.verifiedFingerprint == Wire.decode(recipientFingerprint, 32).joinToString("") { "%02x".format(it.toInt() and 255) })
        require(!Files.exists(output))
        val directory = Files.createTempDirectory(output.toAbsolutePath().parent, ".comanda-export-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        try {
            val inventory = db.readSnapshot { c ->
                val household = c.query("SELECT frozen,epoch,manifest_hash,genesis FROM household WHERE id=?", id(grant.household)) {
                    listOf(it.getBoolean(1), it.getLong(2), Wire.encode(it.getBytes(3)), Wire.encode(it.getBytes(4)))
                }.single()
                require(household[0] == true)
                val fence = c.query("SELECT fence,server_id FROM relay_meta WHERE singleton=1") { it.getString(1) to Wire.encode(it.getBytes(2)) }.single()
                require(fence.second == identity.serverId)
                val epoch = household[1] as Long
                val records = mutableListOf<FrozenRecord>(); var total = 0L
                fun record(kind: String, bytes: ByteArray) {
                    total += bytes.size + 4L; require(total + FrozenArchive.MAX_HEADER <= FrozenArchive.MAX_BYTES && records.size < FrozenArchive.MAX_RECORDS)
                    val path = directory.resolve(records.size.toString())
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                    Files.write(path, bytes); records += FrozenArchive.record(kind, bytes)
                }
                var latest: ManifestProof? = null
                var verified: VerifiedMembership? = null
                c.prepareStatement("SELECT epoch,proof FROM manifest WHERE household=? ORDER BY epoch").use { query ->
                    query.setObject(1, id(grant.household)); query.fetchSize = 1
                    query.executeQuery().use { rows -> while (rows.next()) {
                        require(rows.getLong(1) == records.size + 1L)
                        val bytes = rows.getBytes(2); val proof = decode<ManifestProof>(bytes)
                        verified = verified?.advance(proof) ?: VerifiedMembership.genesis(proof, identity.info(), grant.household, household[3] as String)
                        latest = proof; record("manifest", bytes)
                    } }
                }
                val proof = requireNotNull(latest); val body = proof.signed().body()
                require(body.epoch == epoch && proof.signed().hash() == household[2])
                require(body.recovery == grant.recipient || body.members.any { it.role == "owner" && grant.recipient in it.devices })
                require(body.lists.any { it.listId == grant.list })
                val packageBytes = c.query("SELECT data FROM key_package WHERE household=? AND epoch=? AND list=? AND recipient=?",
                    id(grant.household), epoch, id(grant.list), raw(recipientFingerprint)) { it.getBytes(1) }.single()
                record("package", packageBytes)
                val snapshot = c.query("SELECT data,seq,head FROM published_snapshot WHERE list=? AND epoch=?", id(grant.list), epoch) {
                    Snapshot(decode<Envelope>(it.getBytes(1)), it.getLong(2), Wire.encode(it.getBytes(3)))
                }.single()
                record("snapshot", encode(snapshot))
                val head = c.query("SELECT last_seq,head FROM shared_list WHERE id=? AND household=?", id(grant.list), id(grant.household)) {
                    it.getLong(1) to Wire.encode(it.getBytes(2))
                }.single()
                var seq = snapshot.seq
                while (seq < head.first) {
                    val entries = c.query("SELECT data,receipt FROM operation WHERE list=? AND seq>? ORDER BY seq LIMIT 100", id(grant.list), seq) {
                        StoredOperation(decode<Envelope>(it.getBytes(1)), decode<Receipt>(it.getBytes(2)))
                    }
                    require(entries.isNotEmpty() && entries.first().receipt.seq == seq + 1 && entries.last().receipt.seq <= head.first)
                    seq = entries.last().receipt.seq; record("operations", encode(Operations(entries, seq, seq < head.first)))
                }
                FrozenInventory(1, origin, identity.info(), grant.household, household[3] as String, grant.list, grant.recipient,
                    recipientFingerprint, fence.first, epoch, proof.signed().hash(), snapshot.envelope.digest(), snapshot.seq, snapshot.head, head.first, head.second, records)
            }
            val staging = directory.resolve("complete")
            Files.createFile(staging, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            FileChannel.open(staging, StandardOpenOption.WRITE).use { channel ->
                FrozenArchive.write(Channels.newOutputStream(channel), inventory, identity::sign) { Files.newInputStream(directory.resolve(it.toString())) }
                channel.force(true)
            }
            // A same-filesystem hard link publishes complete bytes atomically and cannot replace an existing name.
            Files.createLink(output, staging)
            FileChannel.open(output.toAbsolutePath().parent, StandardOpenOption.READ).use { it.force(true) }
        } finally {
            Files.list(directory).use { paths -> paths.forEach(Files::deleteIfExists) }; Files.deleteIfExists(directory)
        }
    }
    private fun protectedGrant(path: Path): ByteArray {
        val target = path.toAbsolutePath().normalize()
        val noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
        val forbidden = setOf(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE, java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)
        fun protected(attributes: java.nio.file.attribute.PosixFileAttributes) = attributes.owner().name == System.getProperty("user.name") && attributes.permissions().none { it in forbidden }
        return Files.newDirectoryStream(target.parent).use { stream ->
            require(stream is java.nio.file.SecureDirectoryStream<Path>)
            val parent = stream.getFileAttributeView(java.nio.file.attribute.PosixFileAttributeView::class.java).readAttributes()
            require(parent.isDirectory && protected(parent))
            val view = stream.getFileAttributeView(target.fileName, java.nio.file.attribute.PosixFileAttributeView::class.java, noFollow)
            val before = view.readAttributes()
            require(before.isRegularFile && protected(before) && before.size() in 1..16384)
            val bytes = stream.newByteChannel(target.fileName, setOf(StandardOpenOption.READ, noFollow)).use { channel ->
                require(channel.size() == before.size())
                val buffer = java.nio.ByteBuffer.allocate(before.size().toInt())
                while (buffer.hasRemaining()) require(channel.read(buffer) > 0)
                require(channel.read(java.nio.ByteBuffer.allocate(1)) == -1)
                buffer.array()
            }
            val after = view.readAttributes()
            require(after.fileKey() == before.fileKey() && after.size() == before.size() && after.lastModifiedTime() == before.lastModifiedTime() && after.owner() == before.owner() && after.permissions() == before.permissions())
            bytes
        }
    }
}

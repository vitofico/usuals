package io.theficos.usuals.sync

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable data class FrozenRecord(val kind: String, val size: Int, val hash: String)
@Serializable data class FrozenInventory(val version: Int, val origin: String, val server: ServerInfo,
    val household: String, val genesis: String, val list: String, val recipient: DeviceKeys,
    val recipientFingerprint: String, val fence: String, val epoch: Long, val manifestHash: String, val snapshotDigest: String,
    val snapshotSeq: Long, val snapshotHead: String, val terminalSeq: Long, val terminalHead: String,
    val records: List<FrozenRecord>)
@Serializable data class SignedFrozenInventory(val inventory: String, val signature: String)
class FrozenTrust(val origin: String, val server: ServerInfo, val household: String, val genesis: String,
    val recipient: DeviceSecrets, val memberships: Map<Long, String>, val heads: Map<Long, String>, val ancestryUnavailable: Boolean = false) {
    override fun toString() = "FrozenTrust([redacted])"
}
class FrozenCopy internal constructor(val name: String, val items: Int, val purchases: Int, val older: Boolean,
    val ancestryUnverifiable: Boolean, private val encoded: ByteArray) {
    fun payload(): ByteArray = encoded.copyOf()
    override fun toString() = "FrozenCopy([redacted])"
}

/** Offline, signed ciphertext inventory. It never restores authority or changes private pins. */
object FrozenArchive {
    const val MAX_BYTES = 128L * 1024 * 1024
    const val MAX_RECORDS = 4096
    const val MAX_HEADER = 1024 * 1024
    private val magic = "USUALS-FROZEN\u0000\u0001".toByteArray(Charsets.US_ASCII)
    fun signingBytes(bytes: ByteArray) = Wire.domain("FROZEN-EXPORT", bytes)
    fun record(kind: String, bytes: ByteArray) = FrozenRecord(kind, bytes.size, Wire.hash(bytes)).also { validateRecord(it) }
    fun write(output: OutputStream, inventory: FrozenInventory, sign: (ByteArray) -> String, record: (Int) -> InputStream) {
        validate(inventory)
        val exact = Wire.json.encodeToString(inventory).toByteArray()
        val signed = SignedFrozenInventory(Wire.encode(exact), sign(signingBytes(exact)))
        val header = Wire.json.encodeToString(signed).toByteArray()
        require(header.size <= MAX_HEADER)
        val stream = DataOutputStream(output)
        stream.write(magic); stream.writeInt(header.size); stream.write(header)
        inventory.records.forEachIndexed { index, expected ->
            record(index).use { source ->
                val bytes = readExact(source, expected.size)
                require(source.read() == -1 && Wire.hash(bytes) == expected.hash)
                stream.writeInt(bytes.size); stream.write(bytes)
            }
        }
        stream.flush()
    }
    fun read(input: InputStream, trust: (FrozenInventory) -> FrozenTrust): FrozenCopy {
        val stream = DataInputStream(input)
        require(readExact(stream, magic.size).contentEquals(magic))
        val headerSize = stream.readInt(); require(headerSize in 1..MAX_HEADER)
        val signed = parse<SignedFrozenInventory>(readExact(stream, headerSize), MAX_HEADER)
        val exact = Wire.decode(signed.inventory)
        val inventory = parse<FrozenInventory>(exact, MAX_HEADER)
        validate(inventory)
        val anchor = trust(inventory)
        require(anchor.origin == inventory.origin && anchor.server == inventory.server && anchor.household == inventory.household && anchor.genesis == inventory.genesis && anchor.recipient.publicKeys == inventory.recipient)
        require(Wire.hash(Wire.decode(anchor.server.receiptPublicKey, 32)) == anchor.server.serverId)
        SyncCrypto.verify(anchor.server.receiptPublicKey, signed.signature, signingBytes(exact))
        var bytesRead = magic.size.toLong() + 4 + headerSize
        var verified: VerifiedMembership? = null
        var previous: Manifest? = null
        var manifestCount = 0
        var packageSeen = false
        var snapshotSeen = false
        var key: ByteArray? = null
        var projection: Projection? = null
        var seq = inventory.snapshotSeq
        var head = inventory.snapshotHead
        val witnessed = mutableSetOf<Long>()
        fun witness(sequence: Long, hash: String, bridged: Boolean = true) {
            anchor.heads[sequence]?.let { require(it == hash); if (bridged) witnessed += sequence }
        }
        val reducer = Reducer(io.theficos.usuals.model.NORMALIZER_VERSION)
        for (expected in inventory.records) {
            val size = stream.readInt(); require(size == expected.size)
            bytesRead += 4L + size; require(bytesRead <= MAX_BYTES)
            val bytes = readExact(stream, size); require(bytes.size == size && Wire.hash(bytes) == expected.hash)
            when (expected.kind) {
                "manifest" -> {
                    require(!packageSeen && !snapshotSeen)
                    val proof = parse<ManifestProof>(bytes, 8 * 1024 * 1024)
                    previous = verified?.manifest
                    verified = verified?.advance(proof) ?: VerifiedMembership.genesis(proof, anchor.server, anchor.household, anchor.genesis)
                    manifestCount++
                    require(verified.manifest.epoch == manifestCount.toLong())
                    anchor.memberships[verified.manifest.epoch]?.let { require(it == verified.hash) }
                    verified.manifest.lists.firstOrNull { it.listId == inventory.list }?.let { witness(it.seq, it.head, bridged = false) }
                }
                "package" -> {
                    val current = requireNotNull(verified); val m = current.manifest
                    require(!packageSeen && !snapshotSeen && m.epoch == inventory.epoch && current.hash == inventory.manifestHash)
                    require(m.recovery == inventory.recipient || m.members.any { it.role == "owner" && inventory.recipient in it.devices })
                    val p = parse<KeyPackage>(bytes, 16384)
                    require(p.listId == inventory.list && p.epoch == m.epoch && p.predecessorHash == m.previousHash && p.hash(m.serverId, m.householdId) in m.packageHashes)
                    val signer = transitionSigner(m, requireNotNull(previous))
                    key = SyncCrypto.unwrap(m.serverId, m.householdId, p, anchor.recipient, signer.signingPublicKey)
                    packageSeen = true
                }
                "snapshot" -> {
                    require(packageSeen && !snapshotSeen)
                    val current = requireNotNull(verified); val m = current.manifest
                    val snapshot = parse<Snapshot>(bytes, 10 * 1024 * 1024); val h = snapshot.envelope.parsed()
                    require(snapshot.seq == inventory.snapshotSeq && snapshot.head == inventory.snapshotHead && snapshot.envelope.digest() == inventory.snapshotDigest)
                    require(h.serverId == m.serverId && h.householdId == m.householdId && h.listId == inventory.list && h.epoch == m.epoch && h.baseSeq == snapshot.seq && h.baseHead == snapshot.head)
                    val cutoff = m.lists.single { it.listId == inventory.list }; require(snapshot.seq >= cutoff.seq)
                    val signer = if (h.manifestHash == current.hash) m.members.firstOrNull { it.role == "owner" && it.devices.any { d -> d.id == h.authorDeviceId } }?.devices?.first { it.id == h.authorDeviceId }
                    else {
                        require(h.manifestHash == m.previousHash && h.authorDeviceId == m.signer && snapshot.seq == cutoff.seq && snapshot.head == cutoff.head && snapshot.envelope.digest() == cutoff.snapshotHash)
                        transitionSigner(m, requireNotNull(previous)).takeIf { it.id == h.authorDeviceId }
                    }
                    projection = SnapshotCodec.decode(SyncCrypto.open(snapshot.envelope, requireNotNull(key), requireNotNull(signer).signingPublicKey, 10 * 1024 * 1024))
                    witness(seq, head); snapshotSeen = true
                }
                "operations" -> {
                    require(snapshotSeen)
                    val current = requireNotNull(verified); val m = current.manifest
                    val page = parse<Operations>(bytes, 10 * 1024 * 1024); require(page.entries.size in 1..100)
                    for (entry in page.entries) {
                        val h = entry.envelope.parsed()
                        require(h.serverId == m.serverId && h.householdId == m.householdId && h.listId == inventory.list && h.epoch == m.epoch && h.manifestHash == current.hash)
                        val author = m.members.flatMap { it.devices }.single { it.id == h.authorDeviceId }
                        SyncCrypto.verifyReceipt(entry.envelope, entry.receipt, anchor.server.receiptPublicKey, seq + 1, head)
                        val intent = parse<Intent>(SyncCrypto.open(entry.envelope, requireNotNull(key), author.signingPublicKey), 65536, payload = true)
                        require(intent.id == h.intentId && intent.listId == inventory.list)
                        val reduced = reducer.apply(requireNotNull(projection), intent)
                        if (reduced.outcome == Outcome.UPGRADE_REQUIRED) throw UnsupportedWireVersion()
                        projection = reduced.projection; seq = entry.receipt.seq; head = entry.receipt.head; witness(seq, head)
                    }
                    require(page.nextCursor == seq && page.hasMore == (seq < inventory.terminalSeq))
                }
                else -> throw UnsupportedWireVersion()
            }
        }
        require(packageSeen && snapshotSeen && seq == inventory.terminalSeq && head == inventory.terminalHead && stream.read() == -1)
        val state = requireNotNull(projection)
        val highestEpoch = anchor.memberships.keys.maxOrNull() ?: 1
        val older = inventory.epoch < highestEpoch || seq < (anchor.heads.keys.maxOrNull() ?: 0)
        val unknown = anchor.ancestryUnavailable || witnessed.size < anchor.heads.size || inventory.epoch < highestEpoch && inventory.epoch !in anchor.memberships
        // Encoding applies the same bounded structural validation as ordinary snapshots before preview/save.
        return FrozenCopy(state.name, state.rows.values.count { !it.archived && it.id !in state.tombstones }, state.purchases.size, older, unknown, SnapshotCodec.encode(state))
    }
    private fun transitionSigner(m: Manifest, previous: Manifest): DeviceKeys =
        if (m.action == "recover" && m.signer == previous.recovery.id) previous.recovery
        else requireNotNull(previous.members.firstOrNull { it.role == "owner" && it.devices.any { d -> d.id == m.signer } }?.devices?.first { it.id == m.signer })
    private fun validate(value: FrozenInventory) {
        if (value.version != 1) throw UnsupportedWireVersion()
        require(value.records.size in 3..MAX_RECORDS && value.origin.length in 1..512 && value.epoch > 1 && value.snapshotSeq >= 0 && value.terminalSeq >= value.snapshotSeq)
        Wire.uuid(value.household); Wire.uuid(value.list); Wire.uuid(value.recipient.id); Wire.uuid(value.fence)
        for (hash in listOf(value.genesis, value.manifestHash, value.snapshotDigest, value.snapshotHead, value.terminalHead)) Wire.decode(hash, 32)
        require(value.records.sumOf { it.size.toLong() + 4 } + MAX_HEADER + magic.size + 4 <= MAX_BYTES)
        require(value.recipientFingerprint == Wire.fingerprint(value.server.serverId, value.household, value.recipient.signingPublicKey, value.recipient.recipientPublicKey))
        value.records.forEach(::validateRecord)
    }
    private fun validateRecord(value: FrozenRecord) {
        val max = when (value.kind) { "manifest" -> 8 * 1024 * 1024; "package" -> 16384; "snapshot", "operations" -> 10 * 1024 * 1024; else -> throw UnsupportedWireVersion() }
        require(value.size in 1..max); Wire.decode(value.hash, 32)
    }
    private fun readExact(input: InputStream, size: Int): ByteArray = ByteArray(size).also { bytes ->
        DataInputStream(input).readFully(bytes)
    }
    private inline fun <reified T> parse(bytes: ByteArray, max: Int, payload: Boolean = false): T = Wire.json.decodeFromJsonElement(Wire.strictJson(bytes, max, payload))
}

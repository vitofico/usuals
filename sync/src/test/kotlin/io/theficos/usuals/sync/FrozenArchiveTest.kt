package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class FrozenArchiveTest {
    private class Fixture {
        val receipt = SyncCrypto.device()
        val server = ServerInfo(Wire.hash(Wire.decode(receipt.publicKeys.signingPublicKey)), receipt.publicKeys.signingPublicKey)
        val setup = RecoveryKits.prepare("https://relay.example", server)
        val kit = RecoveryKits.open(setup.file, setup.secret)
        val household = setup.genesis.body().householdId
        val list = UUID.randomUUID().toString()
        val key = SyncCrypto.randomKey()
        val initial = Projection(name = "Recovered Home")
        val snapshotEnvelope = SyncCrypto.seal(Header(server.serverId, household, list, setup.device.publicKeys.id,
            uuid(), uuid(), 2, setup.genesis.hash(), 0, Wire.zero), SnapshotCodec.encode(initial), key, setup.device, 7 * 1024 * 1024) {}
        val packages = listOf(setup.device.publicKeys, kit.recovery.publicKeys).map { SyncCrypto.wrap(server.serverId, household, list, 2, setup.genesis.hash(), it, key, setup.device) }
        val candidate = uuid()
        val body = setup.genesis.body().copy(epoch = 2, genesisHash = setup.genesis.hash(), previousHash = setup.genesis.hash(), action = "create_list",
            lists = listOf(ListCutoff(list, 0, Wire.zero, candidate, snapshotEnvelope.digest())),
            packageHashes = packages.map { it.hash(server.serverId, household) }, createdListId = list)
        val draft = SignedManifest(Wire.encode(Wire.json.encodeToString(body).toByteArray()), Wire.encode(ByteArray(64)), packages,
            listOf(SnapshotRef(candidate, snapshotEnvelope.digest(), list, 0, Wire.zero)))
        val manifest = draft.copy(signature = SyncCrypto.sign(setup.device, draft.signingBytes()))
        val intent = Intent(uuid(), list, "capture", buildJsonObject { put("proposedId", uuid()); put("name", "Milk"); put("key", "milk") })
        val operation = SyncCrypto.seal(Header(server.serverId, household, list, setup.device.publicKeys.id, intent.id, uuid(), 2, manifest.hash(), 0, Wire.zero),
            Wire.json.encodeToString(intent).toByteArray(), key, setup.device) {}
        val head = Wire.head(Wire.zero, 1, operation)
        val stored = StoredOperation(operation, Receipt(1, Wire.zero, head, SyncCrypto.sign(receipt, Wire.receipt(server.serverId, household, list, 1, head))))
        val records = listOf("manifest" to encode(ManifestProof(setup.genesis.manifestBytes, setup.genesis.signature)),
            "manifest" to encode(ManifestProof(manifest.manifestBytes, manifest.signature)), "package" to encode(packages.last()),
            "snapshot" to encode(Snapshot(snapshotEnvelope, 0, Wire.zero)), "operations" to encode(Operations(listOf(stored), 1, false)))
        val inventory = FrozenInventory(1, setup.origin, server, household, setup.genesis.hash(), list, kit.recovery.publicKeys, Wire.fingerprint(server.serverId, household, kit.recovery.publicKeys.signingPublicKey, kit.recovery.publicKeys.recipientPublicKey), uuid(), 2,
            manifest.hash(), snapshotEnvelope.digest(), 0, Wire.zero, 1, head, records.map { FrozenArchive.record(it.first, it.second) })
        val trust = FrozenTrust(setup.origin, server, household, setup.genesis.hash(), kit.recovery, mapOf(1L to setup.genesis.hash()), emptyMap())
        fun bytes(inventory: FrozenInventory = this.inventory, records: List<Pair<String,ByteArray>> = this.records) = ByteArrayOutputStream().also { out ->
            FrozenArchive.write(out, inventory, { SyncCrypto.sign(receipt, it) }) { ByteArrayInputStream(records[it].second) }
        }.toByteArray()
        fun read(bytes: ByteArray = bytes(), trust: FrozenTrust = this.trust) = FrozenArchive.read(ByteArrayInputStream(bytes)) { trust }
        fun trust(memberships: Map<Long,String> = trust.memberships, heads: Map<Long,String> = emptyMap(), recipient: DeviceSecrets = kit.recovery) =
            FrozenTrust(setup.origin, server, household, setup.genesis.hash(), recipient, memberships, heads)
    }
    @Test fun completeInventoryDecryptsAndOnlyExposesAnImmutableIndependentCopy() {
        val f = Fixture(); val copy = f.read()
        assertThat(copy.name).isEqualTo("Recovered Home"); assertThat(copy.items).isEqualTo(1); assertThat(copy.purchases).isEqualTo(0)
        assertThat(copy.older).isFalse(); assertThat(copy.ancestryUnverifiable).isFalse()
        copy.payload().fill(0)
        assertThat(SnapshotCodec.decode(copy.payload()).rows.values.single().facts["name"].toString()).isEqualTo("\"Milk\"")
        assertThat(copy.toString()).isEqualTo("FrozenCopy([redacted])")
    }
    @Test fun suffixTruncationTrailingBytesAndChangedFramesFailBeforeReturningAnyCopy() {
        val f = Fixture(); val bytes = f.bytes()
        for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf(bytes.size / 2), bytes + byteArrayOf(0), bytes.copyOf().also { it[it.lastIndex - 10] = (it[it.lastIndex - 10].toInt() xor 1).toByte() }))
            assertThat(runCatching { f.read(bad) }.isFailure).isTrue()
    }
    @Test fun wrongRecipientOverlappingMembershipForkAndReceiptWitnessForkFailClosed() {
        val f = Fixture(); val wrong = Wire.hash(byteArrayOf(1))
        for (trust in listOf(f.trust(recipient = SyncCrypto.device()), f.trust(memberships = mapOf(1L to f.setup.genesis.hash(), 2L to wrong)), f.trust(heads = mapOf(1L to wrong))))
            assertThat(runCatching { f.read(trust = trust) }.isFailure).isTrue()
    }
    @Test fun anOlderOrUnbridgedCopyIsExplicitAndCannotPretendToExtendKnownState() {
        val f = Fixture()
        val older = f.read(trust = f.trust(memberships = mapOf(1L to f.setup.genesis.hash(), 2L to f.manifest.hash(), 3L to Wire.hash(byteArrayOf(3))), heads = mapOf(2L to Wire.hash(byteArrayOf(2)))))
        assertThat(older.older).isTrue(); assertThat(older.ancestryUnverifiable).isTrue()
        val current = f.read(trust = f.trust(memberships = mapOf(2L to f.manifest.hash()), heads = mapOf(0L to Wire.zero, 1L to f.head)))
        assertThat(current.older).isFalse(); assertThat(current.ancestryUnverifiable).isFalse()
    }
    @Test fun aSignedInventoryCannotAuthorizeInvalidRecordOrderOrAFalseTerminalHead() {
        val f = Fixture()
        val reordered = f.records.toMutableList().also { val value = it.removeAt(2); it.add(value) }
        val inventory = f.inventory.copy(records = reordered.map { FrozenArchive.record(it.first, it.second) })
        assertThat(runCatching { f.read(f.bytes(inventory, reordered)) }.isFailure).isTrue()
        assertThat(runCatching { f.read(f.bytes(f.inventory.copy(terminalHead = Wire.zero))) }.isFailure).isTrue()
    }
    @Test fun aHistoricalCutoffIsNotAReceiptBridgeToALaterSnapshotAndTombstonesAreExcluded() {
        val f = Fixture()
        val state = SnapshotCodec.decode(f.read().payload())
        val removed = state.copy(tombstones = mapOf(state.rows.keys.single() to "deleted"))
        val laterHead = Wire.hash(byteArrayOf(5))
        val envelope = SyncCrypto.seal(Header(f.server.serverId, f.household, f.list, f.setup.device.publicKeys.id,
            uuid(), uuid(), 2, f.manifest.hash(), 5, laterHead), SnapshotCodec.encode(removed), f.key, f.setup.device, 7 * 1024 * 1024) {}
        val records = f.records.take(3) + ("snapshot" to encode(Snapshot(envelope, 5, laterHead)))
        val inventory = f.inventory.copy(snapshotSeq = 5, snapshotHead = laterHead, snapshotDigest = envelope.digest(),
            terminalSeq = 5, terminalHead = laterHead, records = records.map { FrozenArchive.record(it.first, it.second) })
        val copy = f.read(f.bytes(inventory, records), f.trust(heads = mapOf(0L to Wire.zero)))
        assertThat(copy.ancestryUnverifiable).isTrue()
        assertThat(copy.items).isEqualTo(0)
        assertThat(runCatching { f.read(f.bytes(inventory, records), f.trust(heads = mapOf(0L to Wire.hash(byteArrayOf(9))))) }.isFailure).isTrue()
    }
    @Test fun aVerifiedKitCanPreviewWithAnUnreadableVaultWithoutAnyWritesOrNetwork() = kotlinx.coroutines.runBlocking {
        val f = Fixture(); var writes = 0
        val request = RecoveryKits.frozenRequest(f.setup.file, f.setup.secret)
        assertThat(request.household).isEqualTo(f.household)
        assertThat(request.recipient).isEqualTo(f.kit.recovery.publicKeys)
        val publicJson = Wire.json.encodeToString(request)
        assertThat(publicJson).doesNotContain(f.kit.recovery.signingSeed)
        assertThat(publicJson).doesNotContain(f.kit.recovery.recipientSecret)
        assertThat(publicJson).doesNotContain(f.setup.secret)
        val store = object : SyncStateStore {
            override fun read(): ClientState = throw IllegalStateException("unreadable-vault")
            override fun update(change: (ClientState) -> ClientState): ClientState { writes++; error("forbidden") }
            override fun reserve(scope: String) { writes++; error("forbidden") }
            override fun saveBlob(id: String, bytes: ByteArray) { writes++; error("forbidden") }
            override fun readBlob(id: String): ByteArray? = error("unused")
            override fun removeBlob(id: String) { writes++; error("forbidden") }
        }
        val transport = object : RelayTransport {
            override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse = error("offline only")
        }
        val client = HouseholdClient(store, transport, CheckpointSource { _, _ -> error("unused") })
        val copy = client.previewFrozen(ByteArrayInputStream(f.bytes()), f.setup.file, f.setup.secret)
        assertThat(copy.items).isEqualTo(1); assertThat(copy.ancestryUnverifiable).isTrue(); assertThat(writes).isEqualTo(0)
        assertThat(runCatching { client.previewFrozen(ByteArrayInputStream(f.bytes())) }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(runCatching { client.previewFrozen(ByteArrayInputStream(f.bytes()), f.setup.file, "wrong") }.isFailure).isTrue()
        assertThat(writes).isEqualTo(0)
    }
    @Test fun framingLengthAndUnknownVersionAreRejectedWithoutUnboundedAllocation() {
        val f = Fixture(); val bytes = f.bytes(); val magicSize = "USUALS-FROZEN\u0000\u0001".toByteArray().size
        val huge = bytes.copyOf(); java.nio.ByteBuffer.wrap(huge, magicSize, 4).putInt(Int.MAX_VALUE)
        assertThat(runCatching { f.read(huge) }.isFailure).isTrue()
        assertThat(runCatching { f.bytes(f.inventory.copy(version = 2)) }.exceptionOrNull()).isInstanceOf(UnsupportedWireVersion::class.java)
        assertThat(runCatching { FrozenArchive.record("snapshot", ByteArray(10 * 1024 * 1024 + 1)) }.isFailure).isTrue()
    }
    companion object {
        private fun uuid() = UUID.randomUUID().toString()
        private inline fun <reified T> encode(value: T) = Wire.json.encodeToString(value).toByteArray()
    }
}

package io.theficos.usuals.sync

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
class RecoveryKitData(val version: Int = 1, val origin: String, val server: ServerInfo, val household: String,
    val genesis: ManifestProof, val recovery: DeviceSecrets, val anchor: List<ManifestProof> = emptyList(),
    val replacementSigner: String? = null, val replacementSignature: String? = null) {
    override fun toString() = "RecoveryKitData([redacted])"
}
@Serializable private data class KitFile(val version: Int = 1, val ciphertext: String)

interface RecoveryDraft { val file: ByteArray; val secret: String }

/** Keep only in memory until a saved file and separate secret have been verified. */
class NewHousehold internal constructor(val origin: String, val server: ServerInfo, val genesis: SignedManifest,
    internal val device: DeviceSecrets, override val file: ByteArray, override val secret: String) : RecoveryDraft {
    fun verifySaved(savedFile: ByteArray, savedSecret: String): VerifiedSetup {
        val restored = RecoveryKits.open(savedFile, savedSecret)
        require(restored.origin == origin && restored.server == server && restored.household == genesis.body().householdId &&
            restored.genesis.signed().hash() == genesis.hash() && restored.recovery.publicKeys == genesis.body().recovery)
        // Private recovery material is not retained by the verified setup or phone state.
        return VerifiedSetup(origin, server, genesis, device)
    }
    override fun toString() = "NewHousehold([redacted])"
}
class VerifiedSetup internal constructor(val origin: String, val server: ServerInfo, val genesis: SignedManifest, internal val device: DeviceSecrets) {
    override fun toString() = "VerifiedSetup([redacted])"
}
@Serializable data class FrozenRequest(val version: Int, val origin: String, val serverId: String,
    val household: String, val recipient: DeviceKeys, val fingerprint: String)

object RecoveryKits {
    fun frozenRequest(file: ByteArray, secret: String): FrozenRequest {
        val kit = open(file, secret)
        val keys = kit.recovery.publicKeys
        val fingerprint = Wire.decode(Wire.fingerprint(kit.server.serverId, kit.household, keys.signingPublicKey, keys.recipientPublicKey), 32)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return FrozenRequest(1, kit.origin, kit.server.serverId, kit.household, keys, fingerprint)
    }
    fun prepare(origin: String, server: ServerInfo): NewHousehold {
        require(Wire.hash(Wire.decode(server.receiptPublicKey, 32)) == server.serverId && 1 in server.protocolVersions)
        val owner = SyncCrypto.device(); val recovery = SyncCrypto.device()
        val body = Manifest(serverId = server.serverId, householdId = UUID.randomUUID().toString(), genesisHash = Wire.zero,
            epoch = 1, previousHash = Wire.zero, action = "genesis", signer = owner.publicKeys.id,
            members = listOf(RosterMember(UUID.randomUUID().toString(), "owner", listOf(owner.publicKeys))), recovery = recovery.publicKeys)
        val draft = SignedManifest(Wire.encode(Wire.json.encodeToString(Manifest.serializer(), body).toByteArray()), Wire.encode(ByteArray(64)), emptyList(), emptyList())
        val genesis = draft.copy(signature = SyncCrypto.sign(owner, draft.signingBytes()))
        val data = RecoveryKitData(origin = origin, server = server, household = body.householdId,
            genesis = ManifestProof(genesis.manifestBytes, genesis.signature), recovery = recovery)
        val secret = SyncCrypto.randomKey()
        val file = Wire.json.encodeToString(KitFile.serializer(), KitFile(ciphertext = Wire.encode(SyncCrypto.encryptKit(secret,
            Wire.json.encodeToString(RecoveryKitData.serializer(), data).toByteArray())))).toByteArray()
        return NewHousehold(origin, server, genesis, owner, file, Wire.encode(secret))
    }
    internal fun replacement(saved: SavedHousehold, proofs: List<ManifestProof>): ReplacementKit {
        val recovery = SyncCrypto.device()
        val signature = SyncCrypto.sign(saved.device, replacementBytes(saved.origin, saved.server.serverId, saved.id, saved.genesisHash, saved.hash, recovery.publicKeys))
        val data = RecoveryKitData(origin = saved.origin, server = saved.server, household = saved.id, genesis = saved.genesis,
            recovery = recovery, anchor = proofs, replacementSigner = saved.device.publicKeys.id, replacementSignature = signature)
        val secret = SyncCrypto.randomKey()
        val file = Wire.json.encodeToString(KitFile.serializer(), KitFile(ciphertext = Wire.encode(SyncCrypto.encryptKit(secret,
            Wire.json.encodeToString(RecoveryKitData.serializer(), data).toByteArray())))).toByteArray()
        return ReplacementKit(saved.id, saved.hash, recovery.publicKeys, file, Wire.encode(secret))
    }
    private fun replacementBytes(origin: String, server: String, household: String, genesis: String, previous: String, recovery: DeviceKeys) =
        ("USUALS/RECOVERY-REPLACEMENT/1\u0000" + Wire.json.encodeToString(kotlinx.serialization.json.buildJsonObject {
            put("origin", kotlinx.serialization.json.JsonPrimitive(origin)); put("server", kotlinx.serialization.json.JsonPrimitive(server))
            put("household", kotlinx.serialization.json.JsonPrimitive(household)); put("genesis", kotlinx.serialization.json.JsonPrimitive(genesis))
            put("previous", kotlinx.serialization.json.JsonPrimitive(previous)); put("recovery", Wire.json.encodeToJsonElement(DeviceKeys.serializer(), recovery))
        })).toByteArray()
    fun open(file: ByteArray, secret: String): RecoveryKitData {
        val encoded = Wire.json.decodeFromJsonElement(KitFile.serializer(), Wire.strictJson(file, 8 * 1024 * 1024))
        require(encoded.version == 1)
        val bytes = SyncCrypto.decryptKit(Wire.decode(secret, 32), Wire.decode(encoded.ciphertext))
        val kit = Wire.json.decodeFromJsonElement(RecoveryKitData.serializer(), Wire.strictJson(bytes, SyncCrypto.KIT_MAX_PLAINTEXT))
        require(kit.version == 1)
        val genesis = VerifiedMembership.genesis(kit.genesis, kit.server, kit.household, kit.genesis.signed().hash())
        if (kit.anchor.isEmpty()) {
            require(genesis.manifest.recovery == kit.recovery.publicKeys && kit.replacementSigner == null && kit.replacementSignature == null)
        } else {
            require(kit.anchor.size <= 10000 && kit.anchor.first() == kit.genesis)
            var verified = genesis
            kit.anchor.drop(1).forEach { verified = verified.advance(it) }
            val signer = verified.manifest.members.firstOrNull { it.role == "owner" && it.devices.any { d -> d.id == kit.replacementSigner } }
                ?.devices?.first { it.id == kit.replacementSigner } ?: throw IllegalArgumentException("unverified_replacement")
            SyncCrypto.verify(signer.signingPublicKey, requireNotNull(kit.replacementSignature), replacementBytes(kit.origin, kit.server.serverId,
                kit.household, kit.genesis.signed().hash(), verified.hash, kit.recovery.publicKeys))
        }
        val challenge = SyncCrypto.randomKey()
        SyncCrypto.verify(kit.recovery.publicKeys.signingPublicKey, SyncCrypto.sign(kit.recovery, challenge), challenge)
        val packageTest = SyncCrypto.wrap(kit.server.serverId, kit.household, UUID.randomUUID().toString(), 1, Wire.zero,
            kit.recovery.publicKeys, challenge, kit.recovery)
        require(SyncCrypto.unwrap(kit.server.serverId, kit.household, packageTest, kit.recovery, kit.recovery.publicKeys.signingPublicKey).contentEquals(challenge))
        return kit
    }
}

class ReplacementKit internal constructor(val household: String, internal val previous: String, internal val recovery: DeviceKeys,
    override val file: ByteArray, override val secret: String) : RecoveryDraft {
    fun verifySaved(file: ByteArray, secret: String): VerifiedReplacement {
        val restored = RecoveryKits.open(file, secret)
        require(restored.household == household && restored.recovery.publicKeys == recovery && restored.anchor.last().signed().hash() == previous)
        return VerifiedReplacement(household, previous, recovery)
    }
    override fun toString() = "ReplacementKit([redacted])"
}
class VerifiedReplacement internal constructor(internal val household: String, internal val previous: String, internal val recovery: DeviceKeys) {
    override fun toString() = "VerifiedReplacement([redacted])"
}

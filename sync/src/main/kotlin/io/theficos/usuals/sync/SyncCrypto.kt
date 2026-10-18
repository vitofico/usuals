package io.theficos.usuals.sync

import com.google.crypto.tink.*
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.subtle.AesGcmJce
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Random
import com.google.crypto.tink.util.Bytes
import com.google.crypto.tink.util.SecretBytes
import java.util.UUID
import kotlinx.serialization.Serializable

/** Never expose private keys through generated data-class diagnostics. */
@Serializable
class DeviceSecrets(val publicKeys: DeviceKeys, val signingSeed: String, val recipientSecret: String) {
    override fun toString() = "DeviceSecrets([redacted])"
}

object SyncCrypto {
    const val KIT_MAX_PLAINTEXT = 6 * 1024 * 1024 - 128
    private val access = InsecureSecretKeyAccess.get()
    private val hpke = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
        .setVariant(HpkeParameters.Variant.NO_PREFIX).build()
    init { HybridConfig.register() }
    fun randomKey(): ByteArray = Random.randBytes(32)
    fun device(): DeviceSecrets {
        val signing = Ed25519Sign.KeyPair.newKeyPair()
        val recipient = KeysetHandle.generateNew(hpke).primary.key as HpkePrivateKey
        return DeviceSecrets(DeviceKeys(UUID.randomUUID().toString(), Wire.encode(signing.publicKey),
            Wire.encode(recipient.publicKey.publicKeyBytes.toByteArray())), Wire.encode(signing.privateKey),
            Wire.encode(recipient.privateKeyBytes.toByteArray(access)))
    }
    fun sign(keys: DeviceSecrets, bytes: ByteArray): String = Wire.encode(Ed25519Sign(Wire.decode(keys.signingSeed, 32)).sign(bytes))
    fun verify(public: String, signature: String, bytes: ByteArray) = Ed25519Verify(Wire.decode(public, 32)).verify(Wire.decode(signature, 64), bytes)
    fun seal(header: Header, plaintext: ByteArray, key: ByteArray, author: DeviceSecrets,
        maxDecoded: Int = 65536, reserve: () -> Unit): Envelope {
        require(key.size == 32 && header.authorDeviceId == author.publicKeys.id && plaintext.size + 288 <= maxDecoded)
        val encodedHeader = header.bytes()
        // Persist a reservation before Tink can generate a nonce, including abandoned attempts.
        reserve()
        val cipher = AesGcmJce(key).encrypt(plaintext, Wire.domain("AAD", encodedHeader))
        val draft = Envelope(Wire.encode(encodedHeader), Wire.encode(cipher), Wire.encode(ByteArray(64)))
        return draft.copy(signature = sign(author, draft.signingBytes())).also { it.validate(maxDecoded) }
    }
    fun open(envelope: Envelope, key: ByteArray, authorPublic: String, maxDecoded: Int = 65536): ByteArray {
        envelope.validate(maxDecoded)
        verify(authorPublic, envelope.signature, envelope.signingBytes())
        return AesGcmJce(key).decrypt(Wire.decode(envelope.ciphertext), Wire.domain("AAD", Wire.decode(envelope.header, 196)))
    }
    fun wrap(server: String, household: String, list: String, epoch: Long, predecessor: String,
        recipient: DeviceKeys, key: ByteArray, author: DeviceSecrets): KeyPackage {
        require(key.size == 32 && epoch > 0)
        val p = KeyPackage(list, Wire.fingerprint(server, household, recipient.signingPublicKey, recipient.recipientPublicKey),
            epoch, predecessor, Wire.encode(ByteArray(80)), Wire.encode(ByteArray(64)))
        val publicKey = HpkePublicKey.create(hpke, Bytes.copyFrom(Wire.decode(recipient.recipientPublicKey, 32)), null)
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(publicKey).withRandomId().makePrimary()).build()
        val cipher = handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java).encrypt(key, p.context(server, household))
        val draft = p.copy(ciphertext = Wire.encode(cipher))
        return draft.copy(signature = sign(author, draft.signingBytes(server, household)))
    }
    fun unwrap(server: String, household: String, p: KeyPackage, recipient: DeviceSecrets, authorPublic: String): ByteArray {
        require(p.recipientFingerprint == Wire.fingerprint(server, household, recipient.publicKeys.signingPublicKey, recipient.publicKeys.recipientPublicKey))
        verify(authorPublic, p.signature, p.signingBytes(server, household))
        val public = HpkePublicKey.create(hpke, Bytes.copyFrom(Wire.decode(recipient.publicKeys.recipientPublicKey, 32)), null)
        val private = HpkePrivateKey.create(public, SecretBytes.copyFrom(Wire.decode(recipient.recipientSecret, 32), access))
        val handle = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(private).withRandomId().makePrimary()).build()
        return handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
            .decrypt(Wire.decode(p.ciphertext), p.context(server, household)).also { require(it.size == 32) }
    }
    fun encryptKit(secret: ByteArray, plaintext: ByteArray): ByteArray {
        require(secret.size == 32 && plaintext.size <= KIT_MAX_PLAINTEXT)
        return AesGcmJce(secret).encrypt(plaintext, "USUALS/RECOVERY-KIT/1\u0000".toByteArray())
    }
    fun decryptKit(secret: ByteArray, ciphertext: ByteArray): ByteArray {
        require(secret.size == 32 && ciphertext.size in 28..(KIT_MAX_PLAINTEXT + 28))
        return AesGcmJce(secret).decrypt(ciphertext, "USUALS/RECOVERY-KIT/1\u0000".toByteArray())
    }
    fun verifyReceipt(envelope: Envelope, receipt: Receipt, serverPublic: String, nextSeq: Long, previousHead: String) {
        val header = envelope.parsed()
        require(Wire.hash(Wire.decode(serverPublic, 32)) == header.serverId && receipt.seq == nextSeq && receipt.previousHead == previousHead)
        require(Wire.head(previousHead, nextSeq, envelope) == receipt.head)
        verify(serverPublic, receipt.signature, Wire.receipt(header.serverId, header.householdId, header.listId, receipt.seq, receipt.head))
    }
}

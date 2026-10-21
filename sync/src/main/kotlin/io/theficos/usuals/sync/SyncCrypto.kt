package io.theficos.usuals.sync

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePrivateKey
import com.google.crypto.tink.subtle.Ed25519Sign
import java.util.UUID
import kotlinx.serialization.Serializable

/** Never expose private keys through generated data-class diagnostics. */
@Serializable
class DeviceSecrets(val publicKeys: DeviceKeys, val signingSeed: String, val recipientSecret: String) {
    override fun toString() = "DeviceSecrets([redacted])"
}

/** The key pairs a phone presents when it connects; the browser sign-in compares a code derived from their public halves. */
object SyncCrypto {
    private val hpke = HpkeParameters.builder()
        .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
        .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
        .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
        .setVariant(HpkeParameters.Variant.NO_PREFIX).build()
    init { HybridConfig.register() }
    fun device(): DeviceSecrets {
        val signing = Ed25519Sign.KeyPair.newKeyPair()
        val recipient = KeysetHandle.generateNew(hpke).primary.key as HpkePrivateKey
        return DeviceSecrets(DeviceKeys(UUID.randomUUID().toString(), Wire.encode(signing.publicKey),
            Wire.encode(recipient.publicKey.publicKeyBytes.toByteArray())), Wire.encode(signing.privateKey),
            Wire.encode(recipient.privateKeyBytes.toByteArray(InsecureSecretKeyAccess.get())))
    }
}

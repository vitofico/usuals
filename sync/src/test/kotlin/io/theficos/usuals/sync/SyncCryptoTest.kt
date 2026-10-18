package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import java.security.GeneralSecurityException
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

class SyncCryptoTest {
    private fun uuid() = UUID.randomUUID().toString()
    @Test fun authenticatedCiphertextBindsEveryHeaderAndRefusesTampering() {
        val author = SyncCrypto.device(); val key = SyncCrypto.randomKey()
        val header = Header(Wire.hash(byteArrayOf(1)), uuid(), uuid(), author.publicKeys.id, uuid(), uuid(), 1, Wire.zero, 0, Wire.zero)
        var reservations = 0
        val envelope = SyncCrypto.seal(header, "synthetic milk".toByteArray(), key, author) { reservations++ }
        assertThat(reservations).isEqualTo(1)
        assertThat(SyncCrypto.open(envelope, key, author.publicKeys.signingPublicKey).toString(Charsets.UTF_8)).isEqualTo("synthetic milk")
        assertThrows(GeneralSecurityException::class.java) { SyncCrypto.open(envelope, SyncCrypto.randomKey(), author.publicKeys.signingPublicKey) }
        val corrupt = Wire.decode(envelope.ciphertext).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { SyncCrypto.open(envelope.copy(ciphertext = Wire.encode(corrupt)), key, author.publicKeys.signingPublicKey) }
        assertThrows(GeneralSecurityException::class.java) { SyncCrypto.open(envelope.copy(header = Wire.encode(header.copy(listId = uuid()).bytes())), key, author.publicKeys.signingPublicKey) }
        assertThrows(IllegalStateException::class.java) { SyncCrypto.seal(header, byteArrayOf(1), key, author) { error("reservation failed") } }
        assertThat(reservations).isEqualTo(1)
        assertThat(author.toString()).isEqualTo("DeviceSecrets([redacted])")
    }
    @Test fun signedHpkePackagesBindRecipientAndEpochAndSurviveSecretSerialization() {
        val owner = SyncCrypto.device(); val recipient = SyncCrypto.device(); val key = SyncCrypto.randomKey()
        val server = Wire.hash(byteArrayOf(2)); val household = uuid()
        val p = SyncCrypto.wrap(server, household, uuid(), 2, Wire.zero, recipient.publicKeys, key, owner)
        val restored = Wire.json.decodeFromString<DeviceSecrets>(Wire.json.encodeToString(DeviceSecrets.serializer(), recipient))
        assertThat(SyncCrypto.unwrap(server, household, p, restored, owner.publicKeys.signingPublicKey)).isEqualTo(key)
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.unwrap(server, household, p, SyncCrypto.device(), owner.publicKeys.signingPublicKey) }
        assertThrows(GeneralSecurityException::class.java) { SyncCrypto.unwrap(server, household, p.copy(epoch = 3), recipient, owner.publicKeys.signingPublicKey) }
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.unwrap(server, uuid(), p, recipient, owner.publicKeys.signingPublicKey) }
        assertThrows(GeneralSecurityException::class.java) { SyncCrypto.unwrap(server, household, p, recipient, recipient.publicKeys.signingPublicKey) }
    }
    @Test fun recoveryKitNeedsBothFileAndRandomSecret() {
        val secret = SyncCrypto.randomKey(); val content = "synthetic-recovery-private-keys".toByteArray()
        val file = SyncCrypto.encryptKit(secret, content)
        assertThat(file.toString(Charsets.UTF_8)).doesNotContain("synthetic-recovery-private-keys")
        assertThat(SyncCrypto.decryptKit(secret, file)).isEqualTo(content)
        assertThrows(GeneralSecurityException::class.java) { SyncCrypto.decryptKit(SyncCrypto.randomKey(), file) }
        val tampered = file.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { SyncCrypto.decryptKit(secret, tampered) }
    }
    @Test fun replacementRecoveryKitSupportsALegitimateProofChainLargerThan64KiB() {
        val receipt = SyncCrypto.device()
        val server = ServerInfo(Wire.hash(Wire.decode(receipt.publicKeys.signingPublicKey, 32)), receipt.publicKeys.signingPublicKey, listOf(1))
        val setup = RecoveryKits.prepare("https://relay.example", server)
        val genesis = ManifestProof(setup.genesis.manifestBytes, setup.genesis.signature)
        val proofs = mutableListOf(genesis)
        repeat(100) {
            val prior = proofs.last().signed()
            val body = prior.body().copy(epoch = prior.body().epoch + 1, genesisHash = setup.genesis.hash(), previousHash = prior.hash(), action = "rotate")
            val draft = SignedManifest(Wire.encode(Wire.json.encodeToString(Manifest.serializer(), body).toByteArray()), Wire.encode(ByteArray(64)), emptyList(), emptyList())
            val signed = draft.copy(signature = SyncCrypto.sign(setup.device, draft.signingBytes()))
            proofs += ManifestProof(signed.manifestBytes, signed.signature)
        }
        val saved = SavedHousehold(setup.origin, server, setup.genesis.body().householdId, genesis, proofs.last(), setup.device)
        val replacement = RecoveryKits.replacement(saved, proofs)
        assertThat(replacement.file.size).isGreaterThan(65536)
        val verified = replacement.verifySaved(replacement.file, replacement.secret)
        assertThat(verified.recovery).isEqualTo(RecoveryKits.open(replacement.file, replacement.secret).recovery.publicKeys)
    }

    @Test fun recoveryFileAcceptsTheExactCryptoBoundaryIncludingBase64AndAeadFraming() {
        val receipt = SyncCrypto.device()
        val server = ServerInfo(Wire.hash(Wire.decode(receipt.publicKeys.signingPublicKey, 32)), receipt.publicKeys.signingPublicKey)
        val setup = RecoveryKits.prepare("https://relay.example", server)
        val kit = RecoveryKits.open(setup.file, setup.secret)
        val serialized = Wire.json.encodeToString(RecoveryKitData.serializer(), kit).toByteArray()
        val padded = ByteArray(SyncCrypto.KIT_MAX_PLAINTEXT) { 32 }.also { serialized.copyInto(it) }
        val secret = SyncCrypto.randomKey()
        val ciphertext = SyncCrypto.encryptKit(secret, padded)
        val file = ("{\"version\":1,\"ciphertext\":\"" + Wire.encode(ciphertext) + "\"}").toByteArray()
        assertThat(file.size).isAtMost(8 * 1024 * 1024)
        assertThat(RecoveryKits.open(file, Wire.encode(secret)).recovery.publicKeys).isEqualTo(kit.recovery.publicKeys)
        assertThrows(IllegalArgumentException::class.java) { SyncCrypto.encryptKit(secret, ByteArray(SyncCrypto.KIT_MAX_PLAINTEXT + 1)) }
    }

}

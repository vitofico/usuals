package io.theficos.usuals

import androidx.test.platform.app.InstrumentationRegistry
import android.system.Os
import io.theficos.usuals.auth.*
import io.theficos.usuals.sync.*
import java.io.File
import java.security.KeyStore
import org.junit.Assert.*
import org.junit.Test

class SyncVaultDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private inline fun isolated(block: (String, File) -> Unit) {
        val namespace = "test-" + java.util.UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, namespace)
        try { block(namespace, directory) } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("usuals_sync_wrap_v1_$namespace") }
        }
    }
    @Test fun wrappedKeysAndUsageReservationsSurviveReopenAndInterruptedMutation() = isolated { name, directory ->
        val first = SyncVault(context, name)
        val secret = Wire.encode(SyncCrypto.randomKey())
        first.put("fixture", secret)
        assertTrue(SyncVault(context, name).get("fixture") == secret)
        assertFalse(File(directory, "keys.v1").readBytes().toString(Charsets.UTF_8).contains(secret))
        assertEquals(384, Os.stat(File(directory, "keys.v1").path).st_mode and 511)
        assertEquals(1, first.reserve("test-scope"))
        assertEquals(2, SyncVault(context, name).reserve("test-scope"))
        first.transaction { _, counts -> counts["test-scope"] = 4095 }
        assertEquals(4096, SyncVault(context, name).reserve("test-scope"))
        assertEquals(VaultProblem.USAGE_EXHAUSTED, assertThrows(VaultException::class.java) { first.reserve("test-scope") }.problem)
        assertThrows(IllegalStateException::class.java) { first.transaction { entries, _ -> entries["fixture"] = "changed"; error("interrupted") } }
        assertTrue(SyncVault(context, name).get("fixture") == secret)
    }
    @Test fun aLostWrappingKeyFailsClosedWithoutReplacingThePrivateState() = isolated { name, directory ->
        val vault = SyncVault(context, name); vault.put("fixture", Wire.encode(SyncCrypto.randomKey()))
        val before = File(directory, "keys.v1").readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("usuals_sync_wrap_v1_$name") }
        val error = assertThrows(VaultException::class.java) { SyncVault(context, name).get("fixture") }
        assertEquals(VaultProblem.KEY_UNAVAILABLE, error.problem)
        assertArrayEquals(before, File(directory, "keys.v1").readBytes())
    }
    @Test fun explicitResetCannotClearHealthyKeysAndLostKeysGetAFreshVault() = isolated { name, directory ->
        val vault = SyncVault(context, name)
        vault.put("client", "synthetic-private-state"); vault.reserve("old-identity-key")
        val before = File(directory, "keys.v1").readBytes()
        assertFalse(vault.resetUnavailable())
        assertArrayEquals(before, File(directory, "keys.v1").readBytes())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("usuals_sync_wrap_v1_$name") }
        assertTrue(vault.resetUnavailable())
        assertNull(vault.get("client"))
        assertEquals(1, vault.reserve("fresh-identity-key"))
        assertFalse(File(directory, "keys.v1").readBytes().contentEquals(before))
    }
    @Test fun theDeviceExchangesSignedEncryptedEnvelopesAndRecipientPackages() {
        val a = SyncCrypto.device(); val b = SyncCrypto.device(); val key = SyncCrypto.randomKey()
        val server = Wire.hash(SyncCrypto.randomKey()); val household = java.util.UUID.randomUUID().toString()
        val list = java.util.UUID.randomUUID().toString()
        val p = SyncCrypto.wrap(server, household, list, 1, Wire.zero, b.publicKeys, key, a)
        val received = SyncCrypto.unwrap(server, household, p, b, a.publicKeys.signingPublicKey)
        val header = Header(server, household, list, a.publicKeys.id, java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(), 1, Wire.zero, 0, Wire.zero)
        val envelope = SyncCrypto.seal(header, "synthetic-list-fixture".toByteArray(), key, a) { }
        assertTrue(SyncCrypto.open(envelope, received, a.publicKeys.signingPublicKey).toString(Charsets.UTF_8) == "synthetic-list-fixture")
        assertThrows(java.security.GeneralSecurityException::class.java) { SyncCrypto.open(envelope, SyncCrypto.randomKey(), a.publicKeys.signingPublicKey) }
    }
}

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
    @Test fun wrappedStateSurvivesReopenAndInterruptedMutation() = isolated { name, directory ->
        val first = SyncVault(context, name)
        val secret = Wire.encode(SyncCrypto.randomKey())
        first.put("fixture", secret)
        assertTrue(SyncVault(context, name).get("fixture") == secret)
        assertFalse(File(directory, "keys.v1").readBytes().toString(Charsets.UTF_8).contains(secret))
        assertEquals(384, Os.stat(File(directory, "keys.v1").path).st_mode and 511)
        assertThrows(IllegalStateException::class.java) { first.transaction { entries -> entries["fixture"] = "changed"; error("interrupted") } }
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
        vault.put("client", "synthetic-private-state")
        val before = File(directory, "keys.v1").readBytes()
        assertFalse(vault.resetUnavailable())
        assertArrayEquals(before, File(directory, "keys.v1").readBytes())
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("usuals_sync_wrap_v1_$name") }
        assertTrue(vault.resetUnavailable())
        assertNull(vault.get("client"))
        vault.put("client", "fresh-private-state")
        assertFalse(File(directory, "keys.v1").readBytes().contentEquals(before))
    }
}

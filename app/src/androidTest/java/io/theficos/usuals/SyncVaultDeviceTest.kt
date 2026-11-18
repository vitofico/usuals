package io.theficos.usuals

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.auth.SyncVault
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncVaultDeviceTest {
    @Test fun keyFileHasOwnerOnlyPermissionsOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test-${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, namespace)
        try {
            SyncVault(context, namespace).put("fixture", "synthetic-private-state")
            assertEquals(384, Os.stat(File(directory, "keys.v1").path).st_mode and 511)
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry("usuals_sync_wrap_v1_$namespace")
            }
        }
    }
}

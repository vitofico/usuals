package io.theficos.usuals.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.testing.FakeAndroidKeyStore
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncVaultTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val namespace = "test-${UUID.randomUUID()}"
    private val directory get() = File(context.noBackupFilesDir, namespace)
    private val state get() = File(directory, "keys.v1")
    private val store get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias get() = "usuals_sync_wrap_v1_$namespace"
    private val secret = "synthetic-private-state"

    @Before fun installKeystore() = FakeAndroidKeyStore.setup()

    @After fun cleanUp() {
        directory.deleteRecursively()
        store.deleteEntry(alias)
    }

    @Test fun wrappedStateSurvivesReopenAndInterruptedMutation() {
        val first = SyncVault(context, namespace)
        first.put("fixture", secret)
        assertThat(store.containsAlias(alias)).isTrue()
        assertThat(SyncVault(context, namespace).get("fixture")).isEqualTo(secret)
        assertThat(state.readBytes().decodeToString()).doesNotContain(secret)
        assertThrows(IllegalStateException::class.java) {
            first.transaction { entries ->
                entries["fixture"] = "interrupted"
                error("interrupt the write")
            }
        }
        assertThat(SyncVault(context, namespace).get("fixture")).isEqualTo(secret)
    }

    @Test fun resetRejectsWriteFailuresAndOnlyResetsUnavailableState() {
        val vault = SyncVault(context, namespace)
        vault.put("client", secret)
        val before = state.readBytes().toList()
        assertThat(vault.resetUnavailable()).isFalse()

        val lock = File(directory, "lock")
        check(lock.delete() && lock.mkdir())
        try {
            val error = assertThrows(VaultException::class.java) { vault.resetUnavailable() }
            assertThat(error.problem).isEqualTo(VaultProblem.WRITE_FAILED)
            assertThat(state.readBytes().toList()).isEqualTo(before)
        } finally {
            check(lock.delete())
        }
        assertThat(SyncVault(context, namespace).get("client")).isEqualTo(secret)

        store.deleteEntry(alias)
        val lost = assertThrows(VaultException::class.java) { vault.get("client") }
        assertThat(lost.problem).isEqualTo(VaultProblem.KEY_UNAVAILABLE)
        assertThat(state.readBytes().toList()).isEqualTo(before)
        assertThat(vault.resetUnavailable()).isTrue()
        assertThat(vault.get("client")).isNull()
        vault.put("client", "fresh-synthetic-state")
        assertThat(vault.get("client")).isEqualTo("fresh-synthetic-state")
    }
}

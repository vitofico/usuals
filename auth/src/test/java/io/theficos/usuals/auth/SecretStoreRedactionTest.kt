package io.theficos.usuals.auth

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.testing.FakeAndroidKeyStore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Check every SecretStore implementation here before it can enter logs through a string template. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecretStoreRedactionTest {
    @Test fun `toString is constant and redacted before and after storing a key`() {
        FakeAndroidKeyStore.setup()
        val store = KeystoreSecretStore(ApplicationProvider.getApplicationContext())
        val key = "test-model-key-no-real-secret-here"
        store.clear()
        val before = store.toString()
        assertThat(before).contains(store.javaClass.simpleName)
        assertThat(before).contains(SecretStore.REDACTED)
        assertThat(before).doesNotContain(key)

        store.putModelKey(key)
        assertThat(store.toString()).doesNotContain(key)
        assertThat(store.toString()).isEqualTo(before)
    }
}

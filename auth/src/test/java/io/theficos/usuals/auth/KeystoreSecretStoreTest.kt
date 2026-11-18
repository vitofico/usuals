package io.theficos.usuals.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.proto.EncryptedKeyset
import io.theficos.usuals.testing.FakeAndroidKeyStore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KeystoreSecretStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setup()
        context = ApplicationProvider.getApplicationContext()
        // Robolectric keeps preference files for the duration of a test method,
        // but the JVM process is shared across methods in some configurations.
        // Starting from a known-empty store keeps each test independent of the
        // order the runner picks.
        KeystoreSecretStore(context).clear()
    }

    private fun store() = KeystoreSecretStore(context)

    @Test
    fun `a fresh instance sees initial replacement and cleared state`() {
        val first = store()
        assertThat(first.modelKey()).isNull()
        assertThat(first.hasModelKey()).isFalse()

        first.putModelKey(SAMPLE_KEY)
        val second = store()
        assertThat(second.modelKey()).isEqualTo(SAMPLE_KEY)
        assertThat(second.hasModelKey()).isTrue()
        first.putModelKey(OTHER_KEY)
        assertThat(second.modelKey()).isEqualTo(OTHER_KEY)

        first.clear()
        for (reader in listOf(first, second, store())) {
            assertThat(reader.modelKey()).isNull()
            assertThat(reader.hasModelKey()).isFalse()
        }
    }

    @Test
    fun `padded keys are trimmed and blanks are rejected`() {
        val subject = store()
        for (blank in listOf("", "   ", "\n", "\t ")) {
            val thrown = runCatching { subject.putModelKey(blank) }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(subject.modelKey()).isNull()
        }
        subject.putModelKey("  \n$SAMPLE_KEY\t ")
        assertThat(subject.modelKey()).isEqualTo(SAMPLE_KEY)
    }

    /**
     * Invariant 5, at rest. Open the same preference file without the
     * encryption wrapper and confirm the key is not sitting in it, neither as a
     * value nor as a preference name (the name encryption is what stops
     * "model_api_key" from advertising which entry to attack).
     *
     * The final assertion is the inverse property, in quire's shared-cache
     * style: if a refactor made this test pass by simply not storing anything,
     * the last line fails and says so.
     */
    @Test
    fun `the key is not readable from the raw preference file`() {
        store().putModelKey(SAMPLE_KEY)

        val raw = context.getSharedPreferences(
            KeystoreSecretStore.FILE_NAME,
            Context.MODE_PRIVATE,
        ).all

        assertThat(raw).isNotEmpty()
        for ((name, value) in raw) {
            assertThat(name).doesNotContain(SAMPLE_KEY)
            assertThat(name).doesNotContain(KeystoreSecretStore.KEY_MODEL_KEY)
            assertThat(value.toString()).doesNotContain(SAMPLE_KEY)
        }
        for (purpose in listOf("key", "value")) {
            val serialized = raw.getValue("__androidx_security_crypto_encrypted_prefs_${purpose}_keyset__") as String
            val wrapped = EncryptedKeyset.parseFrom(
                serialized.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            )
            assertThat(wrapped.encryptedKeyset.size()).isGreaterThan(0)
            assertThat(wrapped.keysetInfo.keyInfoCount).isGreaterThan(0)
        }

        assertThat(store().modelKey()).isEqualTo(SAMPLE_KEY)
    }

    private companion object {
        // Deliberately unlike any real provider's key format, so a secret
        // scanner has nothing to flag and nobody mistakes it for a leak.
        const val SAMPLE_KEY = "test-model-key-no-real-secret-here"
        const val OTHER_KEY = "test-model-key-the-second-one"
    }
}

package io.theficos.usuals.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
    fun `starts empty`() {
        assertThat(store().modelKey()).isNull()
        assertThat(store().hasModelKey()).isFalse()
    }

    @Test
    fun `round trips a key through a fresh instance`() {
        store().putModelKey(SAMPLE_KEY)

        // A second instance reads from disk rather than from a field, which is
        // what a cold app start does.
        assertThat(store().modelKey()).isEqualTo(SAMPLE_KEY)
        assertThat(store().hasModelKey()).isTrue()
    }

    @Test
    fun `putModelKey replaces the previous key`() {
        val subject = store()
        subject.putModelKey(SAMPLE_KEY)
        subject.putModelKey(OTHER_KEY)

        assertThat(subject.modelKey()).isEqualTo(OTHER_KEY)
    }

    @Test
    fun `putModelKey trims surrounding whitespace`() {
        val subject = store()
        subject.putModelKey("  \n$SAMPLE_KEY\t ")

        assertThat(subject.modelKey()).isEqualTo(SAMPLE_KEY)
    }

    @Test
    fun `putModelKey rejects a blank key`() {
        val subject = store()

        for (blank in listOf("", "   ", "\n", "\t ")) {
            val thrown = runCatching { subject.putModelKey(blank) }.exceptionOrNull()
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(subject.modelKey()).isNull()
    }

    @Test
    fun `clear forgets the key`() {
        val subject = store()
        subject.putModelKey(SAMPLE_KEY)
        subject.clear()

        assertThat(subject.modelKey()).isNull()
        assertThat(store().modelKey()).isNull()
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

        assertThat(store().modelKey()).isEqualTo(SAMPLE_KEY)
    }

    private companion object {
        // Deliberately unlike any real provider's key format, so a secret
        // scanner has nothing to flag and nobody mistakes it for a leak.
        const val SAMPLE_KEY = "test-model-key-no-real-secret-here"
        const val OTHER_KEY = "test-model-key-the-second-one"
    }
}

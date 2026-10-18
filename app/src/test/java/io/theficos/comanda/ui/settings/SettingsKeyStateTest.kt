package io.theficos.comanda.ui.settings

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * What the settings screen does when the keystore will not answer.
 *
 * This is a regression test for a crash, not for a feature. `KeystoreSecretStore`
 * opens `AndroidKeyStore` and decrypts a master key, and on a device where that
 * is locked or restricted it throws. The throw happened inside a coroutine this
 * view model launched, with nothing above it to catch it, so it reached the
 * thread's handler and took the process down from a settings screen. It first
 * showed up as a test in a different class failing, because an uncaught
 * exception from one coroutine lands wherever the runtime is standing.
 *
 * The rule the tests below encode is that a keystore refusing to answer is
 * never rendered as an answer: not as "no key", and after a failed delete, not
 * as "key removed".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsKeyStateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A store whose every method throws the way a locked keystore does. */
    private object LockedKeyStore : SecretStorePort {
        override suspend fun putModelKey(key: String): Unit = throw keyStoreFailure()

        override suspend fun clearModelKey(): Unit = throw keyStoreFailure()

        override suspend fun hasModelKey(): Boolean = throw keyStoreFailure()

        private fun keyStoreFailure() =
            java.security.KeyStoreException("AndroidKeyStore not found")
    }

    private class RecordingKeyStore(private var stored: String? = null) : SecretStorePort {
        var cleared: Boolean = false
            private set

        override suspend fun putModelKey(key: String) {
            stored = key
        }

        override suspend fun clearModelKey() {
            stored = null
            cleared = true
        }

        override suspend fun hasModelKey(): Boolean = stored != null
    }

    private fun viewModel(secrets: SecretStorePort, scope: TestScope) = SettingsViewModel(
        secrets = secrets,
        scope = scope,
        worker = dispatcher,
    )

    @Test
    fun `a keystore that will not answer is not reported as an empty one`() = runTest(dispatcher) {
        val subject = viewModel(LockedKeyStore, this)

        subject.loadIfNeeded()
        advanceUntilIdle()

        assertThat(subject.state.value.apiKey).isEqualTo(KeyState.UNAVAILABLE)
        // The rest of the screen still loads. A keystore is not the settings.
        assertThat(subject.state.value.loaded).isTrue()
    }

    @Test
    fun `a key that could not be stored is not reported as stored`() = runTest(dispatcher) {
        val subject = viewModel(LockedKeyStore, this)

        subject.setApiKeyDraft("sk-whatever")
        subject.saveApiKey()
        advanceUntilIdle()

        assertThat(subject.state.value.apiKey).isEqualTo(KeyState.UNAVAILABLE)
    }

    @Test
    fun `a failed save still drops the draft rather than holding a secret`() = runTest(dispatcher) {
        val subject = viewModel(LockedKeyStore, this)

        subject.setApiKeyDraft("sk-whatever")
        subject.saveApiKey()
        advanceUntilIdle()

        assertThat(subject.state.value.apiKeyDraft).isEmpty()
    }

    @Test
    fun `a delete that threw does not claim the key is gone`() = runTest(dispatcher) {
        val subject = viewModel(LockedKeyStore, this)

        subject.clearApiKey()
        advanceUntilIdle()

        // Not ABSENT. The delete may have removed nothing, and saying otherwise
        // would tell someone their key is gone while it is still on the device.
        assertThat(subject.state.value.apiKey).isEqualTo(KeyState.UNAVAILABLE)
    }

    @Test
    fun `a working keystore still reports presence and absence`() = runTest(dispatcher) {
        val store = RecordingKeyStore(stored = "sk-existing")
        val subject = viewModel(store, this)

        subject.loadIfNeeded()
        advanceUntilIdle()
        assertThat(subject.state.value.apiKey).isEqualTo(KeyState.PRESENT)

        subject.clearApiKey()
        advanceUntilIdle()
        assertThat(subject.state.value.apiKey).isEqualTo(KeyState.ABSENT)
        assertThat(store.cleared).isTrue()

        subject.setApiKeyDraft("sk-new")
        subject.saveApiKey()
        advanceUntilIdle()
        assertThat(subject.state.value.apiKey).isEqualTo(KeyState.PRESENT)
        assertThat(subject.state.value.apiKeyDraft).isEmpty()
    }
}

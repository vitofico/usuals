package io.theficos.comanda.auth

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.testing.FakeAndroidKeyStore
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Invariant 5, in memory rather than at rest.
 *
 * The failure this guards against is not a leak somebody designs, it is a leak
 * somebody types: a `Log.d(TAG, "auth state: $store")` added at midnight while
 * chasing an unrelated 401. Kotlin string templates call `toString()`, so the
 * only way that line is safe is if `toString()` cannot say anything about what
 * is stored. Every implementation is checked, including the fake, because the
 * fake is what previews and other modules' tests hold.
 *
 * Adding a `SecretStore` implementation means adding it to [implementations].
 * That is the one manual step, and it is cheap enough to be worth the coverage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecretStoreRedactionTest {

    private lateinit var implementations: List<SecretStore>

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setup()
        implementations = listOf(
            InMemorySecretStore(),
            KeystoreSecretStore(ApplicationProvider.getApplicationContext()),
        )
        implementations.forEach { it.clear() }
    }

    @Test
    fun `toString never reveals the stored key`() {
        for (store in implementations) {
            val emptyDescription = store.toString()
            store.putModelKey(SAMPLE_KEY)

            assertWithMessage("${store.javaClass.simpleName} leaked the key through toString()")
                .that(store.toString())
                .doesNotContain(SAMPLE_KEY)

            // Constant, not merely key-free: a description that changed shape
            // when a key arrived would still tell an onlooker whether one is
            // configured, and a future edit could widen that hint into the
            // value itself.
            assertWithMessage("${store.javaClass.simpleName} varies its toString() with state")
                .that(store.toString())
                .isEqualTo(emptyDescription)
        }
    }

    @Test
    fun `toString names the class and marks the key redacted`() {
        for (store in implementations) {
            val description = store.toString()

            assertThat(description).contains(store.javaClass.simpleName)
            assertThat(description).contains(SecretStore.REDACTED)
        }
    }

    /**
     * Not a redaction property, an ergonomics one that supports it: a caller
     * that only needs to know whether rung 4 is available must never have to
     * take the plaintext out of the store to find out.
     */
    @Test
    fun `hasModelKey answers without exposing the key`() {
        for (store in implementations) {
            assertThat(store.hasModelKey()).isFalse()
            store.putModelKey(SAMPLE_KEY)
            assertThat(store.hasModelKey()).isTrue()
            store.clear()
            assertThat(store.hasModelKey()).isFalse()
        }
    }

    private companion object {
        const val SAMPLE_KEY = "test-model-key-no-real-secret-here"
    }
}

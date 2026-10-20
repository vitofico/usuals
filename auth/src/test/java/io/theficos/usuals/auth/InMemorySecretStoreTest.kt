package io.theficos.usuals.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The fake has to behave like the real store, or every test written against it
 * is testing something the app does not do. These cases mirror
 * [KeystoreSecretStoreTest] one for one.
 */
class InMemorySecretStoreTest {

    @Test
    fun `starts empty`() {
        val store = InMemorySecretStore()

        assertThat(store.modelKey()).isNull()
        assertThat(store.hasModelKey()).isFalse()
    }

    @Test
    fun `can be seeded for a preview`() {
        val store = InMemorySecretStore(SAMPLE_KEY)

        assertThat(store.modelKey()).isEqualTo(SAMPLE_KEY)
        assertThat(store.hasModelKey()).isTrue()
    }

    @Test
    fun `a blank seed is the same as no seed`() {
        assertThat(InMemorySecretStore("   ").modelKey()).isNull()
        assertThat(InMemorySecretStore("").hasModelKey()).isFalse()
    }

    @Test
    fun `put replaces and trims`() {
        val store = InMemorySecretStore()
        store.putModelKey(SAMPLE_KEY)
        store.putModelKey("  $OTHER_KEY\n")

        assertThat(store.modelKey()).isEqualTo(OTHER_KEY)
    }

    @Test
    fun `put rejects a blank key`() {
        val store = InMemorySecretStore()

        val thrown = runCatching { store.putModelKey(" \t ") }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.modelKey()).isNull()
    }

    @Test
    fun `clear forgets the key`() {
        val store = InMemorySecretStore(SAMPLE_KEY)
        store.clear()

        assertThat(store.modelKey()).isNull()
        assertThat(store.hasModelKey()).isFalse()
    }

    private companion object {
        const val SAMPLE_KEY = "test-model-key-no-real-secret-here"
        const val OTHER_KEY = "test-model-key-the-second-one"
    }
}

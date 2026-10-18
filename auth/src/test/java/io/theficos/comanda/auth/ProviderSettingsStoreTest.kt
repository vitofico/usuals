package io.theficos.comanda.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProviderSettingsStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ProviderSettingsStore(context).clear()
    }

    private fun store() = ProviderSettingsStore(context)

    @Test
    fun `nothing configured reads as null`() {
        assertThat(store().load()).isNull()
    }

    @Test
    fun `round trips through a fresh instance`() {
        val settings = ProviderSettings.openAiCompatible(
            baseUrl = "http://192.168.1.20:8080/v1",
            model = "qwen2.5:7b",
            label = "La caja del estudio",
        )
        store().save(settings)

        assertThat(store().load()).isEqualTo(settings)
    }

    @Test
    fun `round trips every provider kind`() {
        val cases = listOf(
            ProviderSettings.anthropic(),
            ProviderSettings.ollama(),
            ProviderSettings.openAiCompatible("https://gateway.example.com/v1", "llama3.1"),
        )

        for (settings in cases) {
            store().save(settings)
            assertThat(store().load()).isEqualTo(settings)
        }
    }

    @Test
    fun `save replaces the previous configuration wholesale`() {
        val subject = store()
        subject.save(
            ProviderSettings.openAiCompatible(
                baseUrl = "http://192.168.1.20:8080/v1",
                model = "qwen2.5:7b",
                label = "La caja del estudio",
            ),
        )
        subject.save(ProviderSettings.anthropic())

        val loaded = subject.load()

        assertThat(loaded).isEqualTo(ProviderSettings.anthropic())
        // Notably the base URL from the previous record is gone rather than
        // lingering under the new kind, which is the failure mode of writing
        // fields one at a time without clearing.
        assertThat(loaded?.baseUrl).isEmpty()
    }

    @Test
    fun `clear forgets the configuration`() {
        val subject = store()
        subject.save(ProviderSettings.anthropic())
        subject.clear()

        assertThat(subject.load()).isNull()
        assertThat(store().load()).isNull()
    }

    /**
     * A downgrade after a future build wrote a provider kind this one has never
     * heard of. Reporting "unconfigured" sends the user back through the
     * settings screen, which is annoying. Guessing a kind sends their shopping
     * list to an endpoint they did not choose, which is not.
     */
    @Test
    fun `an unknown provider kind reads as unconfigured rather than guessed`() {
        context.getSharedPreferences(ProviderSettingsStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ProviderSettingsStore.KEY_KIND, "SOME_FUTURE_PROVIDER")
            .putString(ProviderSettingsStore.KEY_BASE_URL, "https://elsewhere.example.com")
            .commit()

        assertThat(store().load()).isNull()
    }

    /**
     * Forward compatibility the other way: a record written by a build that
     * added a field still loads here, with the unknown key ignored. This is
     * what the field-per-key layout buys over a serialized blob.
     */
    @Test
    fun `an unexpected extra preference is ignored`() {
        store().save(ProviderSettings.anthropic())
        context.getSharedPreferences(ProviderSettingsStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("some_field_from_a_later_version", "whatever")
            .commit()

        assertThat(store().load()).isEqualTo(ProviderSettings.anthropic())
    }
}

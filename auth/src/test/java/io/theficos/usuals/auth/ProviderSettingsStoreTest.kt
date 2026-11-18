package io.theficos.usuals.auth

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
    fun `stores replace and then clear configuration across fresh instances`() {
        val subject = store()
        assertThat(subject.load()).isNull()
        val cases = listOf(
            ProviderSettings.ollama(),
            ProviderSettings.openAiCompatible("https://gateway.example.com/v1", "llama3.1"),
            ProviderSettings.openAiCompatible(
                baseUrl = "http://192.168.1.20:8080/v1",
                model = "qwen2.5:7b",
                label = "La caja del estudio",
            ),
            ProviderSettings.anthropic(),
        )
        for (settings in cases) {
            subject.save(settings)
            assertThat(store().load()).isEqualTo(settings)
        }
        assertThat(subject.load()?.baseUrl).isEmpty()
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

}

package io.theficos.usuals.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.testing.FakeAndroidKeyStore
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The storage boundary between the provider configuration and the provider key,
 * enforced the way quire enforces its shared-cache tenancy: by a test that
 * reads the actual rows and fails when the boundary moves, rather than by a
 * comment saying it should not.
 *
 * Two properties, and the second one matters as much as the first:
 *
 * - The plain settings file contains nothing derived from the key. A future
 *   convenience ("just keep them together, it is one screen") fails here.
 * - The secret store still holds the key. Without this inverse property, a
 *   refactor that dropped key storage entirely would turn the first assertion
 *   green while breaking the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProviderSettingsTenancyTest {

    private lateinit var context: Context
    private lateinit var secrets: SecretStore
    private lateinit var settingsStore: ProviderSettingsStore

    @Before
    fun setUp() {
        FakeAndroidKeyStore.setup()
        context = ApplicationProvider.getApplicationContext()
        secrets = KeystoreSecretStore(context)
        settingsStore = ProviderSettingsStore(context)
        secrets.clear()
        settingsStore.clear()
    }

    @Test
    fun `the model key never lands in the provider settings file`() {
        secrets.putModelKey(SAMPLE_KEY)
        settingsStore.save(
            ProviderSettings.openAiCompatible(
                baseUrl = "http://192.168.1.20:8080/v1",
                model = "qwen2.5:7b",
                label = "La caja del estudio",
            ),
        )

        val settingsFile = context
            .getSharedPreferences(ProviderSettingsStore.FILE_NAME, Context.MODE_PRIVATE)
            .all

        assertThat(settingsFile).isNotEmpty()
        for ((name, value) in settingsFile) {
            assertWithMessage("provider settings entry '$name' carries the model key")
                .that(value.toString())
                .doesNotContain(SAMPLE_KEY)
        }

        // Inverse property: the key is stored, just not here.
        assertThat(secrets.modelKey()).isEqualTo(SAMPLE_KEY)
    }

    /**
     * Same boundary, stated as file identity. If the two stores ever collapse
     * onto one preference file, the configuration inherits Keystore's cost and
     * the key inherits the configuration's exposure, and neither trade is one
     * anybody would make deliberately.
     */
    @Test
    fun `the two stores use different preference files`() {
        assertThat(ProviderSettingsStore.FILE_NAME)
            .isNotEqualTo(KeystoreSecretStore.FILE_NAME)
    }

    /**
     * Clearing the configuration is not the same action as revoking the key, so
     * one must not quietly do the other. Someone correcting a base URL should
     * not have to re-enter a key they never touched.
     */
    @Test
    fun `clearing the settings leaves the key alone`() {
        secrets.putModelKey(SAMPLE_KEY)
        settingsStore.save(ProviderSettings.anthropic())

        settingsStore.clear()

        assertThat(settingsStore.load()).isNull()
        assertThat(secrets.modelKey()).isEqualTo(SAMPLE_KEY)
    }

    @Test
    fun `clearing the key leaves the settings alone`() {
        val settings = ProviderSettings.anthropic()
        secrets.putModelKey(SAMPLE_KEY)
        settingsStore.save(settings)

        secrets.clear()

        assertThat(secrets.modelKey()).isNull()
        assertThat(settingsStore.load()).isEqualTo(settings)
    }

    private companion object {
        const val SAMPLE_KEY = "test-model-key-no-real-secret-here"
    }
}

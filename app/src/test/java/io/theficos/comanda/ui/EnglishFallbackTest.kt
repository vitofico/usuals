package io.theficos.comanda.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class EnglishFallbackTest {
    @Test
    fun `untranslated device languages use the English default resources`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Reserved tags keep this independent of languages contributors add.
        for (tag in listOf("en-US", "zz-ZZ", "qaa-QM")) {
            val config = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val localized = context.createConfigurationContext(config)
            assertThat(localized.getString(R.string.household_fallback_name)).isEqualTo("Home")
            assertThat(localized.getString(R.string.settings_title)).isEqualTo("Settings")
            assertThat(localized.getString(R.string.scan_manual_add)).isEqualTo("Add")
        }
    }

    @Test
    fun `translated languages include regional fallback plurals and escaped text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tags = translationResourceDirectory().listFiles().orEmpty()
            .filter { it.isDirectory && UI_LOCALE_FOLDER.matches(it.name) }
            .map { it.name.removePrefix("values-").removePrefix("b+").replace('+', '-').replace("-r", "-") }
        assertThat(tags).isNotEmpty()
        for (tag in tags) {
            val config = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val localized = context.createConfigurationContext(config)
            assertThat(localized.getString(R.string.household_fallback_name)).isNotEmpty()
            assertThat(localized.getString(R.string.settings_title)).isNotEmpty()
            if (Regex("[a-z]{2,3}").matches(tag)) {
                val regional = Configuration(config).apply { setLocale(Locale.forLanguageTag("$tag-ZZ")) }
                assertThat(context.createConfigurationContext(regional).getString(R.string.settings_title))
                    .isEqualTo(localized.getString(R.string.settings_title))
            }
            val removal = localized.getString(R.string.sharing_remove_help, "fingerprint")
            assertThat(removal).contains("\n\n")
            assertThat(removal).doesNotContain("\\n")
            assertThat(removal).contains("fingerprint")
            for (count in listOf(0, 1, 2, 1_000_000)) {
                val number = String.format(Locale.forLanguageTag(tag), "%d", count)
                val stale = localized.resources.getQuantityString(R.plurals.history_stale_question, count, count)
                assertThat(stale).contains(number)
                assertThat(localized.resources.getQuantityString(R.plurals.list_item_count, count, count)).contains(number)
            }
        }
    }
}

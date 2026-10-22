package io.theficos.usuals.wiring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.R
import io.theficos.usuals.di.PreferenceSettingsStore
import org.xmlpull.v1.XmlPullParser
import io.theficos.usuals.di.preferenceRowFacts
import io.theficos.usuals.ui.product.RowFactsChoices
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Row-fact choices survive a restart, and rows can follow the lookup opt-in as it changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RowFactsPreferencesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `grades default on and a change is still there after a restart`() {
        assertThat(preferenceRowFacts(context).state.value).isEqualTo(RowFactsChoices())
        preferenceRowFacts(context).showGrades(false)
        assertThat(preferenceRowFacts(context).state.value.showGrades).isFalse()
    }

    @Test fun `the avoid list survives a restart and can be emptied`() {
        preferenceRowFacts(context).avoid("en:milk", true)
        preferenceRowFacts(context).avoid("en:nuts", true)
        assertThat(preferenceRowFacts(context).state.value.avoid).containsExactly("en:milk", "en:nuts")
        preferenceRowFacts(context).avoid("en:milk", false)
        assertThat(preferenceRowFacts(context).state.value.avoid).containsExactly("en:nuts")
    }

    /**
     * The avoid list is health data in shared preferences. Cloud backup must
     * carry no shared preferences at all, and device transfer only the database.
     */
    @Test fun `backup rules keep preferences, and so the avoid list, on the phone`() {
        val cloud = rules(R.xml.data_extraction_rules, "cloud-backup")
        assertThat(cloud.filter { it.first == "exclude" }.map { it.second }).containsAtLeast("sharedpref", "device_sharedpref")
        assertThat(cloud.filter { it.first == "include" }).isEmpty()
        val transfer = rules(R.xml.data_extraction_rules, "device-transfer")
        assertThat(transfer.map { it.first to it.second }.toSet()).containsExactly("include" to "database")
        val legacy = rules(R.xml.backup_rules, "full-backup-content")
        assertThat(legacy.filter { it.first == "exclude" }.map { it.second }).containsAtLeast("sharedpref", "device_sharedpref")
        assertThat(legacy.filter { it.first == "include" }).isEmpty()
    }

    /** (tag, domain, path) for every rule inside [section]. */
    private fun rules(xml: Int, section: String): List<Triple<String, String, String>> {
        val parser = context.resources.getXml(xml)
        val found = mutableListOf<Triple<String, String, String>>()
        var inside = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when {
                parser.eventType == XmlPullParser.START_TAG && parser.name == section -> inside = true
                parser.eventType == XmlPullParser.END_TAG && parser.name == section -> inside = false
                parser.eventType == XmlPullParser.START_TAG && inside ->
                    found += Triple(parser.name, parser.getAttributeValue(null, "domain"), parser.getAttributeValue(null, "path"))
            }
        }
        return found
    }

    @Test fun `an unreadable file falls back to the defaults`() {
        context.getSharedPreferences("row_facts", Context.MODE_PRIVATE).edit().putString("state", "{not json").commit()
        assertThat(preferenceRowFacts(context).state.value).isEqualTo(RowFactsChoices())
    }

    @Test fun `the lookup opt-in is observable`() {
        val store = PreferenceSettingsStore(context)
        assertThat(store.scanLookup.value).isFalse()
        store.setScanLookupEnabled(true)
        assertThat(store.scanLookup.value).isTrue()
        assertThat(PreferenceSettingsStore(context).scanLookup.value).isTrue()
    }
}

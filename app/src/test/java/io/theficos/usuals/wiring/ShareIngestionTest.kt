package io.theficos.usuals.wiring

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.usuals.STATE_SHARE_CONSUMED
import io.theficos.usuals.pendingShare
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sharing a recipe in, and turning the phone sideways afterwards.
 *
 * The activity is handed the same `ACTION_SEND` intent on every recreation, and
 * a rotation, a locale change or a restore from Recents all count. Reading it
 * twice used to merge the same items into the same rows a second time, and the
 * repository sums quantities, so two litres of milk became four with nothing on
 * screen to say so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShareIngestionTest {

    private fun share(text: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

    private fun recreation(consumed: Boolean): Bundle =
        Bundle().apply { putBoolean(STATE_SHARE_CONSUMED, consumed) }

    @Test
    fun readsTextAndConnectIntentOnceAcrossRecreation() {
        data class Case(val label: String, val intent: Intent?, val saved: Bundle?, val expected: String?)

        val shopping = "2 litros de leche y 3 tomates"
        val note = "Shopping\n☐ milk\n☑ bought bread"
        val connect = "usuals://connect#synthetic-code"
        fun link(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        val cases = buildList {
            add(Case("fresh text", share(shopping), null, shopping))
            for (mime in listOf("text/plain", "text/markdown", "text/x-markdown")) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_TEXT, SpannableString(note))
                }
                add(Case("fresh $mime", intent, null, note))
                add(Case("consumed $mime", intent, recreation(consumed = true), null))
            }
            add(Case("rotation after consumption", share(shopping), recreation(consumed = true), null))
            add(Case("rotation before consumption", share("pan"), recreation(consumed = false), "pan"))
            add(Case("fresh connect URI", link(connect), null, connect))
            add(Case("consumed connect URI", link(connect), recreation(consumed = true), null))
            add(Case("join URI", link("usuals://join#synthetic-invitation"), null, null))
            add(Case("ordinary HTTPS URI", link("https://example.test/recipe"), null, null))
            add(Case("ordinary launch", Intent(Intent.ACTION_MAIN), null, null))
            add(Case("no intent", null, null, null))
            val image = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_TEXT, "leche")
            }
            add(Case("image with text", image, null, null))
            add(Case("blank text", share("   "), null, null))
        }
        cases.forEach { case ->
            assertWithMessage(case.label).that(pendingShare(case.intent, case.saved)).isEqualTo(case.expected)
        }
    }
}

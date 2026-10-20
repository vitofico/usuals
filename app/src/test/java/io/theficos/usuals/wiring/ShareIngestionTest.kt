package io.theficos.usuals.wiring

import android.content.Intent
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
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
    fun a_fresh_share_is_ingested() {
        assertThat(pendingShare(share("2 litros de leche y 3 tomates"), null))
            .isEqualTo("2 litros de leche y 3 tomates")
    }

    @Test
    fun a_rotation_after_the_list_swallowed_it_ingests_nothing() {
        val intent = share("2 litros de leche y 3 tomates")

        assertThat(pendingShare(intent, recreation(consumed = true))).isNull()
    }

    /**
     * The other half of the rule, and the reason a bare
     * `savedInstanceState != null` guard is not enough: turning the phone
     * before the list has read the text must not lose it.
     */
    @Test
    fun a_rotation_before_the_list_swallowed_it_still_ingests_it_once() {
        val intent = share("pan")

        assertThat(pendingShare(intent, recreation(consumed = false))).isEqualTo("pan")
    }

    @Test
    fun an_invitation_deep_link_is_consumed_once() {
        val uri = "usuals://join#synthetic-invitation"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
        assertThat(pendingShare(intent, null)).isEqualTo(uri)
        assertThat(pendingShare(intent, recreation(consumed = true))).isNull()
        assertThat(pendingShare(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.test/recipe")), null)).isNull()
    }

    @Test
    fun an_ordinary_launch_shares_nothing() {
        assertThat(pendingShare(Intent(Intent.ACTION_MAIN), null)).isNull()
        assertThat(pendingShare(null, null)).isNull()
    }

    @Test
    fun a_share_that_is_not_text_is_not_read() {
        val image = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_TEXT, "leche")
        }

        assertThat(pendingShare(image, null)).isNull()
    }

    @Test
    fun a_blank_share_is_not_a_share() {
        assertThat(pendingShare(share("   "), null)).isNull()
    }
}

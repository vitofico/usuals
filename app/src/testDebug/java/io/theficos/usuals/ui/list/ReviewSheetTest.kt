package io.theficos.usuals.ui.list

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.theficos.usuals.smart.RepeatPlanner
import io.theficos.usuals.smart.ReviewCandidate
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.google.common.truth.Truth.assertThat

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewSheetTest {
    @get:Rule val compose = createComposeRule()
    private val candidate = ReviewCandidate("milk", "milk", 0, 5, 8)
    private fun show(session: ReviewSession, add: () -> Unit = {}, skip: () -> Unit = {}, mute: () -> Unit = {}) {
        compose.setContent { UsualsTheme { ReviewSheet(session, {}, add, skip, {}, {}, mute) } }
    }

    @Test fun narrow_sheet_exposes_facts_actions_and_existing_hide_choice() {
        var adds = 0
        var skips = 0
        var mutes = 0
        show(ReviewSession("list", listOf(candidate), startedAt = 9 * RepeatPlanner.DAY_MILLIS),
            add = { adds++ }, skip = { skips++ }, mute = { mutes++ })
        compose.onNodeWithText("Bought 9 days ago").assertIsDisplayed()
        compose.onNodeWithText("In 5 of your last 8 shops").assertIsDisplayed()
        compose.onNodeWithTag("review_add").assertIsDisplayed().performClick()
        compose.onNodeWithTag("review_skip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("review_options").performClick()
        compose.onNodeWithText("Don’t suggest again").performClick()
        assertThat(adds).isEqualTo(1)
        assertThat(skips).isEqualTo(1)
        assertThat(mutes).isEqualTo(1)
    }

    @Test fun busy_sheet_disables_choices_and_undo() {
        show(ReviewSession("list", listOf(candidate), startedAt = RepeatPlanner.DAY_MILLIS,
            busy = true, last = ReviewChoice(0)))
        compose.onNodeWithTag("review_add").assertIsNotEnabled()
        compose.onNodeWithTag("review_skip").assertIsNotEnabled()
        compose.onNodeWithTag("review_undo").performScrollTo().assertIsNotEnabled()
    }

    @Test fun completion_offers_more_and_keeps_safe_undo_reachable() {
        show(ReviewSession("list", List(11) { candidate.copy(key = "$it") }, cursor = 10,
            added = 3, last = ReviewChoice(9)))
        compose.onNodeWithText("3 items added").assertIsDisplayed()
        compose.onNodeWithText("Back to list").assertIsDisplayed()
        compose.onNodeWithTag("review_more").assertIsDisplayed()
        compose.onNodeWithTag("review_undo").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("review_add").assertDoesNotExist()
    }
}

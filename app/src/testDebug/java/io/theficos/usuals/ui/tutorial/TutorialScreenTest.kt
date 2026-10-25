package io.theficos.usuals.ui.tutorial

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TutorialScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun review_step_survives_recreation_and_back_then_finish_stays_local() {
        val restoration = StateRestorationTester(compose)
        val finishes = mutableListOf<Boolean>()
        restoration.setContent { UsualsTheme { TutorialScreen { finishes += it } } }
        repeat(4) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("5 of 6").assertIsDisplayed()
        compose.onNodeWithText("Review your usuals").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Up to 10 per round. Close whenever you like; additions stay. Undo last choice reverses your last action.")
            .performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Review your usuals").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Shop, then save the memory.").performScrollTo().assertIsDisplayed()
        repeat(2) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("6 of 6").assertIsDisplayed()
        compose.onNodeWithText("Start my list").assertIsDisplayed().performClick()
        assertThat(finishes).containsExactly(false)
    }

    @Test fun account_setup_is_optional_and_skip_works_from_the_review_step() {
        val finishes = mutableListOf<Boolean>()
        compose.setContent { UsualsTheme { TutorialScreen { finishes += it } } }
        compose.onNodeWithText("Set up account").assertDoesNotExist()
        repeat(4) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("Skip tour").assertIsDisplayed().performClick()
        assertThat(finishes).containsExactly(false)
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Set up account").assertIsDisplayed().performClick()
        assertThat(finishes).containsExactly(false, true).inOrder()
    }
}

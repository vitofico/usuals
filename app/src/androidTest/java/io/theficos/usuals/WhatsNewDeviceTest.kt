package io.theficos.usuals

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class WhatsNewDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("whatsNewQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }

    @Test fun settingsOpensWhatsNewAndFindsCompactMode() {
        fun text(id: Int) = compose.activity.getString(id)
        fun click(id: Int) = compose.onNodeWithText(text(id)).performScrollTo().performClick()
        val unread = hasText(text(R.string.whats_new_title)) and
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text(R.string.whats_new_unread))

        compose.onNodeWithText(text(R.string.tutorial_skip)).performClick()
        compose.onNodeWithContentDescription(text(R.string.list_more)).performClick()
        click(R.string.open_settings)
        compose.onNode(unread).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.whats_new_compact_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.settings_back)).performClick()
        compose.onNode(unread).assertDoesNotExist()

        click(R.string.whats_new_title)
        click(R.string.whats_new_compact_action)
        compose.onNodeWithText(text(R.string.whats_new_badge)).performScrollTo().assertIsDisplayed()
        click(R.string.compact_mode)
        compose.onNodeWithContentDescription(text(R.string.list_more)).performClick()
        compose.onNodeWithText(text(R.string.whats_new_badge)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.compact_mode)).assertIsDisplayed()
    }
}

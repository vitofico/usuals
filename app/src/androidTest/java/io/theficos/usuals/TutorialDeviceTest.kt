package io.theficos.usuals

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.di.ServiceLocator
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Opt-in first-run QA on a disposable install, never on a person's shopping data. */
class TutorialDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("tourQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }

    @Test fun firstRunTourFinishesAndReopensFromSettings() {
        fun text(id: Int) = compose.activity.getString(id)
        fun click(id: Int, scroll: Boolean = false) {
            val node = compose.onNodeWithText(text(id))
            if (scroll) node.performScrollTo()
            node.assertIsDisplayed().performClick()
        }
        fun screenshot(name: String) {
            compose.waitForIdle()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(250, 5_000)
            val bitmap = requireNotNull(automation.takeScreenshot())
            try {
                File(compose.activity.cacheDir, name).outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        assertFalse(ServiceLocator.tutorialPreferences.completed.value)
        val bodies = listOf(R.string.tutorial_welcome_body, R.string.tutorial_capture_body,
            R.string.tutorial_scan_body, R.string.tutorial_shop_body, R.string.tutorial_review_body,
            R.string.tutorial_share_body)
        bodies.forEachIndexed { page, body ->
            compose.onNodeWithText(compose.activity.getString(R.string.tutorial_step, page + 1, bodies.size)).assertIsDisplayed()
            compose.onNodeWithText(text(body)).performScrollTo().assertIsDisplayed()
            if (page == 3) screenshot("tour-shopping.png")
            if (page == 4) {
                compose.onNodeWithText(text(R.string.review_title)).performScrollTo().assertIsDisplayed()
                screenshot("tour-review.png")
                compose.onNodeWithText(text(R.string.tutorial_review_hint)).performScrollTo().assertIsDisplayed()
                screenshot("tour-review-detail.png")
            }
            if (page < bodies.lastIndex) click(R.string.tutorial_next)
        }
        click(R.string.tutorial_finish)
        compose.waitUntil(5_000) { ServiceLocator.tutorialPreferences.completed.value }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.tutorial_skip)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.list_more)).performClick()
        click(R.string.open_settings, scroll = true)
        click(R.string.settings_help, scroll = true)
        click(R.string.tutorial_replay, scroll = true)
        compose.onNodeWithText(text(R.string.tutorial_welcome_body)).performScrollTo().assertIsDisplayed()
        click(R.string.tutorial_next)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.tutorial_capture_body)).performScrollTo().assertIsDisplayed()
        click(R.string.tutorial_skip)
        compose.onNodeWithText(text(R.string.tutorial_skip)).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.list_more)).performClick()
        click(R.string.open_settings, scroll = true)
        click(R.string.settings_help, scroll = true)
        click(R.string.tutorial_replay, scroll = true)
        compose.onNodeWithText(text(R.string.tutorial_welcome_body)).performScrollTo().assertIsDisplayed()
        click(R.string.tutorial_skip)
    }
}

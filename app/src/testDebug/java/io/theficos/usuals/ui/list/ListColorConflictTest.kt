package io.theficos.usuals.ui.list

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class ListColorConflictTest {
    @get:Rule val compose = createComposeRule()
    @Test fun staleColorChoicesShowTheCurrentAndRequestedLocalizedColors() {
        compose.setContent { UsualsTheme {
            ConflictDetails(ListConflict("conflict", null, mapOf("color" to "rose"), mapOf("color" to null), "list_color"))
        } }
        compose.onNodeWithText("List color").assertIsDisplayed()
        compose.onNodeWithText("Current: Rose").assertIsDisplayed()
        compose.onNodeWithText("Requested: Automatic").assertIsDisplayed()
    }
}

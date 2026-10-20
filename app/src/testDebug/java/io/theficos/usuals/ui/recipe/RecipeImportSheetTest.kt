package io.theficos.usuals.ui.recipe

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class RecipeImportSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `close dismisses a recipe while its page is loading`() {
        var visible by mutableStateOf(true)
        compose.setContent {
            UsualsTheme {
                if (visible) RecipeImportSheet(RecipeUiState(url = "https://example.org/slow", busy = true),
                    onUrl = {}, onRead = {}, onModel = {}, onToggle = {}, onAdd = {}, onDismiss = { visible = false })
            }
        }
        compose.onNodeWithText("Close").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Import list or recipe").assertDoesNotExist()
    }
    @Test fun `Markdown and photo both require an explicit review action`() {
        var state by mutableStateOf(RecipeUiState(source = RecipeSource.MARKDOWN, markdown = "- milk"))
        var reads = 0
        compose.setContent { UsualsTheme {
            RecipeImportSheet(state, onUrl = {}, onRead = {}, onModel = { reads++ }, onToggle = {}, onAdd = {}, onDismiss = {},
                onSource = { state = state.copy(source = it) }, onReadMarkdown = { reads++ })
        } }
        compose.onNodeWithText("Review items").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assert(reads == 1); state = state.copy(source = RecipeSource.PHOTO,
            result = RecipeLoad.Photo(io.theficos.usuals.llm.VisionChallenge.create().image)) }
        compose.onNodeWithText("Read photo with my model").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assert(reads == 1) }
        compose.onNodeWithText("Read photo with my model").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assert(reads == 2) }
    }
}

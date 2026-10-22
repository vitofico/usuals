package io.theficos.usuals.ui.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.smart.ItemMemory
import io.theficos.usuals.smart.Suggestion
import io.theficos.usuals.smart.SuggestionChoice
import io.theficos.usuals.smart.SuggestionReason
import io.theficos.usuals.ui.shopping.SavedShoppingItem
import io.theficos.usuals.ui.shopping.ShoppingToolsSheet
import io.theficos.usuals.ui.shopping.ShoppingToolsStore
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val milk = ItemMemory("milk", "milk", 2, 1_000)

    @Test fun typing_suggestions_have_accessible_add_and_hide_actions() {
        var added: ItemMemory? = null
        var muted: ItemMemory? = null
        compose.setContent {
            UsualsTheme {
                ListContent(ListUiState(capture = CaptureState.Typing("mi"), autocomplete = listOf(milk)),
                    ListActions(onAutocomplete = { added = it }, onMuteAutocomplete = { muted = it }))
            }
        }
        compose.onNodeWithText("From past shops").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add milk").performClick()
        assertThat(added).isEqualTo(milk)
        compose.onNodeWithContentDescription("Suggestion options for milk").performClick()
        compose.onNodeWithContentDescription("Don’t suggest milk again").performClick()
        assertThat(muted).isEqualTo(milk)
    }

    @Test fun hiding_a_typing_suggestion_warns_before_stopping_an_explicit_repeat() {
        var muted = 0
        compose.setContent {
            UsualsTheme {
                ListContent(ListUiState(capture = CaptureState.Typing("mi"), autocomplete = listOf(milk), repeatSuggestionKeys = setOf("milk")),
                    ListActions(onMuteAutocomplete = { muted++ }))
            }
        }
        compose.onNodeWithContentDescription("Suggestion options for milk").performClick()
        compose.onNodeWithContentDescription("Don’t suggest milk again").performClick()
        compose.onNodeWithText("Stop suggesting milk?").assertIsDisplayed()
        compose.onNodeWithText("This also stops its automatic repeat rule on this list, on this device. Past purchases stay saved. You can still type the item or add it from favourites.").assertIsDisplayed()
        assertThat(muted).isEqualTo(0)
        compose.onNodeWithText("Cancel").performClick()
        assertThat(muted).isEqualTo(0)
        compose.onNodeWithContentDescription("Suggestion options for milk").performClick()
        compose.onNodeWithContentDescription("Don’t suggest milk again").performClick()
        compose.onNodeWithText("Stop suggesting").performClick()
        assertThat(muted).isEqualTo(1)
    }

    @Test fun proactive_suggestions_use_the_same_repeat_warning() {
        var choice: SuggestionChoice? = null
        compose.setContent {
            UsualsTheme {
                ListContent(ListUiState(suggestions = listOf(Suggestion("milk", "milk", SuggestionReason.REPEAT)), repeatSuggestionKeys = setOf("milk")),
                    ListActions(onSuggestion = { _, selected -> choice = selected }))
            }
        }
        compose.onNodeWithText("Suggestions (1)").performClick()
        compose.onNodeWithContentDescription("Suggestion options for milk").performClick()
        compose.onNodeWithContentDescription("Don’t suggest milk again").performClick()
        compose.onNodeWithText("Stop suggesting milk?").assertIsDisplayed()
        assertThat(choice).isNull()
        compose.onNodeWithText("Stop suggesting").performClick()
        assertThat(choice).isEqualTo(SuggestionChoice.NEVER)
    }

    @Test fun proactive_actions_stay_reachable_in_a_narrow_card() {
        val choices = mutableListOf<SuggestionChoice>()
        compose.setContent {
            UsualsTheme {
                Box(Modifier.width(280.dp)) {
                    SuggestionRow(listOf(Suggestion("milk", "milk", SuggestionReason.STAPLE))) { _, choice -> choices += choice }
                }
            }
        }
        compose.onNodeWithText("Suggestions (1)").performClick()
        compose.onNodeWithContentDescription("Add milk").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Suggestion options for milk").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Not now milk").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Suggestion options for milk").performClick()
        compose.onNodeWithContentDescription("Don’t suggest milk again").assertIsDisplayed().performClick()
        assertThat(choices).containsExactly(SuggestionChoice.ADD, SuggestionChoice.NOT_NOW, SuggestionChoice.NEVER).inOrder()
    }

    @Test fun typing_prioritises_history_completions_over_proactive_cards() {
        compose.setContent {
            UsualsTheme {
                ListContent(ListUiState(capture = CaptureState.Typing("mi"), autocomplete = listOf(milk),
                    suggestions = listOf(Suggestion("oats", "oats", SuggestionReason.STAPLE))), ListActions())
            }
        }
        compose.onNodeWithText("From past shops").assertIsDisplayed()
        compose.onNodeWithContentDescription("Suggestion options for milk").assertIsDisplayed()
        compose.onNodeWithText("Bought in at least half your recent shops").assertDoesNotExist()
    }

    @Test fun favourites_explain_reuse_and_show_when_the_saved_item_is_already_open() {
        val favorite = SavedShoppingItem("saved", "milk", 2.0, "LITRE", store = "Market")
        val tools = ShoppingToolsStore().apply { pin(favorite) }
        var preferences by mutableStateOf(tools.state.value)
        var alreadyAdded by mutableStateOf(emptySet<String>())
        var added = 0
        compose.setContent {
            UsualsTheme {
                ShoppingToolsSheet(preferences, null,
                    update = { change -> change(tools); preferences = tools.state.value },
                    onAdd = { added++; alreadyAdded = setOf(it.id) }, onDismiss = {}, alreadyAdded = alreadyAdded)
            }
        }
        compose.onNodeWithText("Save your regular items here. Add them to any list when you need them.").assertIsDisplayed()
        compose.onNodeWithContentDescription("About favourites").performClick()
        compose.onNodeWithText("Save items here to add them to any list again, with their quantity and store. You choose when to add them. Items already open are skipped.").assertIsDisplayed()
        compose.onAllNodesWithText("Close").onLast().performClick()
        compose.onNodeWithContentDescription("Add favourite milk to this list").assertIsDisplayed().performClick()
        assertThat(added).isEqualTo(1)
        compose.onNodeWithContentDescription("milk is already on this list").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription("Options for favourite milk").performClick()
        compose.onNodeWithContentDescription("Remove milk from favourites").assertIsDisplayed().performClick()
        assertThat(tools.state.value.portable.favorites).isEmpty()
        compose.onNodeWithContentDescription("Remove milk from favourites").assertDoesNotExist()
    }
}

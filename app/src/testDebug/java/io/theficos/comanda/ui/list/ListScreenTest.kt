package io.theficos.comanda.ui.list

import io.theficos.comanda.model.Quantity
import io.theficos.comanda.model.MeasureUnit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.model.ItemId
import io.theficos.comanda.ui.theme.ComandaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The list surface, rendered.
 *
 * Robolectric rather than an emulator, so this runs in the same `dgradle test`
 * as everything else and stays honest about the copy: the locale is pinned to
 * English, which is the default resource set, so the assertions below are the
 * strings a person actually reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class ListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row(id: String, name: String, quantity: Quantity? = null, checked: Boolean = false) =
        ListRow(id = ItemId(id), name = name, quantity = quantity, checked = checked)

    // Native geometry is needed for touch inside the dock's rounded clipping.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun the_keyboard_has_a_direct_action_without_requesting_voice() {
        var typed = 0
        var voice = 0
        compose.setContent {
            ComandaTheme {
                ListContent(ListUiState(), ListActions(onType = { typed++ }, onCaptureStart = { voice++ }))
            }
        }
        compose.onNodeWithContentDescription("Type items").assertIsDisplayed().performClick()
        assertThat(typed).isEqualTo(1)
        assertThat(voice).isEqualTo(0)
    }

    @Test
    fun an_empty_list_is_an_instruction_rather_than_a_mascot() {
        compose.setContent {
            ComandaTheme { ListContent(state = ListUiState(), actions = ListActions()) }
        }

        compose.onNodeWithText("Hold the bar and say what you need.").assertIsDisplayed()
        // The band is still there and still says what to do with it.
        compose.onNodeWithText("Hold to speak").assertIsDisplayed()
    }

    @Test
    fun items_are_listed_with_their_quantities_and_the_household_above_them() {
        val state = ListUiState(
            header = ListHeader(householdName = null, itemCount = 3, contributors = listOf("Marta"), includesYou = true),
            pending = listOf(
                row("a", "Leche entera", Quantity.pieces(2)),
                row("b", "Tomates"),
                row("c", "Aceite de oliva", Quantity(0.5, MeasureUnit.LITRE)),
            ),
        )

        compose.setContent { ComandaTheme { ListContent(state = state, actions = ListActions()) } }

        compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText("Leche entera"))
        compose.onNodeWithText("Leche entera").assertIsDisplayed()
        compose.onNodeWithText("×2").assertIsDisplayed()
        compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText("Tomates"))
        compose.onNodeWithText("Tomates").assertIsDisplayed()
        compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText("Aceite de oliva"))
        compose.onNodeWithText("Aceite de oliva").assertIsDisplayed()
        compose.onNodeWithText("0.5 l").assertIsDisplayed()
        // No household yet, so the header falls back rather than showing a blank.
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("3 items · Marta, you").assertIsDisplayed()
    }

    @Test
    fun checked_items_collapse_into_a_counted_rule_and_open_on_tap() {
        var state by mutableStateOf(
            ListUiState(
                pending = listOf(row("a", "Leche entera")),
                checked = listOf(row("b", "Tomates", checked = true), row("c", "Pan de payés", checked = true)),
                checkedExpanded = false,
            ),
        )

        compose.setContent {
            ComandaTheme {
                ListContent(
                    state = state,
                    actions = ListActions(
                        onToggleCheckedSection = { state = state.copy(checkedExpanded = !state.checkedExpanded) },
                    ),
                )
            }
        }

        // Done is done: two rows become one rule, and the list stays clean.
        compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText("2 checked off"))
        compose.onNodeWithText("2 checked off").assertIsDisplayed()
        compose.onNodeWithText("Tomates").assertDoesNotExist()
        compose.onNodeWithText("Pan de payés").assertDoesNotExist()
        compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText("Leche entera"))
        compose.onNodeWithText("Leche entera").assertIsDisplayed()
        compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText("2 checked off"))

        compose.onNodeWithTag(CheckedDividerTag).performClick()

        compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText("Tomates"))
        compose.onNodeWithText("Tomates").assertIsDisplayed()
        compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText("Pan de payés"))
        compose.onNodeWithText("Pan de payés").assertIsDisplayed()
    }

    @Test
    fun what_is_being_said_grows_at_the_end_of_the_list() {
        val state = ListUiState(
            pending = listOf(row("a", "Leche entera")),
            capture = CaptureState.Listening("dos litros de"),
        )

        compose.setContent { ComandaTheme { ListContent(state = state, actions = ListActions()) } }

        compose.onNodeWithText("dos litros de").assertIsDisplayed()
        compose.onNodeWithText("Listening").assertIsDisplayed()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun voice_starts_on_press_and_stops_on_release() {
        var started = 0
        var ended = 0

        compose.setContent {
            ComandaTheme {
                ListContent(
                    state = ListUiState(),
                    actions = ListActions(
                        onCaptureStart = { started++ },
                        onCaptureEnd = { ended++ },
                    ),
                )
            }
        }

        compose.onNodeWithTag(CaptureBandTag).assertIsDisplayed().performTouchInput {
            down(center)
            advanceEventTime(200)
        }
        compose.runOnIdle {
            assertThat(started).isEqualTo(1)
            assertThat(ended).isEqualTo(0)
        }
        compose.onNodeWithTag(CaptureBandTag).performTouchInput { up() }
        compose.runOnIdle { assertThat(ended).isEqualTo(1) }
    }

    @Test
    fun a_phone_without_dictation_types_in_the_same_place_with_no_dialog() {
        val state = ListUiState(
            capture = CaptureState.Typing(draft = "", becauseSpeechIsUnavailable = true),
        )

        compose.setContent { ComandaTheme { ListContent(state = state, actions = ListActions()) } }

        compose.onNodeWithTag(DraftFieldTag).assertIsDisplayed()
        compose.onNodeWithText("Type what you need").assertIsDisplayed()
        compose.onNodeWithText("Dictation is not available on this phone, type what you need.")
            .assertIsDisplayed()
        // The band is still the band: no modal took the screen over.
        compose.onNodeWithText("Hold to speak").assertDoesNotExist()
    }
}

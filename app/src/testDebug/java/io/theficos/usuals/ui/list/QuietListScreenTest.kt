package io.theficos.usuals.ui.list

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.smart.ItemMemory
import io.theficos.usuals.ui.shopping.ShoppingPreferences
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
class QuietListScreenTest {
    @get:Rule val compose = createComposeRule()
    private val row = ListRow(ItemId("milk"), "Milk")

    @Test fun secondary_features_are_labelled_in_the_menu_instead_of_permanent_toolbars() {
        val opened = mutableListOf<String>()
        compose.setContent { UsualsTheme {
            ListContent(ListUiState(header = ListHeader("Groceries", 1), pending = listOf(row)), ListActions(
                onOpenRecipe = { opened += "import" }, onOpenHistory = { opened += "history" },
                onShoppingTools = { opened += "favourites" }, onShoppingMode = { opened += "shopping" },
                onShareList = { opened += "share" }, onOpenSettings = { opened += "settings" }))
        } }
        compose.onNodeWithText("Milk").assertIsDisplayed()
        compose.onNodeWithText("Start shopping").assertDoesNotExist()
        compose.onNodeWithContentDescription("Share").assertDoesNotExist()
        for ((label, expected) in listOf("Import recipe or list" to "import", "History" to "history", "Favourites" to "favourites",
            "Start shopping" to "shopping", "Share" to "share", "Settings" to "settings")) {
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText(label).performScrollTo().performClick()
            assertThat(opened.last()).isEqualTo(expected)
        }
    }

    @Test fun review_is_a_quiet_single_action() {
        var opened = 0
        compose.setContent {
            ListContent(ListUiState(pending = listOf(row), reviewCandidates = listOf(io.theficos.usuals.smart.ReviewCandidate("oats", "oats", 0, 2, 2))),
                ListActions(onOpenReview = { opened++ }))
        }
        compose.onNodeWithText("Review your usuals").assertIsDisplayed().performClick()
        assertThat(opened).isEqualTo(1)
        compose.onNodeWithText("oats").assertDoesNotExist()
    }

    @Test fun typing_keeps_capture_and_memory_visible_without_unrelated_controls() {
        compose.setContent { UsualsTheme {
            ListContent(ListUiState(header = ListHeader("Groceries", 1), pending = listOf(row),
                stores = listOf("Market", "Bakery"), capture = CaptureState.Typing("mi"),
                autocomplete = listOf(ItemMemory("milk", "milk", 2, 1_000)),
                reviewCandidates = listOf(io.theficos.usuals.smart.ReviewCandidate("oats", "oats", 0, 2, 2))), ListActions())
        } }
        compose.onNodeWithTag(DraftFieldTag).assertIsDisplayed()
        compose.onNodeWithText("1 item").assertDoesNotExist()
        compose.onNodeWithText("From past shops").assertIsDisplayed()
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
        compose.onNodeWithText("Store: All stores").assertDoesNotExist()
        compose.onNodeWithText("Review your usuals").assertDoesNotExist()
        compose.onNodeWithText("Add your first item").assertDoesNotExist()
    }

    @Test fun one_store_selector_filters_and_clears_the_selection() {
        val selected = mutableStateOf<String?>(null)
        compose.setContent { UsualsTheme {
            ListContent(ListUiState(pending = listOf(row), stores = listOf("Market", "Bakery"), selectedStore = selected.value),
                ListActions(onStoreFilter = { selected.value = it }))
        } }
        compose.onNodeWithContentDescription("Store: All stores").performClick()
        compose.onNodeWithText("Market").performClick()
        assertThat(selected.value).isEqualTo("Market")
        compose.onNodeWithContentDescription("Store: Market").performClick()
        compose.onNodeWithText("All stores").performClick()
        assertThat(selected.value).isNull()
    }

    @Test fun shopping_focuses_on_rows_and_keeps_finish_next_to_checked_items() {
        var finished = 0
        var left = 0
        compose.setContent { UsualsTheme {
            ListContent(ListUiState(pending = listOf(row), checked = listOf(ListRow(ItemId("bread"), "Bread", checked = true)),
                personal = ShoppingPreferences(mode = true), reviewCandidates = listOf(io.theficos.usuals.smart.ReviewCandidate("oats", "oats", 0, 2, 2))),
                ListActions(shoppingMode = true, onFinishShop = { finished++ }, onShoppingMode = { left++ }))
        } }
        compose.onNodeWithText("Shopping mode").assertIsDisplayed()
        compose.onNodeWithText("Review your usuals").assertDoesNotExist()
        compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText("Finish shop"))
        compose.onNodeWithText("Finish shop").performClick()
        assertThat(finished).isEqualTo(1)
        compose.onNodeWithText("Leave shopping mode").performClick()
        assertThat(left).isEqualTo(1)
    }

    @Config(sdk = [33], qualifiers = "en-w320dp-h640dp")
    @Test fun large_text_shopping_controls_wrap_and_leave_groceries_reachable() {
        var left = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                UsualsTheme { ListContent(ListUiState(header = ListHeader("Groceries", 1), pending = listOf(row),
                    personal = ShoppingPreferences(mode = true)), ListActions(shoppingMode = true, onShoppingMode = { left = true })) }
            }
        }
        compose.onNodeWithText("Shopping mode").assertIsDisplayed()
        compose.onNodeWithTag(ShoppingListTag).performScrollToNode(hasText("Milk"))
        compose.onNodeWithText("Milk").assertIsDisplayed()
        compose.onNodeWithText("Leave shopping mode").assertIsDisplayed().performClick()
        assertThat(left).isTrue()
    }

    // Default width avoids Robolectric's text-field dialog loop: https://github.com/robolectric/robolectric/issues/8460
    @Config(sdk = [33], qualifiers = "en")
    @Test fun picker_keeps_new_list_fixed_and_archived_lists_disclosed() {
        var created: String? = null
        val current = NamedList("home", "Home", false)
        val directory = ListDirectory("home", listOf(current) + (1..15).map { NamedList("list-$it", "List $it", false) } +
            NamedList("old", "Old camping list", false, archived = true))
        compose.setContent { UsualsTheme { ListContent(ListUiState(directory = directory), ListActions(onCreateList = { created = it })) } }
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("New list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Selected").assertExists()
        compose.onNodeWithText("Old camping list").assertDoesNotExist()
        compose.onNodeWithText("Archived (1)").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
        compose.onNodeWithText("Archived (1)").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        compose.onNodeWithText("Archived (1)").performClick()
        compose.onNodeWithText("Archived (1)").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithText("Old camping list").assertDoesNotExist()
        compose.onNodeWithText("Archived (1)").performClick()
        compose.onNodeWithText("Old camping list").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New list").assertIsDisplayed().performClick()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNodeWithText("List name").assertIsDisplayed().performTextInput("Weekend groceries")
        compose.onNodeWithText("Create").assertIsEnabled().performClick()
        assertThat(created).isEqualTo("Weekend groceries")
        compose.onNodeWithText("List name").assertDoesNotExist()
    }

    @Config(sdk = [33], qualifiers = "en-w320dp-h640dp")
    @Test fun narrow_large_text_keeps_capture_targets_and_the_list_reachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                UsualsTheme { ListContent(ListUiState(header = ListHeader("A long household shopping list title", 1), pending = listOf(row)), ListActions()) }
            }
        }
        compose.onNodeWithText("Milk").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add items").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(CaptureBandTag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Scan a barcode").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
}

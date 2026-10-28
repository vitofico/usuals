package io.theficos.usuals.ui.list

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.smart.Category
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class EditItemSheetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `the sheet saves the entered amount and explicit category and rule`() {
        var saved: ItemEdit? = null
        val row = ListRow(ItemId("milk"), "Milk", Quantity(2.0, MeasureUnit.LITRE), note = "for the cake", rawName = "milk", category = Category.DAIRY)
        compose.setContent { UsualsTheme { EditItemSheet(row, false, { saved = it }, {}, {}) } }
        compose.onNodeWithText("for the cake").assertExists()
        compose.onNodeWithText("Name").performTextReplacement("oat milk")
        compose.onNodeWithText("Quantity").performTextReplacement("1")
        compose.onNodeWithText("Note (optional)").performTextReplacement(" lactose free ")
        compose.onNodeWithText("Repeat every (days)").performScrollTo().performTextReplacement("21")
        compose.onNodeWithText("Store tag (optional)").performScrollTo().performTextReplacement("greengrocer")
        compose.onNodeWithText("Category: Automatic").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Household").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Save").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertThat(saved!!.name).isEqualTo("oat milk")
        assertThat(saved!!.quantity).isEqualTo(Quantity(1.0, MeasureUnit.LITRE))
        assertThat(saved!!.category).isEqualTo(Category.HOUSEHOLD)
        assertThat(saved!!.repeatEveryDays).isEqualTo(21)
        assertThat(saved!!.store).isEqualTo("greengrocer")
        assertThat(saved!!.note).isEqualTo("lactose free")
    }

    @Test fun `saving unrelated changes preserves the complete stored amount`() {
        var saved: ItemEdit? = null
        val quantity = Quantity(1.23456, MeasureUnit.KILOGRAM)
        compose.setContent { UsualsTheme { EditItemSheet(ListRow(ItemId("flour"), "Flour", quantity), false, { saved = it }, {}, {}) } }
        compose.onNodeWithText("Name").performTextReplacement("bread flour")
        compose.onNodeWithText("Save").performClick()
        assertThat(saved!!.quantity).isEqualTo(quantity)
    }

    @Test fun `saving a tiny amount unchanged does not round it to zero`() {
        var saved: ItemEdit? = null
        val quantity = Quantity(0.0004, MeasureUnit.KILOGRAM)
        compose.setContent { UsualsTheme { EditItemSheet(ListRow(ItemId("spice"), "Spice", quantity), false, { saved = it }, {}, {}) } }
        compose.onNodeWithText("Save").performClick()
        assertThat(saved!!.quantity).isEqualTo(quantity)
    }

    @Test @Config(qualifiers = "ar-w411dp-h891dp")
    fun `a quantity remains editable when the system uses different decimal symbols`() {
        var saved: ItemEdit? = null
        val quantity = Quantity(1.5, MeasureUnit.LITRE)
        compose.setContent { UsualsTheme { EditItemSheet(ListRow(ItemId("milk"), "Milk", quantity), false, { saved = it }, {}, {}) } }
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertThat(saved!!.quantity).isEqualTo(quantity)
    }

    @Test fun `decimal comma input remains accepted`() {
        var saved: ItemEdit? = null
        compose.setContent { UsualsTheme { EditItemSheet(ListRow(ItemId("milk"), "Milk"), false, { saved = it }, {}, {}) } }
        compose.onNodeWithText("Quantity").performTextReplacement("1,23456")
        compose.onNodeWithText("Save").performClick()
        assertThat(saved!!.quantity).isEqualTo(Quantity(1.23456, MeasureUnit.PIECE))
    }

    @Test fun `clearing the note saves none`() {
        var saved: ItemEdit? = null
        compose.setContent { UsualsTheme { EditItemSheet(ListRow(ItemId("milk"), "Milk", note = "for the cake", rawName = "milk"), false, { saved = it }, {}, {}) } }
        compose.onNodeWithText("Note (optional)").performTextReplacement("  ")
        compose.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertThat(saved!!.note).isNull()
    }

    @Test fun `a simple item starts with core fields and optional details stay behind their label`() {
        compose.setContent { UsualsTheme { EditItemSheet(ListRow(ItemId("milk"), "Milk"), false, {}, {}, {}) } }
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithText("Quantity").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Note (optional)").assertDoesNotExist()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Note (optional)").assertExists()
    }

    @Test fun `save and cancel stay visible with large text and expanded details`() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                UsualsTheme { EditItemSheet(ListRow(ItemId("milk"), "Milk", note = "for the cake", store = "Market"), false, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Repeat every (days)").performScrollTo()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun `removing an item requires confirming the named item`() {
        var removed: ItemId? = null
        compose.setContent { UsualsTheme { EditItemSheet(ListRow(ItemId("milk"), "Milk"), false, {}, {}, { removed = it }) } }
        compose.onNodeWithText("Remove").assertDoesNotExist()
        compose.onNodeWithText("More actions").performScrollTo().performClick()
        compose.onNodeWithText("Remove").performScrollTo().performClick()
        compose.onNodeWithText("Remove Milk?").assertExists()
        assertThat(removed).isNull()
        compose.onAllNodesWithText("Remove").onLast().performClick()
        assertThat(removed).isEqualTo(ItemId("milk"))
    }

    @Test fun `long press edits without checking or deleting`() {
        var edited: ItemId? = null
        var checked = false
        var removed = false
        compose.setContent { UsualsTheme { ListContent(
            ListUiState(pending = listOf(ListRow(ItemId("milk"), "Milk", category = Category.DAIRY))),
            ListActions(onEditItem = { edited = it }, onCheckedChange = { _, _ -> checked = true }, onConfirmRemove = { removed = true }),
        ) } }
        compose.onNodeWithText("Milk").performTouchInput { longClick() }
        assertThat(edited).isEqualTo(ItemId("milk"))
        assertThat(checked).isFalse()
        assertThat(removed).isFalse()
    }
}

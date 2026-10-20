package io.theficos.usuals.ui.list

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
        val row = ListRow(ItemId("milk"), "Milk", Quantity(2.0, MeasureUnit.LITRE), rawName = "milk", category = Category.DAIRY)
        compose.setContent { UsualsTheme { EditItemSheet(row, false, { saved = it }, {}, {}) } }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("oat milk")
        fields[1].performTextReplacement("1")
        fields[2].performTextReplacement("21")
        fields[3].performTextReplacement("greengrocer")
        compose.onNodeWithText("Automatic").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Household").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("Save").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertThat(saved!!.name).isEqualTo("oat milk")
        assertThat(saved!!.quantity).isEqualTo(Quantity(1.0, MeasureUnit.LITRE))
        assertThat(saved!!.category).isEqualTo(Category.HOUSEHOLD)
        assertThat(saved!!.repeatEveryDays).isEqualTo(21)
        assertThat(saved!!.store).isEqualTo("greengrocer")
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

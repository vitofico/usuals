package io.theficos.usuals.ui.list

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class ItemTransferUiTest {
    @get:Rule val compose = createComposeRule()
    private val source = NamedList("source", "Groceries", false)
    private val directory = ListDirectory("source", listOf(source, NamedList("target", "Weekend", false), NamedList("archive", "Old list", false, archived = true)))
    private val row = ListRow(ItemId("milk"), "Milk")

    @Test fun `copy review names both lists and excludes archived destinations`() {
        var requested: Pair<String, Boolean>? = null
        compose.setContent { UsualsTheme {
            TransferItemDialog(row, source, directory, false, { id, move -> requested = id to move }, {})
        } }
        compose.onNodeWithText("Weekend").assertExists()
        compose.onNodeWithText("Old list").assertDoesNotExist()
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Copy “Milk” from “Groceries” to “Weekend”?").assertExists()
        compose.onAllNodesWithText("Copy").onLast().performClick()
        assertThat(requested).isEqualTo("target" to false)
    }

    @Test fun `no destination explains how to get one and disables confirmation`() {
        compose.setContent { UsualsTheme {
            TransferItemDialog(row, source, directory.copy(lists = listOf(source)), false, { _, _ -> error("no target") }, {})
        } }
        compose.onNodeWithText("Create or restore another list to move or copy this item.").assertExists()
        compose.onAllNodesWithText("Move").onLast().assertIsNotEnabled()
    }

    @Test fun `editor requires saving changed details before transfer`() {
        compose.setContent { UsualsTheme {
            EditItemSheet(row, false, {}, {}, {}, onTransfer = {})
        } }
        compose.onNodeWithText("More actions").performScrollTo().performClick()
        compose.onNodeWithText("Move or copy to another list").performScrollTo().assertIsEnabled()
        compose.onNode(hasSetTextAction() and hasText("Milk")).performTextReplacement("Oat milk")
        compose.onNodeWithText("Move or copy to another list").assertIsNotEnabled()
        compose.onNodeWithText("Save your changes before moving or copying this item.").assertExists()
    }

    @Test fun `invalid unsaved repeat text also disables transfer`() {
        compose.setContent { UsualsTheme {
            EditItemSheet(row, false, {}, {}, {}, onTransfer = {})
        } }
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Repeat every (days)").performScrollTo().performTextReplacement("999999999999999")
        compose.onNodeWithText("More actions").performScrollTo().performClick()
        compose.onNodeWithText("Move or copy to another list").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `an untouched precise quantity does not require a rounding edit before transfer`() {
        var transferred = false
        compose.setContent { UsualsTheme {
            EditItemSheet(row.copy(quantity = Quantity(1.2345, MeasureUnit.LITRE)), false, {}, {}, {}, onTransfer = { transferred = true })
        } }
        compose.onNodeWithText("More actions").performScrollTo().performClick()
        compose.onNodeWithText("Move or copy to another list").performScrollTo().assertIsEnabled().performClick()
        assertThat(transferred).isTrue()
    }
}

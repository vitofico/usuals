package io.theficos.usuals.ui.list

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.NutriScore
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Product facts as a row draws them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class RowFactsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val milk = Gtin.parse("4006381333931")!!

    // Native geometry is needed for touch inside the row's rounded clipping.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun `a known grade is a chip that opens product details instead of checking the row`() {
        val graded = ListRow(ItemId("a"), "Milk", gtin = milk, grade = NutriScore.B)
        val opened = mutableListOf<ListRow>()
        var checks = 0
        compose.setContent {
            UsualsTheme {
                ListContent(ListUiState(pending = listOf(graded, ListRow(ItemId("b"), "Bread"))),
                    ListActions(onProductInfo = { opened += it }, onCheckedChange = { _, _ -> checks++ }))
            }
        }
        compose.onNodeWithContentDescription("Nutri-Score B").performClick()
        assertThat(opened).containsExactly(graded)
        assertThat(checks).isEqualTo(0)
        // The bare row draws no chip at all, not even an unknown one.
        compose.onAllNodesWithContentDescription("Nutri-Score", substring = true).assertCountEquals(1)
    }

    @Test fun `a checked row keeps its chip`() {
        compose.setContent {
            UsualsTheme {
                ListContent(ListUiState(checked = listOf(ListRow(ItemId("a"), "Milk", checked = true, gtin = milk, grade = NutriScore.E)),
                    checkedExpanded = true), ListActions())
            }
        }
        compose.onAllNodes(hasContentDescription("Nutri-Score E")).assertCountEquals(1)
    }
}

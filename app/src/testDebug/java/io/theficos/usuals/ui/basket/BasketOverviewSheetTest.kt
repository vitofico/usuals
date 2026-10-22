package io.theficos.usuals.ui.basket

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.NovaGroup
import io.theficos.usuals.enrich.NutriScore
import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.smart.Category
import io.theficos.usuals.ui.list.ListActions
import io.theficos.usuals.ui.list.ListContent
import io.theficos.usuals.ui.list.ListHeader
import io.theficos.usuals.ui.list.ListRow
import io.theficos.usuals.ui.list.ListUiState
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class BasketOverviewSheetTest {
    @get:Rule val compose = createComposeRule()

    private var next = 0
    private fun scanned(nova: NovaGroup?, nutri: NutriScore? = null) = "item-${next++}".let {
        BasketLine(it, it, Category.PANTRY, true, ProductQuality(novaGroup = nova, nutriScore = nutri))
    }
    private fun produce(name: String) = BasketLine(name, name, Category.PRODUCE, false, null)
    private val enough = basketOverview(
        List(3) { scanned(NovaGroup.ULTRA_PROCESSED, NutriScore.A) } + List(4) { scanned(NovaGroup.PROCESSED, NutriScore.C) } +
            List(2) { scanned(NovaGroup.UNPROCESSED, NutriScore.B) } + produce("bananas") +
            BasketLine("soap", "soap", Category.PERSONAL_CARE, false, null),
    ) { it == "bananas" }
    private val sparse = basketOverview(listOf(scanned(NovaGroup.PROCESSED, NutriScore.C), produce("bread"), produce("cheese"))) { false }

    @Test fun `too little data says so calmly with coverage and how it grows`() {
        compose.setContent { UsualsTheme { BasketOverviewSheet(BasketSheetState(sparse)) {} } }
        compose.onAllNodesWithText("Not enough product data yet.").assertCountEquals(2)
        compose.onNodeWithText("Based on 1 of 3 food items.").assertExists()
        compose.onNodeWithText("Known for 1 of 3 items it applies to.").assertExists()
        compose.onNodeWithText("scan an item once", substring = true).assertExists()
        compose.onAllNodesWithText("ultra-processed", substring = true).assertCountEquals(0)
    }

    @Test fun `enough data describes the mix and counts every grade`() {
        compose.setContent { UsualsTheme { BasketOverviewSheet(BasketSheetState(enough)) {} } }
        // 3 of 10 known items.
        compose.onNodeWithText("About 3 in 10 known items are ultra-processed.").assertExists()
        compose.onNodeWithText("Unprocessed or minimally processed: 3").assertExists()
        compose.onNodeWithText("Based on 10 of 10 food items.").assertExists()
        compose.onNodeWithText("Fresh produce counted as unprocessed.").assertExists()
        for (legend in listOf("A 3", "B 2", "C 4", "D 0", "E 0")) compose.onNodeWithText(legend).assertExists()
        compose.onNodeWithText("Known for 9 of 9 items it applies to.").assertExists()
        compose.onNodeWithText("1 non-food item not counted.").performScrollTo().assertExists()
        compose.onAllNodesWithText("Not enough product data yet.").assertCountEquals(0)
    }

    @Test fun `a past trip shows the compact form`() {
        compose.setContent { UsualsTheme { BasketTripSummary(sparse) } }
        compose.onNodeWithText("Not enough product data yet.").assertExists()
        compose.onNodeWithText("Based on 1 of 3 food items.").assertExists()
        compose.onAllNodesWithText("Nutri-Score", substring = true).assertCountEquals(0)
    }

    @Test fun `the list menu offers the overview only when it is on`() {
        var opened = 0
        var actions by mutableStateOf(ListActions())
        val state = ListUiState(header = ListHeader(name = "Home", itemCount = 1), pending = listOf(ListRow(ItemId("a"), "Milk")))
        compose.setContent { UsualsTheme { ListContent(state, actions) } }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onAllNodesWithText("Basket overview").assertCountEquals(0)

        actions = ListActions(onBasketOverview = { opened++ })
        compose.onNodeWithText("Basket overview").performClick()
        compose.runOnIdle { assertThat(opened).isEqualTo(1) }
    }
}

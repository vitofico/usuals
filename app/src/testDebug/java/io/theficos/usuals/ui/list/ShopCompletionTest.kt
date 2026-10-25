package io.theficos.usuals.ui.list

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.FinishedTrip
import io.theficos.usuals.model.Trip
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShopCompletionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun shopping_completion_keeps_undo_receipt_and_wallet_reachable_with_large_text() {
        var wallet = 0
        var undo = 0
        var receipt: String? = null
        var state by mutableStateOf(ListUiState(finishedTrip = FinishedTrip(Trip("shop", 0), 4), finishedInShoppingMode = true))
        compose.setContent { val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) { UsualsTheme {
                ListContent(state.copy(personal = io.theficos.usuals.ui.shopping.ShoppingPreferences(mode = true)), ListActions(onOpenWallet = { wallet++ }, onUndoFinish = { undo++ }, onOpenReceipt = { receipt = it }))
            } }
        }
        compose.onNodeWithText("Add your first item").assertDoesNotExist()
        compose.onNodeWithText("Open Wallet").assertIsDisplayed().performClick()
        compose.onNodeWithText("Add receipt").assertIsDisplayed().performClick()
        compose.onNodeWithText("Undo").assertIsDisplayed().performClick()
        assertThat(wallet).isEqualTo(1)
        assertThat(undo).isEqualTo(1)
        assertThat(receipt).isEqualTo("shop")
        compose.runOnIdle { state = state.copy(finishedInShoppingMode = false) }
        compose.onNodeWithText("Open Wallet").assertDoesNotExist()
        compose.onNodeWithText("Add receipt").assertIsDisplayed()
    }
}

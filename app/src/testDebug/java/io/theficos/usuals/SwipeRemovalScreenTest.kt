package io.theficos.usuals

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.ui.history.FakeHistoryStore
import kotlinx.coroutines.Dispatchers
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.ui.list.*
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class SwipeRemovalScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun swipeUndoAndDismissal() {
        val models = ViewModelStore()
        val milk = item("milk", "milk").copy(quantity = Quantity.pieces(2), store = "Market")
        val bread = item("bread", "bread").copy(position = 1.0)
        val original = listOf(milk, bread)
        val store = FakeItemStore(original)
        val history = FakeHistoryStore()
        try {
            lateinit var vm: ListViewModel
            compose.runOnUiThread {
                vm = ListViewModel(
                    store, LocalUtteranceParser(), NoSpeechSource,
                    clock = { 1_700_000_000_000L }, historyStore = history,
                    compute = Dispatchers.Main.immediate,
                )
                models.put("swipe-test", vm)
            }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                    UsualsTheme { ListScreen(vm, {}, {}) }
                }
            }
            compose.waitUntil(5_000) { vm.state.value.pending.size == 2 }
            compose.onNodeWithText("Milk").performTouchInput { swipeLeft() }
            val undo = compose.activity.getString(R.string.finish_shop_undo)
            compose.onNodeWithText(undo).assertIsDisplayed()
            compose.onNodeWithText("Milk").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(original, store.current)
                assertTrue(store.removed.isEmpty())
            }
            compose.onNodeWithText(undo).performClick()
            compose.onNodeWithText("Milk").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(original, store.current)
                assertTrue(store.removed.isEmpty())
            }
            compose.onNodeWithText("Milk").performTouchInput { swipeRight() }
            compose.onNodeWithText("Bread").performTouchInput { swipeLeft() }
            compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.swipe_removed, 2, 2)).assertIsDisplayed()
            compose.onNodeWithText(undo).performClick()
            compose.onNodeWithText("Milk").assertIsDisplayed()
            compose.onNodeWithText("Bread").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(original, store.current)
                assertTrue(store.removed.isEmpty())
            }
            compose.onNodeWithText("Milk").performTouchInput { swipeLeft() }
            compose.onNodeWithText(undo).assertIsDisplayed()
            // Advance the Compose clock past the snackbar duration.
            compose.mainClock.advanceTimeBy(11_000)
            compose.waitForIdle()
            compose.waitUntil(20_000) { vm.state.value.swipeRemoval == null }
            compose.waitUntil(5_000) { vm.state.value.pending.size == 1 }
            compose.runOnIdle {
                assertEquals(listOf(bread), store.current)
                assertEquals(listOf(milk.id), store.removed)
                assertTrue(history.data.value.purchases.isEmpty())
            }
        } finally {
            compose.runOnUiThread { models.clear() }
        }
    }
}

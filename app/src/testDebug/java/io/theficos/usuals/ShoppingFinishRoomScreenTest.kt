package io.theficos.usuals

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.theficos.usuals.data.local.HistoryRepository
import io.theficos.usuals.data.local.ItemRepository
import io.theficos.usuals.data.local.UsualsDatabase
import io.theficos.usuals.di.RepositoryHistoryStore
import io.theficos.usuals.di.RepositoryItemStore
import io.theficos.usuals.di.RepositorySmartStore
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.smart.CategoryClassifier
import io.theficos.usuals.smart.HistoryRanker
import io.theficos.usuals.smart.RepeatPlanner
import io.theficos.usuals.ui.history.ScopedHistoryStore
import io.theficos.usuals.ui.list.FakeSpeechSource
import io.theficos.usuals.ui.list.ListScreen
import io.theficos.usuals.ui.list.ListViewModel
import io.theficos.usuals.ui.shopping.ShoppingToolsStore
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class ShoppingFinishRoomScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun finishUsesDisplayedVersionsAndUndoRestoresOnlyTheCompletedRow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val now = 1_700_000_000_000L
            val items = ItemRepository(db, clock = { now })
            val history = HistoryRepository(db, items, clock = { now })
            val milk = items.add(ParsedItem("Milk", Quantity.pieces(2)), store = "Market")
            val bread = items.add(ParsedItem("Bread"), store = "Market")
            val apples = items.add(ParsedItem("Apples"), store = "Market")
            val soap = items.add(ParsedItem("Soap"), store = "Other store")
            listOf(milk, bread, apples, soap).forEach { items.setChecked(it, true) }

            // Hold the view at the last delivered frame while new writes reach Room.
            val displayed = history.observeShopping().first()
            val parser = LocalUtteranceParser()
            val smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
            val persistentHistory = RepositoryHistoryStore(history, parser, smart)
            val historyStore = object : ScopedHistoryStore by persistentHistory {
                override fun observeShopping() = flowOf(displayed)
            }
            lateinit var vm: ListViewModel
            compose.runOnUiThread {
                vm = ListViewModel(
                    RepositoryItemStore(items), parser, FakeSpeechSource(available = false),
                    clock = { now }, historyStore = historyStore,
                    shoppingTools = ShoppingToolsStore().apply { mode(true) },
                )
                models.put("finish", vm)
                vm.onStoreFilter("Market")
            }
            compose.setContent { UsualsTheme { ListScreen(vm, onOpenScan = {}, onOpenSettings = {}) } }
            compose.waitUntil(10_000) { vm.state.value.checked.map { it.id }.toSet() == setOf(milk, bread, apples) }
            compose.onNodeWithTag("shopping_finish").assertIsDisplayed().assertHasClickAction()

            items.setQuantity(milk, Quantity.pieces(7))
            items.setChecked(bread, false)
            compose.onNodeWithTag("shopping_finish").performClick()
            compose.waitUntil(10_000) { vm.state.value.finishedTrip != null || vm.state.value.historyError }
            assertFalse(vm.state.value.historyError)
            assertEquals(1, vm.state.value.finishedTrip?.count)
            assertEquals(apples, history.snapshot().purchases.single().id)
            val remaining = items.items().associateBy { it.id }
            assertEquals(setOf(milk, bread, soap), remaining.keys)
            assertEquals(Quantity.pieces(7), remaining.getValue(milk).quantity)
            assertFalse(remaining.getValue(bread).checked)
            assertTrue(remaining.getValue(soap).checked)

            compose.onNodeWithText(context.getString(R.string.finish_shop_undo)).performClick()
            compose.waitUntil(10_000) { vm.state.value.finishedTrip == null }
            assertTrue(history.snapshot().purchases.isEmpty())
            val restored = items.items().associateBy { it.id }
            assertEquals(setOf(milk, bread, apples, soap), restored.keys)
            assertEquals(Quantity.pieces(7), restored.getValue(milk).quantity)
            assertFalse(restored.getValue(bread).checked)
            assertTrue(restored.getValue(soap).checked)
            assertTrue(restored.getValue(apples).checked)
        } finally {
            compose.runOnUiThread { models.clear() }
            db.close()
        }
    }
}

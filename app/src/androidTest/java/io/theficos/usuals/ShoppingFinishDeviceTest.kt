package io.theficos.usuals

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.data.local.HistoryRepository
import io.theficos.usuals.data.local.ItemRepository
import io.theficos.usuals.data.local.UsualsDatabase
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.ui.list.*
import io.theficos.usuals.ui.shopping.ShoppingPreferences
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Frozen presentation plus real Room changes exercise the footer's production callback contract. */
class ShoppingFinishDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("shoppingFinishQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }

    @Test fun footerPreservesConcurrentEditsAndFilteredRowsWithUndo() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(compose.activity, UsualsDatabase::class.java).build()
        try {
            val items = ItemRepository(db, clock = { 1_700_000_000_000L })
            val history = HistoryRepository(db, items, clock = { 1_700_000_000_000L })
            val quantityChanged = items.add(ParsedItem("Milk", Quantity.pieces(2)), store = "Market")
            val checkChanged = items.add(ParsedItem("Bread"), store = "Market")
            val unchanged = items.add(ParsedItem("Apples"), store = "Market")
            val hidden = items.add(ParsedItem("Soap"), store = "Other store")
            listOf(quantityChanged, checkChanged, unchanged, hidden).forEach { items.setChecked(it, true) }
            val frozen = items.items()
            val rows = frozen.map { ListRow(it.id, it.name, quantity = it.quantity, checked = it.checked, version = it.version, store = it.store) }
            val displayed = rows.filter { it.store == "Market" }
            var received: List<ItemVersion>? = null
            var receivedIds: Set<ItemId>? = null
            compose.runOnUiThread {
                compose.activity.setContent {
                    val density = LocalDensity.current
                    val large = InstrumentationRegistry.getArguments().getString("shoppingFinishLarge") == "true"
                    CompositionLocalProvider(LocalDensity provides if (large) Density(density.density, 1.8f) else density) {
                        UsualsTheme {
                            ListContent(ListUiState(checked = displayed, packRows = rows, selectedStore = "Market",
                                stores = listOf("Market", "Other store"), personal = ShoppingPreferences(mode = true)),
                                ListActions(onFinishShop = { fail("Footer bypassed displayed-row versions") },
                                    onFinishRows = { selected, ids ->
                                        received = selected.mapNotNull { it.version }.takeIf { it.size == selected.size }
                                        receivedIds = ids
                                    }), modifier = Modifier.widthIn(max = 320.dp))
                        }
                    }
                }
            }
            compose.onNodeWithTag("shopping_finish").assertIsDisplayed().assertHasClickAction()
            // These changes arrive after presentation but before the person presses Finish.
            items.setQuantity(quantityChanged, Quantity.pieces(7))
            items.setChecked(checkChanged, false)
            compose.onNodeWithTag("shopping_finish").performClick()
            compose.runOnIdle {
                assertEquals(displayed.map { it.version }, received)
                assertEquals(displayed.map { it.id.value }.toSet(), received!!.map { it.id }.toSet())
                assertNull(receivedIds)
            }
            val finish = requireNotNull(history.finish(receivedIds, requireNotNull(received)))
            assertEquals(1, finish.count)
            assertEquals(unchanged.value, history.snapshot().purchases.single().id.value)
            val remaining = items.items().associateBy { it.id }
            assertEquals(Quantity.pieces(7), remaining.getValue(quantityChanged).quantity)
            assertFalse(remaining.getValue(checkChanged).checked)
            assertTrue(remaining.getValue(hidden).checked)
            assertTrue(history.undo(finish.trip.id))
            assertTrue(history.snapshot().purchases.isEmpty())
            val restored = items.items().associateBy { it.id }
            assertEquals(4, restored.size)
            assertEquals(Quantity.pieces(7), restored.getValue(quantityChanged).quantity)
            assertFalse(restored.getValue(checkChanged).checked)
            assertTrue(restored.getValue(hidden).checked)
            assertTrue(restored.getValue(unchanged).checked)
        } finally { db.close() }
    }
}

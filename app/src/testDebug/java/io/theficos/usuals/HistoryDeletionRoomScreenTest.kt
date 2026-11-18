package io.theficos.usuals

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import io.theficos.usuals.data.local.*
import io.theficos.usuals.di.RepositoryHistoryStore
import io.theficos.usuals.di.RepositorySmartStore
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.smart.CategoryClassifier
import io.theficos.usuals.smart.HistoryRanker
import io.theficos.usuals.smart.RepeatPlanner
import io.theficos.usuals.ui.history.HistoryNotice
import io.theficos.usuals.ui.history.HistoryScreen
import io.theficos.usuals.ui.history.HistoryViewModel
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class HistoryDeletionRoomScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelAndConfirmDeleteChangeOnlyTheSelectedShop() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val models = ViewModelStore()
        val now = 1_800_000_000_000L
        try {
            val replica = LocalReplica(db)
            val items = ItemRepository(db, clock = { now }, replica = replica)
            val history = HistoryRepository(db, items, clock = { now }, replica = replica)
            val smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
            db.listDao().insert(ListEntity("history-delete-qa", "QA groceries"))
            ListRepository(db).select("history-delete-qa")
            history.importPast(listOf(ParsedItem("Milk"), ParsedItem("Eggs")), now - RepeatPlanner.DAY_MILLIS)
            items.add(ParsedItem("Bread"))
            val before = items.items()
            lateinit var vm: HistoryViewModel
            compose.runOnUiThread {
                vm = HistoryViewModel(RepositoryHistoryStore(history, LocalUtteranceParser(), smart), clock = { now })
                models.put("history-delete-qa", vm)
            }
            compose.setContent { UsualsTheme { HistoryScreen(vm) {} } }
            compose.waitUntil(15_000) { vm.state.value.stats.purchaseCount == 2 }
            compose.onNodeWithContentDescription("Milk").performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.history_shop_options)).performClick()
            compose.onNodeWithText(context.getString(R.string.history_delete)).performClick()
            compose.onNodeWithText(context.getString(R.string.history_delete_help)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.edit_cancel)).performClick()
            compose.onNodeWithText(context.getString(R.string.history_delete_help)).assertDoesNotExist()
            compose.onNodeWithText("Milk").assertExists()
            assertEquals(1, history.snapshot().trips.size)
            assertEquals(2, history.snapshot().purchases.size)
            assertEquals(before, items.items())
            compose.onNodeWithContentDescription(context.getString(R.string.history_shop_options)).performClick()
            compose.onNodeWithText(context.getString(R.string.history_delete)).performClick()
            compose.onNodeWithText(context.getString(R.string.history_delete)).performClick()
            compose.waitUntil(10_000) { vm.state.value.notice != null }
            assertEquals(HistoryNotice.DELETED, vm.state.value.notice)
            compose.waitUntil(10_000) { vm.state.value.stats.shopCount == 0 }
            compose.onNodeWithText(context.getString(R.string.history_deleted)).assertExists()
            assertTrue(vm.state.value.selected.isEmpty())
            assertTrue(history.snapshot().purchases.isEmpty())
            assertTrue(history.snapshot().trips.isEmpty())
            assertEquals(before, items.items())
        } finally {
            compose.runOnUiThread { models.clear() }
            db.close()
        }
    }
}

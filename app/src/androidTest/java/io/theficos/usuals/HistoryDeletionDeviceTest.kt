package io.theficos.usuals

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.data.local.*
import io.theficos.usuals.di.RepositoryHistoryStore
import io.theficos.usuals.di.RepositorySmartStore
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.smart.CategoryClassifier
import io.theficos.usuals.smart.HistoryRanker
import io.theficos.usuals.smart.RepeatPlanner
import io.theficos.usuals.ui.history.HistoryScreen
import io.theficos.usuals.ui.history.HistoryViewModel
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Synthetic history in a separate in-memory database, never the installed shopping data. */
class HistoryDeletionDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("historyDeleteQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }

    @Test fun confirmDeletePreservesTheCurrentListAndUpdatesHistory(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
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
                compose.activity.setContent { UsualsTheme { HistoryScreen(vm) {} } }
            }
            compose.waitUntil(15_000) { vm.state.value.stats.purchaseCount == 2 }
            compose.onNodeWithContentDescription("Milk").performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.history_shop_options)).performClick()
            compose.onNodeWithText(context.getString(R.string.history_delete)).performClick()
            compose.onNodeWithText(context.getString(R.string.history_delete_help)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.edit_cancel)).performClick()
            assertEquals(1, history.snapshot().trips.size)
            compose.onNodeWithContentDescription(context.getString(R.string.history_shop_options)).performClick()
            compose.onNodeWithText(context.getString(R.string.history_delete)).performClick()
            compose.waitForIdle()
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(context.cacheDir, "history-delete-confirm.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
            compose.onNodeWithText(context.getString(R.string.history_delete)).performClick()
            compose.waitUntil(15_000) { vm.state.value.stats.shopCount == 0 }
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

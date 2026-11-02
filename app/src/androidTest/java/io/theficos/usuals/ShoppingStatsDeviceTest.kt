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

/** Opt-in production history adapter and real Room, isolated from all installed shopping data. */
class ShoppingStatsDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("statsQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }

    @Test fun statsStayReadOnlyAndFollowTheSelectedList(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
        val models = ViewModelStore()
        val now = 1_800_000_000_000L
        val large = InstrumentationRegistry.getArguments().getString("statsLargeText") == "true"
        try {
            val replica = LocalReplica(db)
            val items = ItemRepository(db, clock = { now }, replica = replica)
            val history = HistoryRepository(db, items, clock = { now }, replica = replica)
            val directory = ListRepository(db)
            val parser = LocalUtteranceParser()
            val smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
            db.listDao().insert(ListEntity("stats-source", "QA groceries"))
            db.listDao().insert(ListEntity("stats-empty", "QA weekend"))
            directory.select("stats-source")
            history.importPast(listOf(ParsedItem("milk"), ParsedItem("bread")), now - 14 * RepeatPlanner.DAY_MILLIS)
            history.importPast(listOf(ParsedItem("milk")), now - 7 * RepeatPlanner.DAY_MILLIS)
            lateinit var vm: HistoryViewModel
            compose.runOnUiThread {
                vm = HistoryViewModel(RepositoryHistoryStore(history, parser, smart), clock = { now })
                models.put("shopping-stats-qa", vm)
                compose.activity.setContent { UsualsTheme(darkTheme = large) { HistoryScreen(vm) {} } }
            }
            compose.waitUntil(15_000) { vm.state.value.scope == "stats-source" && vm.state.value.stats.purchaseCount == 3 }
            compose.onNodeWithTag("stats_shops").assertDoesNotExist()
            compose.onNodeWithTag("shopping_stats_toggle").performClick()
            compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("stats_shops"))
            compose.onNodeWithTag("stats_shops").assertTextEquals(context.getString(R.string.stats_shops, 2))
            compose.onNodeWithTag("stats_purchases").assertTextEquals(context.getString(R.string.stats_purchases, 3))
            compose.onNodeWithTag("stats_different").assertTextEquals(context.getString(R.string.stats_different, 2))
            compose.onNodeWithTag("history_list").performScrollToNode(hasText(context.getString(R.string.stats_frequency, 2, 2)))
            compose.onNodeWithText(context.getString(R.string.stats_frequency, 2, 2)).assertIsDisplayed()
            compose.waitForIdle()
            instrumentation.uiAutomation.waitForIdle(250, 5_000)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(context.cacheDir, if (large) "shopping-stats-large.png" else "shopping-stats.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
            compose.runOnUiThread { vm.search("no matching purchase") }
            assertEquals(3, vm.state.value.stats.purchaseCount)
            directory.select("stats-empty")
            compose.waitUntil(15_000) { vm.state.value.scope == "stats-empty" }
            compose.onNodeWithTag("stats_shops").assertDoesNotExist()
            compose.onNodeWithTag("history_list").performScrollToNode(hasTestTag("shopping_stats_toggle"))
            compose.onNodeWithTag("shopping_stats_toggle").performClick()
            compose.onNodeWithTag("stats_shops").assertTextEquals(context.getString(R.string.stats_shops, 0))
            directory.select("stats-source")
            compose.waitUntil(15_000) { vm.state.value.scope == "stats-source" }
            compose.onNodeWithTag("stats_shops").assertDoesNotExist()
            assertEquals(3, history.snapshot().purchases.size)
            assertTrue(items.items().isEmpty())
        } finally {
            compose.runOnUiThread { models.clear() }
            db.close()
        }
    }
}

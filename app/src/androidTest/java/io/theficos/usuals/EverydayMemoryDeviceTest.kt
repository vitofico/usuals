package io.theficos.usuals

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.data.local.*
import io.theficos.usuals.di.RepositoryHistoryStore
import io.theficos.usuals.di.RepositoryItemStore
import io.theficos.usuals.di.RepositoryListDirectory
import io.theficos.usuals.di.RepositorySmartStore
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.smart.CategoryClassifier
import io.theficos.usuals.smart.HistoryRanker
import io.theficos.usuals.smart.RepeatPlanner
import io.theficos.usuals.ui.list.DraftFieldTag
import io.theficos.usuals.ui.list.ListScreen
import io.theficos.usuals.ui.list.ListViewModel
import io.theficos.usuals.ui.list.NoSpeechSource
import io.theficos.usuals.ui.shopping.ShoppingToolsStore
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/**
 * Opt-in real Room and Compose check. The Compose test-manifest host starts no production services;
 * all shopping data and personal choices are isolated in memory.
 */
class EverydayMemoryDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val enabled = TestRule { test, description ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(InstrumentationRegistry.getArguments().getString("memoryQa") == "synthetic")
                compose.apply(test, description).evaluate()
            }
        }
    }

    @Test fun favouritesTransfersAndTypingMemoryWorkOnDevice(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
        val viewModels = ViewModelStore()
        val source = "everyday-memory-source"
        val target = "everyday-memory-target"
        val now = 1_800_000_000_000L
        try {
            val replica = LocalReplica(db)
            val items = ItemRepository(db, clock = { now }, replica = replica)
            val history = HistoryRepository(db, items, clock = { now }, replica = replica)
            val directory = ListRepository(db)
            val parser = LocalUtteranceParser()
            val tools = ShoppingToolsStore()
            val smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
            db.listDao().insert(ListEntity(source, "QA groceries"))
            db.listDao().insert(ListEntity(target, "QA weekend"))
            directory.select(source)
            val oats = items.add(ParsedItem("oats"))
            items.setChecked(oats, true)
            assertNotNull(history.finish())
            val oatsKey = history.snapshot().purchases.single().key
            val barcode = requireNotNull(Gtin.parse("3017620422003"))
            val milk = items.add(ParsedItem("milk", Quantity(2.0, MeasureUnit.LITRE), "whole"), gtin = barcode, store = "QA market")
            history.savePreference(ItemPreference("milk", category = "DAIRY"))

            lateinit var vm: ListViewModel
            compose.runOnUiThread {
                compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                vm = ListViewModel(RepositoryItemStore(items), parser, NoSpeechSource, clock = { now },
                    historyStore = RepositoryHistoryStore(history, parser, smart, tools), smartStore = smart,
                    directoryStore = RepositoryListDirectory(directory, replica), shoppingTools = tools)
                viewModels.put("everyday-memory-qa", vm)
                compose.activity.setContent { UsualsTheme { ListScreen(vm, onOpenScan = {}, onOpenSettings = {}) } }
            }
            compose.waitUntil(15_000) { vm.state.value.directory.selected == source && vm.state.value.pending.any { it.id == milk } }

            fun screenshot(name: String) {
                compose.waitForIdle()
                val bitmap = compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
                try {
                    File(context.cacheDir, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
            fun editMilk() {
                val label = vm.state.value.pending.single { it.id == milk }.name
                compose.onNodeWithText(label).performTouchInput { longClick() }
                compose.waitUntil(10_000) { vm.state.value.editing?.id == milk }
            }
            fun openTransfer() {
                editMilk()
                compose.onNodeWithText(context.getString(R.string.quiet_item_actions)).performScrollTo().performClick()
                compose.onNodeWithText(context.getString(R.string.transfer_action)).performScrollTo().performClick()
                compose.waitUntil(10_000) { vm.state.value.transfer?.row?.id == milk }
                compose.onNodeWithText("QA weekend").performScrollTo().performClick()
            }
            fun dismissTransferResult() {
                compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
                compose.waitUntil(10_000) { vm.state.value.transferResult == null }
            }

            editMilk()
            compose.onNodeWithText(context.getString(R.string.shopping_pin)).performScrollTo().performClick()
            compose.waitUntil(10_000) { tools.state.value.portable.favorites.size == 1 && vm.state.value.favoritesOnList.isNotEmpty() }
            compose.runOnUiThread { vm.onDismissEdit() }
            compose.waitUntil(10_000) { vm.state.value.editing == null }
            compose.onNodeWithContentDescription(context.getString(R.string.list_more)).performClick()
            compose.onNodeWithText(context.getString(R.string.shopping_favorites)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.memory_favorite_on_list, "milk"))
                .performScrollTo().assertIsNotEnabled()
            screenshot("everyday-memory-favourites.png")
            compose.onNodeWithText(context.getString(R.string.shopping_close)).assertIsDisplayed().performClick()
            compose.waitForIdle()

            openTransfer()
            compose.onNodeWithText(context.getString(R.string.transfer_copy)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.transfer_copy_question, "milk", "QA groceries", "QA weekend"))
                .performScrollTo().assertExists()
            screenshot("everyday-memory-transfer.png")
            compose.onAllNodesWithText(context.getString(R.string.transfer_copy)).onLast().performClick()
            compose.waitUntil(15_000) { vm.state.value.transferResult == ItemTransferResult.COPIED }
            assertEquals(listOf(milk.value), db.itemDao().active(source).map { it.id })
            assertEquals(1, db.itemDao().active(target).size)
            dismissTransferResult()

            openTransfer()
            compose.onAllNodesWithText(context.getString(R.string.transfer_move)).onLast().performClick()
            compose.waitUntil(15_000) { vm.state.value.transferResult == ItemTransferResult.MOVED && vm.state.value.pending.isEmpty() }
            assertTrue(db.itemDao().active(source).isEmpty())
            val transferred = db.itemDao().active(target)
            assertEquals(2, transferred.size)
            assertEquals(2, transferred.map { it.id }.toSet().size)
            assertTrue(transferred.none { it.id == milk.value || it.checked || it.checkedAt != null })
            transferred.forEach {
                assertEquals("milk", it.name)
                assertEquals(2.0, requireNotNull(it.quantityAmount), 0.0)
                assertEquals(MeasureUnit.LITRE.name, it.quantityUnit)
                assertEquals("whole", it.note)
                assertEquals(barcode.value, it.gtin)
                assertEquals("QA market", it.store)
            }
            assertEquals("DAIRY", db.historyDao().preferences(target).single().category)
            assertTrue(db.historyDao().purchases(target).isEmpty())
            assertEquals(listOf("oats"), history.snapshot().purchases.map { it.name })
            dismissTransferResult()

            compose.runOnUiThread { vm.onSelectList(target) }
            compose.waitUntil(15_000) { vm.state.value.directory.selected == target && vm.state.value.pending.size == 2 }
            compose.runOnUiThread { vm.onSelectList(source) }
            compose.waitUntil(15_000) { vm.state.value.directory.selected == source && vm.state.value.pending.isEmpty() }
            compose.onNodeWithContentDescription(context.getString(R.string.capture_type)).performClick()
            compose.onNodeWithTag(DraftFieldTag).performTextReplacement("oa")
            compose.waitUntil(15_000) { vm.state.value.autocomplete.any { it.key == oatsKey } }
            compose.onNodeWithText(context.getString(R.string.memory_from_history)).assertExists()
            screenshot("everyday-memory-typing.png")
            compose.onNodeWithContentDescription(context.getString(R.string.memory_suggestion_options, "oats")).performClick()
            compose.onAllNodesWithText(context.getString(R.string.memory_dont_suggest)).onLast().performClick()
            compose.waitUntil(15_000) { vm.state.value.autocomplete.none { it.key == oatsKey } }
            assertTrue(history.snapshot().preferences.single { it.key == oatsKey }.muted)
            assertEquals(listOf("oats"), history.snapshot().purchases.map { it.name })
            compose.onNodeWithTag(DraftFieldTag).performTextReplacement("oats")
            compose.onNodeWithText(context.getString(R.string.capture_add)).performClick()
            compose.waitUntil(15_000) { vm.state.value.pending.any { it.rawName == "oats" } }
            assertEquals(listOf("oats"), items.items().map { it.name })
            assertFalse(items.items().single().fromSuggestion)
            assertEquals(1, tools.state.value.portable.favorites.size)
        } finally {
            compose.runOnUiThread { compose.activity.setContent { }; viewModels.clear() }
            db.close()
        }
    }
}

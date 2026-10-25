package io.theficos.usuals

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.data.local.*
import io.theficos.usuals.di.*
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.smart.*
import io.theficos.usuals.ui.list.*
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Real Room, production adapters and Compose, isolated from the installed app's shopping data. */
class ReviewDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("replayQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }

    @Test fun completeReviewFlowWorksOffline(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
        val viewModels = ViewModelStore()
        val source = "review-qa-source"
        val target = "review-qa-target"
        val now = 1_800_000_000_000L
        val arguments = InstrumentationRegistry.getArguments()
        val large = arguments.getString("replayLargeText") == "true"
        val dark = arguments.getString("replayDark") == "true"
        try {
            val replica = LocalReplica(db)
            val items = ItemRepository(db, clock = { now }, replica = replica)
            val history = HistoryRepository(db, items, clock = { now }, replica = replica)
            val directory = ListRepository(db)
            val parser = LocalUtteranceParser()
            val smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
            db.listDao().insert(ListEntity(source, "QA groceries"))
            db.listDao().insert(ListEntity(target, "QA weekend"))
            directory.select(source)
            val names = listOf("apples", "bananas", "carrots", "eggs", "flour", "grapes", "milk", "oats", "pasta", "rice", "soap", "tomatoes", "yoghurt")
            val past = (names + listOf("bread", "coffee", "candles", "tea")).map { ParsedItem(it, Quantity.pieces(2)) }
            history.importPast(past, now - 14 * RepeatPlanner.DAY_MILLIS)
            history.importPast(past, now - 7 * RepeatPlanner.DAY_MILLIS)
            history.importPast(listOf(ParsedItem("tea")), now - 60 * 60 * 1000)
            history.savePreference(ItemPreference("coffee", repeatEveryDays = 21))
            // Normalized identity is taken from the actual import, independent of language-pack plurals.
            val candles = history.snapshot().purchases.first { it.name == "candles" }.key
            smart.muteSuggestion(candles, source)
            items.setChecked(items.add(ParsedItem("bread")), true)
            items.add(ParsedItem("carry bag"))
            val purchaseCount = history.snapshot().purchases.size
            lateinit var vm: ListViewModel
            compose.runOnUiThread {
                vm = ListViewModel(RepositoryItemStore(items), parser, NoSpeechSource, clock = { now },
                    historyStore = RepositoryHistoryStore(history, parser, smart), smartStore = smart,
                    directoryStore = RepositoryListDirectory(directory, replica))
                viewModels.put("review-qa", vm)
                compose.activity.setContent {
                    val density = LocalDensity.current
                    val width = LocalConfiguration.current.screenWidthDp
                    CompositionLocalProvider(LocalDensity provides if (large) Density(density.density * width / 320f, 1.6f) else density) {
                        UsualsTheme(darkTheme = dark) { ListScreen(vm, onOpenScan = {}, onOpenSettings = {}) }
                    }
                }
            }
            fun screenshot(name: String) {
                compose.waitForIdle()
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                // The display compositor can trail Compose semantics by a frame.
                automation.waitForIdle(250, 5_000)
                val bitmap = requireNotNull(automation.takeScreenshot())
                try { File(context.cacheDir, (if (large) "large-" else "") + name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
            fun click(tag: String) { compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick() }
            fun awaitReady() { compose.waitUntil(15_000) { vm.state.value.review?.busy == false } }
            fun open() {
                compose.waitUntil(15_000) { vm.state.value.reviewCandidates.isNotEmpty() }
                compose.onNodeWithTag("review_entry").performClick()
                compose.waitUntil(15_000) { vm.state.value.review?.current != null }
            }
            compose.waitUntil(15_000) { vm.state.value.directory.selected == source && vm.state.value.reviewCandidates.size == 13 }
            compose.onNodeWithContentDescription(context.getString(R.string.review_title)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.review_title)).assertDoesNotExist()
            screenshot("replay-shortcut.png")
            open()
            val first = vm.state.value.review!!.current!!
            compose.onNodeWithTag("review_candidate").assertIsDisplayed().assertTextEquals(first.name)
            compose.onNodeWithTag("review_progress").assertTextEquals("1 of 10")
            screenshot("replay-review.png")
            click("review_skip")
            compose.waitUntil(10_000) { vm.state.value.review!!.cursor == 1 }
            click("review_undo")
            compose.waitUntil(10_000) { vm.state.value.review!!.current?.key == first.key }
            click("review_add"); awaitReady()
            compose.waitUntil(15_000) { vm.state.value.review!!.added == 1 }
            val added = items.items().single { it.fromSuggestion }
            assertEquals(first.name, added.name)
            assertNull(added.quantity)
            click("review_undo"); awaitReady()
            compose.waitUntil(15_000) { vm.state.value.review!!.current?.key == first.key }
            assertFalse(items.items().any { it.id == added.id })
            click("review_add"); awaitReady()
            compose.waitUntil(15_000) { vm.state.value.review!!.added == 1 }
            val muted = vm.state.value.review!!.current!!
            click("review_options")
            compose.onNodeWithText(context.getString(R.string.memory_dont_suggest)).performClick()
            awaitReady()
            compose.waitUntil(15_000) { vm.state.value.review!!.current?.key != muted.key }
            assertTrue(history.snapshot().preferences.single { it.key == muted.key }.muted)
            click("review_close")
            compose.waitUntil(10_000) { vm.state.value.review == null }
            assertEquals(1, items.items().count { it.fromSuggestion })
            assertEquals(purchaseCount, history.snapshot().purchases.size)

            open()
            assertEquals(11, vm.state.value.review!!.queue.size)
            val reviewed = mutableSetOf<String>()
            repeat(10) {
                val candidate = vm.state.value.review!!.current!!
                assertTrue(reviewed.add(candidate.key))
                click("review_skip")
                compose.waitUntil(10_000) { vm.state.value.review!!.current?.key != candidate.key }
            }
            compose.onNodeWithTag("review_summary").assertTextEquals("0 items added")
            screenshot("replay-round-complete.png")
            click("review_more")
            compose.waitUntil(10_000) { vm.state.value.review!!.current != null }
            assertTrue(reviewed.add(vm.state.value.review!!.current!!.key))
            click("review_skip")
            compose.waitUntil(10_000) { vm.state.value.review!!.current == null }
            compose.onNodeWithTag("review_more").assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.review_back)).performClick()
            compose.waitUntil(10_000) { vm.state.value.review == null }
            screenshot("replay-list.png")
            assertTrue(history.snapshot().preferences.none { it.snoozedUntil != null })

            open()
            val partnerItem = vm.state.value.review!!.current!!
            items.add(ParsedItem(partnerItem.name))
            compose.waitUntil(15_000) { vm.state.value.review!!.current?.key != partnerItem.key }
            assertEquals(1, items.items().count { it.name == partnerItem.name })
            compose.runOnUiThread { vm.onSelectList(target) }
            compose.waitUntil(15_000) { vm.state.value.directory.selected == target && vm.state.value.review == null }
            assertTrue(items.items().isEmpty())
            assertEquals(purchaseCount, db.historyDao().purchases(source).size)
        } finally {
            compose.runOnUiThread { compose.activity.setContent { }; viewModels.clear() }
            db.close()
        }
    }
}

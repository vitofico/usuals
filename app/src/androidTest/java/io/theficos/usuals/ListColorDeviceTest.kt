package io.theficos.usuals

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.data.local.*
import io.theficos.usuals.di.*
import io.theficos.usuals.model.ListColor
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.smart.*
import io.theficos.usuals.sync.ListAppearance
import io.theficos.usuals.ui.list.*
import io.theficos.usuals.ui.theme.AppPalette
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Production adapters and UI, with groceries isolated in an in-memory database. */
class ListColorDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("colorsQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }
    @Test fun choosePreviewCancelSaveAndSwitchLists() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(compose.activity, UsualsDatabase::class.java).build()
        val models = ViewModelStore()
        val args = InstrumentationRegistry.getArguments()
        val large = args.getString("colorsLarge") == "true"
        val dark = args.getString("colorsDark") == "true"
        try {
            val replica = LocalReplica(db)
            val lists = ListRepository(db)
            val items = ItemRepository(db, replica = replica)
            val history = HistoryRepository(db, items, replica = replica)
            val market = lists.create("Market")
            items.add(ParsedItem("milk")); items.add(ParsedItem("bread"))
            val weekend = lists.create("Weekend")
            items.add(ParsedItem("tomatoes")); lists.select(market)
            val parser = LocalUtteranceParser()
            val smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
            lateinit var vm: ListViewModel
            compose.runOnUiThread {
                vm = ListViewModel(RepositoryItemStore(items), parser, NoSpeechSource,
                    historyStore = RepositoryHistoryStore(history, parser, smart), smartStore = smart,
                    directoryStore = RepositoryListDirectory(lists, replica))
                models.put("list-colors", vm)
                compose.activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides if (large) Density(density.density, 1.6f) else density) {
                        UsualsTheme(darkTheme = dark, palette = AppPalette.fromId(args.getString("colorsPalette"))) { ListScreen(vm, {}, {}) }
                    }
                }
            }
            fun text(id: Int) = compose.activity.getString(id)
            fun openColors() {
                compose.onNodeWithContentDescription(text(R.string.list_more)).performClick()
                compose.onNodeWithText(text(R.string.quiet_list_manage)).performClick()
                compose.onNodeWithText(text(R.string.list_color_title)).performClick()
            }
            fun choose(id: Int) { compose.onNodeWithText(text(id)).performScrollTo().performClick() }
            fun waitColor(id: String, color: ListColor?) {
                compose.waitUntil(5_000) { vm.state.value.directory.lists.firstOrNull { it.id == id }?.let { it.color == color } == true && !vm.state.value.busy }
            }
            fun screenshot(name: String) {
                compose.waitForIdle()
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.waitForIdle(250, 5_000)
                val bitmap = requireNotNull(automation.takeScreenshot())
                try { File(compose.activity.cacheDir, name).outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
            compose.waitUntil(5_000) { vm.state.value.pending.size == 2 && !vm.state.value.busy }
            openColors(); choose(R.string.list_color_sage)
            screenshot(if (large) "list-color-preview-large.png" else "list-color-preview.png")
            compose.onNodeWithText(text(R.string.edit_cancel)).performClick()
            assertNull(ListAppearance.color(replica.projection(market)))
            openColors(); choose(R.string.list_color_rose)
            compose.onNodeWithText(text(R.string.edit_save)).performClick(); waitColor(market, ListColor.ROSE)
            assertEquals("rose", db.listDao().find(market)!!.color)
            compose.onNodeWithText("Market").performClick()
            compose.onNodeWithText("Weekend").performClick()
            compose.waitUntil(5_000) { vm.state.value.directory.selected == weekend && !vm.state.value.busy }
            openColors(); choose(R.string.list_color_teal)
            compose.onNodeWithText(text(R.string.edit_save)).performClick(); waitColor(weekend, ListColor.TEAL)
            compose.onNodeWithText("Weekend").performClick(); compose.onNodeWithText("Market").performClick()
            compose.waitUntil(5_000) { vm.state.value.directory.selected == market && !vm.state.value.busy }
            waitColor(market, ListColor.ROSE)
            openColors(); choose(R.string.list_color_automatic)
            compose.onNodeWithText(text(R.string.edit_save)).performClick(); waitColor(market, null)
            openColors(); choose(R.string.list_color_rose)
            compose.onNodeWithText(text(R.string.edit_save)).performClick(); waitColor(market, ListColor.ROSE)
            screenshot(if (large) "list-color-large.png" else "list-color.png")
            assertEquals(ListColor.ROSE, ListAppearance.color(replica.projection(market)))
            assertEquals(ListColor.TEAL, ListAppearance.color(replica.projection(weekend)))
        } finally { compose.runOnUiThread { models.clear() }; db.close() }
    }
}

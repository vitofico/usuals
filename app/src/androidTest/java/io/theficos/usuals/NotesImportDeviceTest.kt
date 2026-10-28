package io.theficos.usuals

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.data.local.*
import io.theficos.usuals.di.RepositoryItemStore
import io.theficos.usuals.di.RepositoryRecipeStore
import io.theficos.usuals.llm.ModelBudget
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.*
import io.theficos.usuals.recipe.*
import io.theficos.usuals.ui.list.ListViewModel
import io.theficos.usuals.ui.list.NoSpeechSource
import io.theficos.usuals.ui.recipe.*
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Opt-in emulator check with an isolated database and no model or remote reads. */
class NotesImportDeviceTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val enabled = TestRule { test, description ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(InstrumentationRegistry.getArguments().getString("notesQa") == "synthetic")
                compose.apply(test, description).evaluate()
            }
        }
    }

    @Test fun copiedNoteCanBeCancelledOrConfirmedOnDevice(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
        val viewModels = ViewModelStore()
        try {
            db.listDao().insert(ListEntity(LOCAL_LIST_ID, ""))
            val items = ItemRepository(db)
            val history = HistoryRepository(db, items)
            val budget = ModelBudget(1)
            val store = RepositoryRecipeStore(RecipeFetcher(RecipeJsonLd()), IngredientReviewPlanner(IngredientLineParser(), emptyList()),
                items, history, { error("Notes cannot request a model") }, budget)
            lateinit var vm: ListViewModel
            val note = "# Shopping\n☐ 2 l milk\n- [x] bread\n• 6 eggs"
            compose.runOnUiThread {
                vm = ListViewModel(RepositoryItemStore(items), LocalUtteranceParser(), NoSpeechSource, recipeStore = store,
                    historyStore = object : io.theficos.usuals.ui.history.ScopedHistoryStore,
                        io.theficos.usuals.ui.history.HistoryStore by io.theficos.usuals.ui.history.NoHistoryStore {
                        override fun observeShopping() = history.observeShopping()
                    })
                viewModels.put("notes-qa", vm)
                compose.activity.setContent { UsualsTheme {
                    val state by vm.state.collectAsState()
                    state.recipe?.let { RecipeImportSheet(it, vm::onRecipeUrl, vm::onReadRecipe, vm::onRecipeModel,
                        vm::onRecipeToggle, vm::onAddRecipe, vm::onDismissRecipe, vm::onRecipeSource,
                        vm::onRecipeMarkdown, vm::onReadRecipeMarkdown, vm::onRecipeMarkdownFile) }
                } }
                vm.onSharedText(note)
            }
            suspend fun reviewed() {
                compose.waitUntil(15_000) { vm.state.value.recipe?.result is RecipeLoad.Review }
                assertEquals(listOf("milk", "bread", "eggs"), (vm.state.value.recipe!!.result as RecipeLoad.Review).rows.map { it.item.name })
                assertTrue(items.items().isEmpty())
                assertEquals(0, budget.usedToday())
            }
            reviewed()
            compose.onNodeWithText(context.getString(R.string.recipe_close)).performClick()
            compose.waitUntil(10_000) { vm.state.value.recipe == null }
            assertTrue(items.items().isEmpty())
            compose.runOnUiThread { vm.onSharedText(note) }
            reviewed()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.cacheDir, "notes-import-review.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithText(context.resources.getQuantityString(R.plurals.recipe_add, 3, 3)).performClick()
            compose.waitUntil(10_000) { vm.state.value.recipe == null }
            val saved = withTimeout(10_000) { items.observeItems().first { it.size == 3 } }
            assertEquals(setOf("milk", "bread", "eggs"), saved.map { it.name }.toSet())
            assertEquals(Quantity(2.0, MeasureUnit.LITRE), saved.single { it.name == "milk" }.quantity)
            assertEquals(Quantity.pieces(6), saved.single { it.name == "eggs" }.quantity)
            assertEquals(0, budget.usedToday())
        } finally {
            compose.runOnUiThread { compose.activity.setContent { }; viewModels.clear() }
            db.close()
        }
    }
}

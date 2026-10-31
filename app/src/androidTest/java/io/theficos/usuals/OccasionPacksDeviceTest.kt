package io.theficos.usuals

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.data.local.*
import io.theficos.usuals.di.RepositorySmartStore
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.smart.*
import io.theficos.usuals.ui.list.ListRow
import io.theficos.usuals.ui.shopping.*
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Synthetic preferences and Room exercise actual sheet writes and the production pack reducer. */
class OccasionPacksDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("occasionPacksQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }
    private fun text(id: Int) = compose.activity.getString(id)
    private fun click(id: Int) = compose.onNodeWithText(text(id)).performScrollTo().performClick()
    private fun field(id: Int, value: String) = compose.onNode(hasText(text(id)) and hasSetTextAction()).performScrollTo().performTextReplacement(value)
    private fun dialogSave(title: String) {
        val itemDialog = isDialog() and hasAnyDescendant(hasText(title)) and
            hasAnyDescendant(hasText(text(R.string.pack_quantity)) and hasSetTextAction())
        compose.onNode(hasText(text(R.string.pack_save)) and hasClickAction() and
            hasAnyAncestor(itemDialog)).performClick()
    }

    @Test fun currentListEditingReviewedUseAndDurableRestart() = runBlocking {
        val prefs = compose.activity.getSharedPreferences("occasion-pack-device-qa", Context.MODE_PRIVATE)
        check(prefs.edit().clear().commit())
        fun controller() = ShoppingToolsStore(read = { prefs.getString("state", null) },
            write = { check(prefs.edit().putString("state", it).commit()) })
        val db = Room.inMemoryDatabaseBuilder(compose.activity, UsualsDatabase::class.java).build()
        try {
            val items = ItemRepository(db, clock = { 1_700_000_000_000L })
            val history = HistoryRepository(db, items, clock = { 1_700_000_000_000L })
            val smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
            items.add(ParsedItem("Milk", Quantity.pieces(1)), store = "Market")
            val bread = items.add(ParsedItem("Bread", Quantity.pieces(2), "Sliced"), store = "Market")
            val soap = items.add(ParsedItem("Soap"), store = "Other store")
            items.setChecked(soap, true)
            val original = items.items()
            val scope = items.currentListId()
            var tools by mutableStateOf(controller())
            var open by mutableStateOf(true)
            compose.runOnUiThread {
                compose.activity.setContent {
                    val personal by tools.state.collectAsState()
                    val active by items.observeItems().collectAsState(initial = original)
                    val density = LocalDensity.current
                    val large = InstrumentationRegistry.getArguments().getString("occasionPacksLarge") == "true"
                    // Density supplies a 320 dp logical viewport as well as the large-font variant.
                    val widthDp = compose.activity.resources.configuration.screenWidthDp.toFloat()
                    CompositionLocalProvider(LocalDensity provides Density(density.density * widthDp / 320f, if (large) 1.8f else 1f)) {
                        UsualsTheme {
                            if (open) OccasionPacksSheet(personal,
                                active.map { ListRow(it.id, it.name, rawName = it.name, quantity = it.quantity, note = it.note, checked = it.checked, store = it.store) },
                                onList = { saved -> active.any { Normalizer.normalize(it.name) == Normalizer.normalize(saved.name) } },
                                update = { change -> withContext(Dispatchers.IO) { change(tools) }; true },
                                add = { selected -> smart.addPack(selected, scope); true }, onDismiss = { open = false })
                        }
                    }
                }
            }
            click(R.string.pack_from_list)
            compose.onNodeWithContentDescription("Milk").performScrollTo().assertIsOn()
            compose.onNodeWithContentDescription("Bread").performScrollTo().assertIsOn()
            compose.onNodeWithContentDescription("Soap").performScrollTo().assertIsOff().performClick()
            field(R.string.pack_name, "Picnic")
            compose.onNodeWithText(text(R.string.pack_save)).assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(5_000) { tools.state.value.portable.packs.size == 1 }
            assertEquals(original, items.items())
            assertEquals(3, controller().state.value.portable.packs.single().items.size)

            compose.onNodeWithContentDescription(compose.activity.getString(R.string.pack_edit_named, "Picnic")).performScrollTo().performClick()
            field(R.string.pack_name, "Weekend")
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.pack_edit_named, "Bread")).performScrollTo().performClick()
            field(R.string.pack_quantity, "3")
            dialogSave(text(R.string.pack_edit_item))
            compose.onNodeWithText(text(R.string.pack_save)).assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { tools.state.value.portable.packs.single().name == "Weekend" }
            val saved = controller().state.value.portable.packs.single()
            assertEquals(3.0, saved.items.single { it.name == "Bread" }.amount)
            assertEquals("Sliced", saved.items.single { it.name == "Bread" }.note)
            assertEquals(original, items.items())

            // Recreate the preference controller and sheet, keeping the real persisted JSON.
            compose.onNodeWithText(text(R.string.shopping_close)).assertIsDisplayed().performClick()
            compose.runOnUiThread { tools = controller(); open = true }
            items.delete(bread)
            compose.waitUntil(5_000) { tools.state.value.portable.packs.single().name == "Weekend" }
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.pack_use_named, "Weekend")).performScrollTo().performClick()
            // Other-store checked Soap and unchecked Milk must both be unavailable.
            compose.onNodeWithContentDescription("Milk").performScrollTo().assertIsNotEnabled().assertIsOff()
            compose.onNodeWithContentDescription("Soap").performScrollTo().assertIsNotEnabled().assertIsOff()
            compose.onNodeWithContentDescription("Bread").performScrollTo().assertIsEnabled().assertIsOn()
            val beforeCancel = items.items()
            compose.onNodeWithText(text(R.string.pack_cancel)).assertIsDisplayed().performClick()
            assertEquals(beforeCancel, items.items())
            assertEquals(saved, controller().state.value.portable.packs.single())

            compose.onNodeWithContentDescription(compose.activity.getString(R.string.pack_use_named, "Weekend")).performScrollTo().performClick()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.pack_edit_named, "Bread")).performScrollTo().performClick()
            field(R.string.pack_quantity, "5")
            dialogSave("Bread")
            compose.onNodeWithText(text(R.string.pack_add_selected)).assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(5_000) { tools.state.value.portable.packs.single() == saved &&
                compose.onAllNodesWithText(text(R.string.pack_add_selected)).fetchSemanticsNodes().isEmpty() }
            val result = items.items()
            assertEquals(3, result.size)
            assertEquals(Quantity.pieces(5), result.single { it.name == "Bread" }.quantity)
            assertEquals("Sliced", result.single { it.name == "Bread" }.note)
            assertEquals("Market", result.single { it.name == "Bread" }.store)
            assertEquals(original.single { it.id == soap }, result.single { it.id == soap })
            assertEquals(saved, controller().state.value.portable.packs.single())
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            db.close()
            check(prefs.edit().clear().commit())
        }
    }
}

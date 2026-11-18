package io.theficos.usuals

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import io.theficos.usuals.data.local.*
import io.theficos.usuals.di.*
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.ui.list.*
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Isolated fixtures exercise the production Room adapter without touching installed shopping data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class SwipeRemovalScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun swipeUndoAndDismissal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(compose.activity, UsualsDatabase::class.java).build()
        val models = ViewModelStore()
        try {
            val repository = ItemRepository(db, clock = { 1_700_000_000_000L })
            repository.add(ParsedItem("milk", Quantity.pieces(2), "whole"), store = "Market")
            repository.add(ParsedItem("bread"))
            val original = repository.items()
            lateinit var vm: ListViewModel
            compose.runOnUiThread {
                vm = ListViewModel(RepositoryItemStore(repository), LocalUtteranceParser(), NoSpeechSource, clock = { 1_700_000_000_000L })
                models.put("swipe-qa", vm)
                compose.activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                        UsualsTheme { ListScreen(vm, {}, {}) }
                    }
                }
            }
            compose.waitUntil(5_000) { vm.state.value.pending.size == 2 }
            compose.onNodeWithText("Milk").performTouchInput { swipeLeft() }
            val undo = compose.activity.getString(R.string.finish_shop_undo)
            compose.onNodeWithText(undo).assertIsDisplayed()
            compose.onNodeWithText("Milk").assertDoesNotExist()
            assertEquals(original, repository.items())
            compose.onNodeWithText(undo).performClick()
            compose.onNodeWithText("Milk").assertIsDisplayed()
            assertEquals(original, repository.items())
            compose.onNodeWithText("Milk").performTouchInput { swipeRight() }
            compose.onNodeWithText("Bread").performTouchInput { swipeLeft() }
            compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.swipe_removed, 2, 2)).assertIsDisplayed()
            compose.onNodeWithText(undo).performClick()
            compose.onNodeWithText("Milk").assertIsDisplayed()
            compose.onNodeWithText("Bread").assertIsDisplayed()
            assertEquals(original, repository.items())
            compose.onNodeWithText("Milk").performTouchInput { swipeLeft() }
            // Advance the Compose clock past the snackbar duration.
            compose.mainClock.advanceTimeBy(11_000)
            compose.waitForIdle()
            compose.waitUntil(20_000) { vm.state.value.swipeRemoval == null }
            compose.waitUntil(5_000) { vm.state.value.pending.size == 1 }
            assertEquals(listOf(original.last()), repository.items())
            assertTrue(db.historyDao().purchases().isEmpty())
        } finally {
            compose.runOnUiThread { models.clear() }
            db.close()
        }
    }
}

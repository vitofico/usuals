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
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
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

/** Isolated fixtures exercise the production Room adapter without touching installed shopping data. */
class SwipeRemovalDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("swipeQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }

    @Test fun swipeUndoAndDismissal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(compose.activity, UsualsDatabase::class.java).build()
        val models = ViewModelStore()
        val large = InstrumentationRegistry.getArguments().getString("swipeLarge") == "true"
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
                    CompositionLocalProvider(LocalDensity provides if (large) Density(density.density, 1.6f) else density) {
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
            val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                File(compose.activity.cacheDir, if (large) "swipe-undo-large.png" else "swipe-undo.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
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
            // Snackbar delays use the controlled Compose clock in an instrumentation test.
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

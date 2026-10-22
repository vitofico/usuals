package io.theficos.usuals

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.model.*
import io.theficos.usuals.smart.*
import io.theficos.usuals.ui.account.*
import io.theficos.usuals.ui.history.*
import io.theficos.usuals.ui.list.*
import io.theficos.usuals.ui.scan.*
import io.theficos.usuals.ui.settings.*
import io.theficos.usuals.ui.shopping.*
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File

/** Opt-in visual/reachability sweep, using synthetic state and the service-free test host. */
class InterfaceSweepDeviceTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val enabled = TestRule { test, description -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("memoryQa") == "synthetic")
            compose.apply(test, description).evaluate()
        }
    } }
    private val directory = ListDirectory("groceries", listOf(
        NamedList("groceries", "Groceries", false), NamedList("weekend", "Weekend", false)))
    private val milk = ListRow(ItemId("milk"), "Milk", Quantity(2.0, MeasureUnit.LITRE), category = Category.DAIRY)
    private val populated = ListUiState(directory = directory, header = ListHeader("Groceries", 8),
        pending = listOf(milk,
            ListRow(ItemId("bread"), "Sourdough bread", category = Category.BAKERY),
            ListRow(ItemId("lemons"), "Lemons", Quantity(4.0, MeasureUnit.PIECE), category = Category.PRODUCE),
            ListRow(ItemId("tomatoes"), "Cherry tomatoes", category = Category.PRODUCE),
            ListRow(ItemId("oil"), "Olive oil"), ListRow(ItemId("pasta"), "Pasta")),
        checked = listOf(ListRow(ItemId("eggs"), "Eggs", checked = true), ListRow(ItemId("coffee"), "Coffee", checked = true)),
        suggestions = listOf(Suggestion("yoghurt", "Yoghurt", SuggestionReason.STAPLE), Suggestion("bananas", "Bananas", SuggestionReason.STAPLE)))

    private fun show(dark: Boolean = false, narrowLarge: Boolean = false, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            compose.activity.setContent {
                val base = LocalDensity.current
                val width = LocalConfiguration.current.screenWidthDp
                CompositionLocalProvider(LocalDensity provides Density(
                    base.density * if (narrowLarge) width / 320f else 1f,
                    if (narrowLarge) 1.6f else 1f)) {
                    UsualsTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
                }
            }
        }
        compose.waitForIdle()
    }
    private fun record(name: String, target: SemanticsMatcher = isRoot()) {
        compose.waitForIdle()
        val node = compose.onNode(target).assertIsDisplayed()
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.cacheDir, "quiet-$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @Test fun listStatesStayFocusedAndReachable() {
        var created: String? = null
        show { ListContent(populated, ListActions(onCreateList = { created = it })) }
        compose.onNodeWithText("Milk").assertIsDisplayed()
        compose.onNodeWithText("Add items").assertIsDisplayed()
        record("list")
        compose.onNodeWithText("Groceries").performClick()
        compose.onNodeWithText("New list").performClick()
        compose.onNodeWithText("List name").assertIsDisplayed().performTextInput("Weekend groceries")
        compose.onNodeWithText("Create").assertIsEnabled()
        compose.onNodeWithText("Create").performClick()
        check(created == "Weekend groceries")
        compose.onNodeWithText("List name").assertDoesNotExist()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Favourites").assertIsDisplayed()
        record("menu", isPopup())
        show { ListContent(populated.copy(personal = ShoppingPreferences(mode = true)), ListActions(shoppingMode = true)) }
        record("shopping")
        show { ListContent(ListUiState(directory = directory, header = ListHeader("Groceries")), ListActions()) }
        compose.onNodeWithText("Add your first item").assertIsDisplayed()
        record("empty")
        show(dark = true, narrowLarge = true) { ListContent(populated.copy(header = ListHeader("Groceries for the weekend with friends", 8)), ListActions()) }
        compose.onNodeWithText("Add items").assertIsDisplayed()
        compose.onNodeWithText("Milk").assertIsDisplayed()
        record("list-large-dark")
        show { ListContent(populated.copy(capture = CaptureState.Typing("mi"), autocomplete = listOf(ItemMemory("milk", "Milk", 4, 1_000))), ListActions()) }
        compose.onNodeWithText("From past shops").assertIsDisplayed()
        record("typing")
    }

    @Test fun secondaryScreensKeepTheirPrimaryActionVisible() {
        val models = ViewModelStore()
        try {
            show { EditItemSheet(milk, false, {}, {}, {}, onPin = {}) }
            compose.onNodeWithText("Save").assertIsDisplayed()
            record("editor", isDialog())
            val tools = ShoppingToolsStore()
            listOf("Milk", "Bread", "Lemons").forEach { tools.pin(SavedShoppingItem(id = it.lowercase(), name = it)) }
            show {
                val preferences by tools.state.collectAsState()
                ShoppingToolsSheet(preferences, null, { it(tools) }, {}, {})
            }
            compose.onNodeWithText("Close").assertIsDisplayed()
            record("favourites", isDialog())
            val settings = SettingsViewModel().also { models.put("settings", it) }
            show { SettingsScreen(settings, {}) }
            compose.onNodeWithText("Appearance").assertIsDisplayed()
            record("settings")
            show { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) { AccountSignIn(AccountState(), {}, {}, {}, {}) } }
            record("account")
            val snapshot = HistorySnapshot(trips = listOf(Trip("trip", 1_790_000_000_000)),
                purchases = listOf(Purchase(ItemId("milk"), "trip", "milk", "Milk", boughtAt = 1_790_000_000_000)))
            val history = HistoryViewModel(object : HistoryStore by NoHistoryStore { override fun observe() = flowOf(snapshot) }, clock = { 1_790_000_000_000 }).also { models.put("history", it) }
            show { HistoryScreen(history) {} }
            compose.waitUntil(10_000) { history.state.value.history.purchases.isNotEmpty() }
            record("history")
            show(narrowLarge = true) { ScanNameSheet(ScanNameOffer(requireNotNull(Gtin.parse("3017620422003")), "Milk", null, false, ScanNameProblem.LOOKUP_DISABLED), 1, false, {}, {}, {}) }
            compose.onNodeWithText("Add to list").assertIsDisplayed()
            record("scan-large", isDialog())
        } finally { compose.runOnUiThread { compose.activity.setContent {}; models.clear() } }
    }
}

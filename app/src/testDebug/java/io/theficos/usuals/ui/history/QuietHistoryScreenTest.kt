package io.theficos.usuals.ui.history

import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import io.theficos.usuals.model.*
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class QuietHistoryScreenTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var host: View
    private var screenDensity = 1f
    private var effectiveFontScale = 1f
    private val snapshot = HistorySnapshot(trips = listOf(Trip("trip", 1_000_000)),
        purchases = listOf(Purchase(ItemId("milk"), "trip", "milk", "Milk", boughtAt = 1_000_000)))
    private fun render(fontScale: Float = 1f): HistoryViewModel {
        val store = object : HistoryStore by NoHistoryStore { override fun observe() = flowOf(snapshot) }
        val vm = HistoryViewModel(store, clock = { 1_000_000 })
        models.put("history-quiet", vm)
        compose.setContent {
            val density = LocalDensity.current
            val view = LocalView.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                val effective = LocalDensity.current
                SideEffect { host = view; screenDensity = effective.density; effectiveFontScale = effective.fontScale }
                UsualsTheme { HistoryScreen(vm) {} }
            }
        }
        compose.waitUntil(10_000) { vm.state.value.history.purchases.isNotEmpty() }
        return vm
    }
    @After fun clear() { compose.runOnIdle { models.clear() } }

    @Test fun `shopping statistics are collapsed and independent of purchase search`() {
        val vm = render()
        compose.onNodeWithTag("stats_shops").assertDoesNotExist()
        compose.onNodeWithTag("shopping_stats_toggle").performClick()
        compose.onNodeWithTag("stats_shops").assertTextEquals("Saved shops: 1")
        compose.onNodeWithTag("stats_purchases").assertTextEquals("Items recorded: 1")
        compose.onNodeWithTag("stats_different").assertTextEquals("Different items: 1")
        compose.runOnIdle { vm.search("nothing matches") }
        compose.onNodeWithTag("stats_purchases").assertTextEquals("Items recorded: 1")
        compose.onNodeWithTag("shopping_stats_toggle").performClick()
        compose.onNodeWithTag("stats_shops").assertDoesNotExist()
    }

    @Test fun `shops appear directly and management is behind history options`() {
        render()
        compose.onNodeWithText("Milk").assertIsDisplayed()
        compose.onNodeWithText("Export data").assertDoesNotExist()
        compose.onNodeWithText("Import data").assertDoesNotExist()
        compose.onNodeWithContentDescription("History options").performClick()
        compose.onNodeWithText("Export data").assertExists()
        compose.onNodeWithText("Import data").assertExists()
        compose.onNodeWithText("Add a past shop").performClick()
        compose.onNodeWithText("Save past shop").assertExists().assertIsNotEnabled()
    }

    @Test fun `selected purchases can be added above an open search keyboard`() {
        val vm = render()
        compose.onNodeWithText("Search past purchases").performClick().performTextReplacement("Milk")
        compose.onNodeWithContentDescription("Milk").performClick()
        val keyboard = (300 * screenDensity).toInt()
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(host, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .setVisible(WindowInsetsCompat.Type.ime(), true)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (24 * screenDensity).toInt()))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build())
        }
        compose.waitForIdle()
        val button = compose.onNodeWithText("Add selected (1)").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        assertThat(button.bottom).isAtMost(bottom - keyboard + 1f)
        compose.onNodeWithText("Add selected (1)").performClick()
        compose.waitUntil(10_000) { vm.state.value.notice == HistoryNotice.REBOUGHT }
    }

    @Test
    @Config(sdk = [33], qualifiers = "en-w411dp-h360dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `history information scrolls at large text while close stays reachable`() {
        val previousScale = RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(1.8f)
        try {
            render(fontScale = 1.8f)
            assertThat(host.resources.configuration.fontScale).isWithin(0.01f).of(1.8f)
            compose.onNodeWithContentDescription("History options").performClick()
            compose.onNodeWithText("About your history").performClick()
            val explanation = "Exports include your named lists, shops, purchases and item preferences. Keys, membership and cached product data stay on the phone."
            val scroll = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText(explanation)))
            val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val dialogBounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
            val textBounds = compose.onNodeWithText(explanation).fetchSemanticsNode().boundsInRoot
            val scrollBounds = scroll.fetchSemanticsNode().boundsInRoot
            val configuration = host.resources.configuration
            val geometry = "host=${host.width}x${host.height}, config=${configuration.screenWidthDp}x${configuration.screenHeightDp}dp, " +
                "fontScale=${configuration.fontScale}/$effectiveFontScale, dialog=${dialogBounds.width}x${dialogBounds.height}, " +
                "textHeight=${textBounds.height}, scrollHeight=${scrollBounds.height}, maxRange=${range.maxValue()}"
            assertWithMessage(geometry).that(range.maxValue()).isGreaterThan(0f)
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { action ->
                assertThat(action(0f, 10_000f)).isTrue()
            }
            compose.waitForIdle()
            assertWithMessage(geometry).that(range.value()).isGreaterThan(0f)
            compose.onNodeWithText(explanation).assertIsDisplayed()
            compose.onNodeWithText("Close").assertIsDisplayed().performClick()
            compose.onNodeWithText(explanation).assertDoesNotExist()
        } finally {
            RuntimeEnvironment.setFontScale(previousScale)
        }
    }

    @Test fun `adding selected purchases stays visible below the list`() {
        render()
        compose.onNodeWithContentDescription("Milk").performClick()
        compose.onNodeWithText("Add selected (1)").assertIsDisplayed()
    }
}

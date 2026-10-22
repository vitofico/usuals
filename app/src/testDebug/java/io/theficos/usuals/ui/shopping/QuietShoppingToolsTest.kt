package io.theficos.usuals.ui.shopping

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class QuietShoppingToolsTest {
    @get:Rule val compose = createComposeRule()
    private val milk = SavedShoppingItem("milk", "Milk", 2.0, "LITRE", store = "Market")

    @Test fun `saved items lead and optional shopping controls start closed`() {
        val preferences = ShoppingPreferences(portable = PortableShoppingPreferences(listOf(milk)))
        compose.setContent { UsualsTheme { ShoppingToolsSheet(preferences, null, {}, {}, {}, alreadyAdded = setOf(milk.id)) } }
        compose.onNodeWithText("Favourites").assertIsDisplayed()
        compose.onNodeWithText("Milk").assertIsDisplayed()
        compose.onNodeWithContentDescription("Milk is already on this list").assertIsNotEnabled()
        compose.onNodeWithText("Shopping mode").assertDoesNotExist()
        compose.onNodeWithContentDescription("Move Fruit and vegetables up").assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Shopping preferences").performScrollTo().performClick()
        compose.onNodeWithText("Shopping mode").assertExists()
        compose.onNodeWithText("Aisle order").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Move Fruit and vegetables up").assertExists()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun `favourite removal is a labelled secondary action without an extra confirmation`() {
        val store = ShoppingToolsStore()
        store.pin(milk)
        compose.setContent {
            val preferences by store.state.collectAsState()
            UsualsTheme { ShoppingToolsSheet(preferences, null, { it(store) }, {}, {}) }
        }
        compose.onNodeWithContentDescription("Remove Milk from favourites").assertDoesNotExist()
        compose.onNodeWithContentDescription("Options for favourite Milk").performClick()
        compose.onNodeWithContentDescription("Remove Milk from favourites").performClick()
        assertThat(store.state.value.portable.favorites).isEmpty()
        compose.onNodeWithText("Milk").assertDoesNotExist()
    }

    @Test fun `close remains visible with large text and many saved items`() {
        val preferences = ShoppingPreferences(portable = PortableShoppingPreferences((1..12).map { milk.copy(id = "milk-$it", name = "Milk $it") }))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                UsualsTheme { ShoppingToolsSheet(preferences, null, {}, {}, {}) }
            }
        }
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Shopping preferences").performScrollTo().performClick()
        compose.onNodeWithText("Close").assertIsDisplayed()
    }

    @Test fun `full explanation and privacy remain available from information`() {
        compose.setContent { UsualsTheme { ShoppingToolsSheet(ShoppingPreferences(), null, {}, {}, {}) } }
        compose.onNodeWithText("Save items here to add them to any list again, with their quantity and store. You choose when to add them. Items already open are skipped.").assertDoesNotExist()
        compose.onNodeWithContentDescription("About favourites").performClick()
        compose.onNodeWithText("Save items here to add them to any list again, with their quantity and store. You choose when to add them. Items already open are skipped.").assertExists()
        compose.onNodeWithText("These choices stay on this device. Favourites and store layouts are included in your data exports; they are not shared with anyone.").assertExists()
    }
}

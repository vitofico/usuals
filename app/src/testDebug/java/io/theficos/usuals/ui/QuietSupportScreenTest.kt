package io.theficos.usuals.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.ui.account.AccountSignIn
import io.theficos.usuals.ui.account.AccountState
import io.theficos.usuals.ui.scan.ScanNameOffer
import io.theficos.usuals.ui.scan.ScanNameProblem
import io.theficos.usuals.ui.scan.ScanNameSheet
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuietSupportScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun manual_sign_in_is_available_without_losing_the_server_draft() {
        var state by mutableStateOf(AccountState())
        var submitted = 0
        compose.setContent {
            UsualsTheme { Column(Modifier.fillMaxWidth()) {
                AccountSignIn(state, { state = state.copy(origin = it) }, { submitted++ }, {}, {})
            } }
        }
        compose.onNodeWithText("Server address").assertDoesNotExist()
        compose.onNodeWithText("Sign in without a QR code").performClick()
        compose.onNodeWithText("Server address").performTextInput("https://usuals.example.org")
        compose.onNodeWithText("Sign in without a QR code").performClick()
        compose.onNodeWithText("Server address").assertDoesNotExist()
        compose.onNodeWithText("Sign in without a QR code").performClick()
        compose.onNodeWithText("https://usuals.example.org").assertExists()
        compose.onNodeWithText("Sign in in your browser").performClick()
        assertThat(submitted).isEqualTo(1)
    }

    @Test fun an_incoming_server_address_reveals_the_manual_sign_in_path() {
        var state by mutableStateOf(AccountState())
        compose.setContent {
            UsualsTheme { Column { AccountSignIn(state, {}, {}, {}, {}) } }
        }
        compose.runOnIdle { state = state.copy(origin = "https://usuals.example.org") }
        compose.onNodeWithText("https://usuals.example.org").assertExists()
        compose.onNodeWithText("Sign in without a QR code").performClick()
        compose.runOnIdle { state = state.copy(origin = "https://other.example.org") }
        compose.onNodeWithText("https://other.example.org").assertExists()
    }

    @Test fun manual_scan_capture_is_primary_and_online_lookup_requires_a_choice() {
        var added: String? = null
        var lookup = 0
        compose.setContent {
            UsualsTheme {
                ScanNameSheet(ScanNameOffer(requireNotNull(Gtin.parse("3017620422003")), null, null, false,
                    ScanNameProblem.LOOKUP_DISABLED), 1, false, { added = it }, {}, {}, { lookup++ })
            }
        }
        compose.onNodeWithText("Enable product names").assertDoesNotExist()
        compose.onNodeWithText("Name").performTextInput("Milk")
        compose.onNodeWithText("Add to list").assertIsDisplayed().performClick()
        assertThat(added).isEqualTo("Milk")
        assertThat(lookup).isEqualTo(0)
        compose.onNodeWithText("Online product lookup").performClick()
        compose.onNodeWithText("Enable product names").performClick()
        assertThat(lookup).isEqualTo(1)
    }

    @Test fun scan_confirmation_stays_visible_with_large_text() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                UsualsTheme {
                    ScanNameSheet(ScanNameOffer(requireNotNull(Gtin.parse("3017620422003")), "Milk", null, false,
                        ScanNameProblem.UNAVAILABLE), 3, false, {}, {}, {}, onRetry = {})
                }
            }
        }
        compose.onNodeWithText("Add to list").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Don't add the rest").assertIsDisplayed()
    }
}

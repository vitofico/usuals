package io.theficos.comanda.ui.list

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.model.ItemId
import io.theficos.comanda.model.Quantity
import io.theficos.comanda.parse.LocalUtteranceParser
import io.theficos.comanda.ui.theme.ComandaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class ListSharingTest {
    @get:Rule val compose = createComposeRule()

    @Suppress("DEPRECATION")
    @Test fun `sharing opens the system chooser with a readable list snapshot`() {
        val vm = ListViewModel(FakeItemStore(listOf(
            item("milk", "milk", Quantity.pieces(2)), item("bread", "bread", checked = true),
        )), LocalUtteranceParser(), FakeSpeechSource())
        compose.setContent { ComandaTheme { ListScreen(vm, {}, {}) } }
        compose.onNodeWithContentDescription("Share list").performClick()
        val chooser = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("☐ Milk ×2\n☑ Bread")
    }

    @Test fun `swiping a row invokes removal once`() {
        var state by mutableStateOf(ListUiState(pending = listOf(ListRow(ItemId("milk"), "Milk"))))
        val removed = mutableListOf<ItemId>()
        compose.setContent {
            ComandaTheme {
                ListContent(state, ListActions(onConfirmRemove = {
                    removed += it
                    state = ListUiState()
                }))
            }
        }
        compose.onNodeWithText("Milk").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertThat(removed).containsExactly(ItemId("milk"))
    }
}

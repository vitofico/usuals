package io.theficos.usuals.ui.list

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.ui.theme.UsualsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import io.theficos.usuals.model.ShoppingSnapshot
import io.theficos.usuals.ui.history.HistoryStore
import io.theficos.usuals.ui.history.NoHistoryStore
import io.theficos.usuals.ui.history.ScopedHistoryStore
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
    @Test fun `sending as text opens the system chooser with a readable list snapshot`() {
        val vm = ListViewModel(FakeItemStore(listOf(
            item("milk", "milk", Quantity.pieces(2)), item("bread", "bread", checked = true),
        )), LocalUtteranceParser(), FakeSpeechSource(), compute = kotlinx.coroutines.Dispatchers.Main.immediate)
        compose.setContent { UsualsTheme { ListScreen(vm, {}, {}) } }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Send as text").performClick()
        val chooser = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("☐ Milk ×2\n☑ Bread")
    }

    @Test fun `sharing remains available through the labelled list menu`() {
        var opened = 0
        val vm = ListViewModel(FakeItemStore(), LocalUtteranceParser(), FakeSpeechSource(), compute = kotlinx.coroutines.Dispatchers.Main.immediate)
        compose.setContent { UsualsTheme { ListScreen(vm, {}, {}, onOpenSharing = { opened++ }) } }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Share").performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test fun `the menu deletes an owned list and leaves one someone else owns`() {
        val lists = MutableStateFlow(ListDirectory("own", listOf(NamedList("own", "Home", shared = false), NamedList("theirs", "Camping", shared = true, editor = true))))
        val removed = mutableListOf<String>()
        val directory = object : ListDirectoryStore {
            override fun observe() = lists
            override suspend fun select(id: String) { lists.value = lists.value.copy(selected = id) }
            override suspend fun create(name: String) = Unit
            override suspend fun remove(id: String) { removed += id }
        }
        // The screen waits until the items shown belong to the selected list.
        val history = object : ScopedHistoryStore, HistoryStore by NoHistoryStore {
            override fun observeShopping() = lists.map { ShoppingSnapshot(listId = it.selected) }
        }
        val vm = ListViewModel(FakeItemStore(), LocalUtteranceParser(), FakeSpeechSource(), historyStore = history, directoryStore = directory, compute = kotlinx.coroutines.Dispatchers.Main.immediate)
        compose.setContent { UsualsTheme { ListScreen(vm, {}, {}) } }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Manage list").performClick()
        compose.onNodeWithText("Leave list").assertDoesNotExist()
        compose.onNodeWithText("Delete list").performClick()
        compose.onNodeWithText("Delete list").performClick()
        compose.waitForIdle()
        assertThat(removed).containsExactly("own")
        lists.value = lists.value.copy(selected = "theirs")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Manage list").performClick()
        compose.onNodeWithText("Delete list").assertDoesNotExist()
        compose.onNodeWithText("Leave list").assertExists()
    }

    @Test fun `the menu archives the list and the picker restores one, while an editor can only leave a list its owner archived`() {
        val lists = MutableStateFlow(ListDirectory("own", listOf(NamedList("own", "Home", shared = false),
            NamedList("old", "Camping", shared = false, archived = true, archivedByOwner = true),
            NamedList("theirs", "Market", shared = true, editor = true, archived = true, archivedByOwner = true))))
        val changes = mutableListOf<Pair<String, Boolean>>()
        val removed = mutableListOf<String>()
        val directory = object : ListDirectoryStore {
            override fun observe() = lists
            override suspend fun select(id: String) { lists.value = lists.value.copy(selected = id) }
            override suspend fun create(name: String) = Unit
            override suspend fun archive(id: String, archived: Boolean) { changes += id to archived }
            override suspend fun remove(id: String) { removed += id }
        }
        val history = object : ScopedHistoryStore, HistoryStore by NoHistoryStore {
            override fun observeShopping() = lists.map { ShoppingSnapshot(listId = it.selected) }
        }
        val vm = ListViewModel(FakeItemStore(), LocalUtteranceParser(), FakeSpeechSource(), historyStore = history, directoryStore = directory, compute = kotlinx.coroutines.Dispatchers.Main.immediate)
        compose.setContent { UsualsTheme { ListScreen(vm, {}, {}) } }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Manage list").performClick()
        compose.onNodeWithText("Archive").performClick()
        compose.onNodeWithText("Archive Home?").assertExists()
        compose.onNodeWithText("Archive").performClick()
        compose.waitForIdle()
        assertThat(changes).containsExactly("own" to true)
        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Archived (2)").performClick()
        compose.onNodeWithText("Archived by the owner. Only the owner can restore it.").assertExists()
        compose.onAllNodesWithText("Restore").assertCountEquals(1)
        compose.onAllNodesWithText("Leave list").assertCountEquals(1)
        compose.onNodeWithText("Restore").performClick()
        compose.waitForIdle()
        assertThat(changes).containsExactly("own" to true, "old" to false).inOrder()
        compose.onNodeWithText("Leave list").performClick()
        compose.onNodeWithText("Leave Market?").assertExists()
        compose.onNodeWithText("Leave list").performClick()
        compose.waitForIdle()
        assertThat(removed).containsExactly("theirs")
        assertThat(changes).hasSize(2)
    }

    @Test fun `the blank starting list offers no Archive`() {
        val lists = MutableStateFlow(ListDirectory("blank", listOf(NamedList("blank", "", shared = false)), unused = true))
        val directory = object : ListDirectoryStore {
            override fun observe() = lists
            override suspend fun select(id: String) = Unit
            override suspend fun create(name: String) = Unit
        }
        val history = object : ScopedHistoryStore, HistoryStore by NoHistoryStore {
            override fun observeShopping() = lists.map { ShoppingSnapshot(listId = it.selected) }
        }
        val vm = ListViewModel(FakeItemStore(), LocalUtteranceParser(), FakeSpeechSource(), historyStore = history, directoryStore = directory, compute = kotlinx.coroutines.Dispatchers.Main.immediate)
        compose.setContent { UsualsTheme { ListScreen(vm, {}, {}) } }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Manage list").performClick()
        compose.onNodeWithText("Rename").assertExists()
        compose.onNodeWithText("Archive").assertDoesNotExist()
    }

    @Test fun `swiping a row invokes removal once`() {
        var state by mutableStateOf(ListUiState(pending = listOf(ListRow(ItemId("milk"), "Milk"))))
        val removed = mutableListOf<ItemId>()
        compose.setContent {
            UsualsTheme {
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

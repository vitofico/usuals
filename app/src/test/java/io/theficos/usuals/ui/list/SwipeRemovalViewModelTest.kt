package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.ui.history.HistoryStore
import io.theficos.usuals.ui.history.NoHistoryStore
import io.theficos.usuals.ui.history.ScopedHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class SwipeRemovalViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private fun vm(store: ItemStore, directory: ListDirectoryStore = NoListDirectory, history: HistoryStore = NoHistoryStore) =
        ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(), directoryStore = directory, historyStore = history, compute = main.dispatcher, clock = { 1_700_000_000_000L })

    @Test fun `Undo restores the original checked row and every detail without a storage write`() = runTest(main.dispatcher) {
        val original = item("milk", "milk", checked = true).copy(quantity = Quantity.pieces(2), note = "whole", store = "Market", gtin = Gtin.parse("3017620422003"))
        val store = FakeItemStore(listOf(original, item("bread", "bread", position = 2.0)))
        val vm = vm(store)
        val before = vm.state.value
        vm.onSwipeRemoveRow(before.checked.single())
        assertThat(vm.state.value.checked).isEmpty()
        assertThat(vm.state.value.header.itemCount).isEqualTo(1)
        assertThat(store.current).containsExactly(original, store.current.last())
        assertThat(store.removed).isEmpty()
        vm.onUndoSwipeRemoval(vm.state.value.swipeRemoval!!.token)
        assertThat(vm.state.value.checked).isEqualTo(before.checked)
        assertThat(vm.state.value.header).isEqualTo(before.header)
        assertThat(store.current.first()).isEqualTo(original)
        assertThat(store.removed).isEmpty()
    }

    @Test fun `the store filter count stays correct before removal and after Undo`() = runTest(main.dispatcher) {
        val store = FakeItemStore(listOf(item("milk", "milk").copy(store = "Market"), item("bread", "bread").copy(store = "Bakery")))
        val vm = vm(store)
        vm.onStoreFilter("Market")
        assertThat(vm.state.value.header.itemCount).isEqualTo(1)
        vm.onSwipeRemoveRow(vm.state.value.pending.single())
        assertThat(vm.state.value.header.itemCount).isEqualTo(0)
        vm.onUndoSwipeRemoval(vm.state.value.swipeRemoval!!.token)
        assertThat(vm.state.value.header.itemCount).isEqualTo(1)
        assertThat(vm.state.value.pending.single().id).isEqualTo(ItemId("milk"))
    }

    @Test fun `quick swipes share Undo and an obsolete snackbar cannot delete the newer batch`() = runTest(main.dispatcher) {
        val store = FakeItemStore(listOf(item("milk", "milk"), item("bread", "bread", position = 2.0)))
        val vm = vm(store)
        vm.onSwipeRemoveRow(vm.state.value.pending.first())
        val first = vm.state.value.swipeRemoval!!.token
        vm.onSwipeRemoveRow(vm.state.value.pending.single())
        val batch = vm.state.value.swipeRemoval!!
        assertThat(batch.rows).hasSize(2)
        assertThat(vm.state.value.isEmpty).isTrue()
        vm.onCommitSwipeRemoval(first)
        assertThat(store.removed).isEmpty()
        vm.onUndoSwipeRemoval(batch.token)
        assertThat(vm.state.value.pending.map { it.id }).containsExactly(ItemId("milk"), ItemId("bread")).inOrder()
        vm.onCommitSwipeRemoval(batch.token)
        assertThat(store.removed).isEmpty()
    }

    @Test fun `dismissal commits once and passes the exact swipe version`() = runTest(main.dispatcher) {
        val version = ItemVersion("", "milk", mapOf("name" to "name-1"), "life-1", null, emptyMap(), emptyMap(), "{}")
        val original = item("milk", "milk").also { it.version = version }
        val backing = FakeItemStore(listOf(original))
        val versions = mutableListOf<ItemVersion?>()
        val store = object : ItemStore by backing {
            override suspend fun removeAll(rows: List<Pair<ItemId, ItemVersion?>>) { versions += rows.map { it.second }; backing.removeAll(rows) }
        }
        val vm = vm(store)
        vm.onSwipeRemoveRow(vm.state.value.pending.single())
        val token = vm.state.value.swipeRemoval!!.token
        vm.onCommitSwipeRemoval(token)
        vm.onCommitSwipeRemoval(token)
        assertThat(backing.removed).containsExactly(ItemId("milk"))
        assertThat(versions).containsExactly(version)
        assertThat(vm.state.value.pending).isEmpty()
        assertThat(vm.state.value.swipeRemoval).isNull()
    }

    @Test fun `a failed delete restores the row and offers the existing error`() = runTest(main.dispatcher) {
        val backing = FakeItemStore(listOf(item("milk", "milk")))
        val store = object : ItemStore by backing {
            override suspend fun removeAll(rows: List<Pair<ItemId, ItemVersion?>>) { error("disk unavailable") }
        }
        val vm = vm(store)
        vm.onSwipeRemoveRow(vm.state.value.pending.single())
        vm.onCommitSwipeRemoval(vm.state.value.swipeRemoval!!.token)
        assertThat(vm.state.value.pending.single().id).isEqualTo(ItemId("milk"))
        assertThat(vm.state.value.historyError).isTrue()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a rejected delete cannot leave the row permanently hidden`() = runTest(main.dispatcher) {
        val backing = FakeItemStore(listOf(item("milk", "milk")))
        val store = object : ItemStore by backing {
            override suspend fun removeAll(rows: List<Pair<ItemId, ItemVersion?>>) = Unit
        }
        val vm = vm(store)
        vm.onSwipeRemoveRow(vm.state.value.pending.single())
        vm.onCommitSwipeRemoval(vm.state.value.swipeRemoval!!.token)
        advanceUntilIdle()
        assertThat(vm.state.value.pending.single().id).isEqualTo(ItemId("milk"))
        assertThat(vm.state.value.historyError).isTrue()
    }

    @Test fun `switching lists cancels the pending deletion`() = runTest(main.dispatcher) {
        val snapshots = MutableStateFlow(ShoppingSnapshot("one", listOf(item("milk", "milk"))))
        val lists = MutableStateFlow(ListDirectory(selected = "one"))
        val directory = object : ListDirectoryStore {
            override fun observe() = lists
            override suspend fun select(id: String) { snapshots.value = ShoppingSnapshot(id); lists.value = ListDirectory(selected = id) }
            override suspend fun create(name: String) = Unit
        }
        val history = object : ScopedHistoryStore, HistoryStore by NoHistoryStore {
            override fun observeShopping() = snapshots
        }
        val store = FakeItemStore()
        val vm = vm(store, directory, history)
        vm.onSwipeRemoveRow(vm.state.value.pending.single())
        val token = vm.state.value.swipeRemoval!!.token
        vm.onSelectList("two")
        vm.onCommitSwipeRemoval(token)
        assertThat(store.removed).isEmpty()
        assertThat(vm.state.value.swipeRemoval).isNull()
        assertThat(vm.state.value.directory.selected).isEqualTo("two")
    }
}

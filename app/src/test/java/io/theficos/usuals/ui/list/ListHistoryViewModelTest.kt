package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.ui.history.FakeHistoryStore
import io.theficos.usuals.ui.history.InMemoryPromptMemory
import kotlinx.coroutines.flow.MutableStateFlow
import io.theficos.usuals.ui.history.ScopedHistoryStore
import io.theficos.usuals.ui.history.HistoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Rule
import org.junit.Test

class ListHistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val now = 1_700_000_000_000L
    private fun vm(items: FakeItemStore, history: FakeHistoryStore, memory: InMemoryPromptMemory = InMemoryPromptMemory()) =
        ListViewModel(items, LocalUtteranceParser(), FakeSpeechSource(), clock = { now }, historyStore = history, prompts = memory, compute = main.dispatcher)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `buy next time is a durable local view choice and never checks or purchases the row`() = runTest(main.dispatcher) {
        val store = FakeItemStore(listOf(item("milk", "milk")))
        val history = FakeHistoryStore()
        val tools = io.theficos.usuals.ui.shopping.ShoppingToolsStore()
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(), historyStore = history,
            shoppingTools = tools, background = main.dispatcher, compute = main.dispatcher)
        vm.onDefer(vm.state.value.pending.single())
        runCurrent()
        assertThat(vm.state.value.pending).isEmpty()
        assertThat(vm.state.value.deferred.single().checked).isFalse()
        assertThat(history.finishCalls).isEqualTo(0)
        assertThat(history.data.value.purchases).isEmpty()
        history.data.value = HistorySnapshot(trips = listOf(Trip("new-shop", now)))
        runCurrent()
        assertThat(vm.state.value.pending.single().name).isEqualTo("Milk")
        assertThat(vm.state.value.deferred).isEmpty()
    }

    @Test fun `a stale batch is offered only once even after reopening and checking new rows`() = runTest(main.dispatcher) {
        val old = item("old", "milk", checked = true).copy(checkedAt = now - 13 * 60 * 60 * 1000L)
        val items = FakeItemStore(listOf(old, item("open", "bread")))
        val history = FakeHistoryStore()
        val memory = InMemoryPromptMemory()
        val first = vm(items, history, memory)
        assertThat((first.state.value.prompt as ListPrompt.Stale).ids).containsExactly(old.id)
        first.onDismissPrompt()
        first.onOpened()
        assertThat(first.state.value.prompt).isNull()
        val reopened = vm(items, history, memory)
        assertThat(reopened.state.value.prompt).isNull()
        items.add(listOf(item("new", "apples", checked = true).copy(checkedAt = now)))
        assertThat(reopened.state.value.prompt).isNull()
    }

    @Test fun `checking every row offers Finish shop`() = runTest(main.dispatcher) {
        val items = FakeItemStore(listOf(item("milk", "milk")))
        val viewModel = vm(items, FakeHistoryStore())
        viewModel.onCheckedChange(ItemId("milk"), true)
        assertThat(viewModel.state.value.prompt).isEqualTo(ListPrompt.AllChecked)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `three shops since an item was added trigger a carry-over question`() = runTest(main.dispatcher) {
        val items = FakeItemStore(listOf(item("milk", "milk", createdAt = 1000)))
        val history = FakeHistoryStore()
        val viewModel = vm(items, history)
        history.data.value = HistorySnapshot(trips = listOf(Trip("old", 900), Trip("a", 1100), Trip("b", 1200)))
        runCurrent()
        assertThat(viewModel.state.value.prompt).isNull()
        history.data.value = history.data.value.copy(trips = history.data.value.trips + Trip("c", 1300))
        runCurrent()
        val prompt = viewModel.state.value.prompt as ListPrompt.CarryOver
        assertThat(prompt.names).containsExactly("milk")
        viewModel.onDismissPrompt()
        viewModel.onOpened()
        assertThat(viewModel.state.value.prompt).isNull()
        history.data.value = history.data.value.copy(trips = history.data.value.trips + Trip("d", 1400))
        runCurrent()
        assertThat(viewModel.state.value.prompt).isNull()
    }

    @Test fun `double finish is serialized and Undo targets the returned trip`() = runTest(main.dispatcher) {
        val history = FakeHistoryStore().apply { gate = CompletableDeferred() }
        val viewModel = vm(FakeItemStore(listOf(item("milk", "milk", checked = true))), history)
        viewModel.onFinishShop()
        viewModel.onFinishShop()
        assertThat(history.finishCalls).isEqualTo(1)
        assertThat(viewModel.state.value.busy).isTrue()
        history.gate!!.complete(Unit)
        assertThat(viewModel.state.value.finishedTrip!!.trip.id).isEqualTo("trip")
        viewModel.onUndoFinish()
        assertThat(history.undone).isEqualTo("trip")
        assertThat(viewModel.state.value.finishedTrip).isNull()
    }

    @Test fun `stale finish forwards only the selected rows`() = runTest(main.dispatcher) {
        val history = FakeHistoryStore()
        val viewModel = vm(FakeItemStore(), history)
        viewModel.onFinishShop(setOf(ItemId("old")))
        assertThat(history.requestedIds).containsExactly(ItemId("old"))
    }

    @Test fun `swipe removal never closes a shop`() = runTest(main.dispatcher) {
        val history = FakeHistoryStore()
        val items = FakeItemStore(listOf(item("milk", "milk")))
        vm(items, history).onConfirmRemove(ItemId("milk"))
        assertThat(items.current).isEmpty()
        assertThat(history.finishCalls).isEqualTo(0)
    }

    @Test fun `a failed finish keeps the list and presents a recoverable error`() = runTest(main.dispatcher) {
        val items = FakeItemStore(listOf(item("milk", "milk", checked = true)))
        val history = FakeHistoryStore().apply { fail = true }
        val viewModel = vm(items, history)
        viewModel.onFinishShop()
        assertThat(items.current).hasSize(1)
        assertThat(viewModel.state.value.historyError).isTrue()
        assertThat(viewModel.state.value.busy).isFalse()
        assertThat(viewModel.state.value.finishedTrip).isNull()
    }    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `a losing optimistic Finish has no Undo control for the other devices accepted trip`() = runTest(main.dispatcher) {
        val items = FakeItemStore(listOf(item("milk", "milk", checked = true)))
        val history = FakeHistoryStore()
        val snapshots = MutableStateFlow(ShoppingSnapshot(items = items.current, history = HistorySnapshot(trips = listOf(Trip("trip", 10_000)))))
        val scoped = object : ScopedHistoryStore, HistoryStore by history {
            override fun observeShopping() = snapshots
        }
        val vm = ListViewModel(items, LocalUtteranceParser(), FakeSpeechSource(), clock = { now }, historyStore = scoped, compute = main.dispatcher)
        vm.onFinishShop()
        assertThat(vm.state.value.finishedTrip!!.trip.id).isEqualTo("trip")
        snapshots.value = snapshots.value.copy(history = HistorySnapshot(trips = listOf(Trip("accepted-elsewhere", 11_000))))
        runCurrent()
        assertThat(vm.state.value.finishedTrip).isNull()
    }

}

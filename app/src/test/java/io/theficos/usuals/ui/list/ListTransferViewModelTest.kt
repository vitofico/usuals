package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.ui.history.HistoryStore
import io.theficos.usuals.ui.history.NoHistoryStore
import io.theficos.usuals.ui.history.ScopedHistoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ListTransferViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val version = ItemVersion("source", "milk", emptyMap(), "life", null, emptyMap(), emptyMap(), "{}")
    private val original = item("milk", "milk", Quantity.pieces(2)).also { it.version = version }
    private val lists = MutableStateFlow(ListDirectory("source", listOf(NamedList("source", "Groceries", false), NamedList("target", "Weekend", false))))
    private val shopping = MutableStateFlow(ShoppingSnapshot("source", listOf(original)))
    private val requests = mutableListOf<Triple<ItemVersion, String, Boolean>>()
    private var response: suspend () -> ItemTransferResult = { ItemTransferResult.COPIED }

    private fun vm(): ListViewModel {
        val store = object : ItemStore by FakeItemStore(listOf(original)) {
            override suspend fun transfer(source: ItemVersion, destination: String, move: Boolean): ItemTransferResult {
                requests += Triple(source, destination, move)
                return response()
            }
        }
        val history = object : ScopedHistoryStore, HistoryStore by NoHistoryStore {
            override fun observeShopping() = shopping
        }
        val directory = object : ListDirectoryStore by NoListDirectory {
            override fun observe() = lists
        }
        return ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(), clock = { 1_800_000_000_000L }, historyStore = history,
            directoryStore = directory, background = main.dispatcher, compute = main.dispatcher)
    }

    @Test fun `transfer reviews the saved row and pins its source list and version`() = runTest(main.dispatcher) {
        val vm = vm()
        val row = vm.state.value.pending.single()
        vm.onEditRow(row)
        vm.onTransferItem(row)
        assertThat(vm.state.value.editing).isNull()
        assertThat(vm.state.value.transfer?.row?.version).isEqualTo(version)
        assertThat(vm.state.value.transfer?.source?.name).isEqualTo("Groceries")
        vm.onConfirmTransfer("target", false)
        runCurrent()
        assertThat(requests).containsExactly(Triple(version, "target", false))
        assertThat(vm.state.value.transfer).isNull()
        assertThat(vm.state.value.transferResult).isEqualTo(ItemTransferResult.COPIED)
        vm.onDismissTransferResult()
        assertThat(vm.state.value.transferResult).isNull()
    }

    @Test fun `a second confirmation cannot copy twice while the first request is running`() = runTest(main.dispatcher) {
        val gate = CompletableDeferred<ItemTransferResult>()
        response = { gate.await() }
        val vm = vm()
        vm.onTransferItem(vm.state.value.pending.single())
        vm.onConfirmTransfer("target", true)
        vm.onConfirmTransfer("target", true)
        runCurrent()
        assertThat(requests).hasSize(1)
        assertThat(vm.state.value.busy).isTrue()
        gate.complete(ItemTransferResult.MOVED)
        runCurrent()
        assertThat(vm.state.value.busy).isFalse()
        assertThat(vm.state.value.transferResult).isEqualTo(ItemTransferResult.MOVED)
    }

    @Test fun `a destination archived during review is rejected without a write`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onTransferItem(vm.state.value.pending.single())
        lists.value = lists.value.copy(lists = lists.value.lists.map { if (it.id == "target") it.copy(archived = true) else it })
        runCurrent()
        vm.onConfirmTransfer("target", true)
        runCurrent()
        assertThat(requests).isEmpty()
        assertThat(vm.state.value.transferResult).isEqualTo(ItemTransferResult.DESTINATION_UNAVAILABLE)
    }

    @Test fun `a row from another list cannot start a transfer`() = runTest(main.dispatcher) {
        val vm = vm()
        val row = vm.state.value.pending.single().copy(version = version.copy(list = "other"))
        vm.onTransferItem(row)
        assertThat(vm.state.value.transfer).isNull()
        assertThat(requests).isEmpty()
    }

    @Test fun `a changed selection during review cannot transfer the previously selected row`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onTransferItem(vm.state.value.pending.single())
        lists.value = lists.value.copy(selected = "target")
        shopping.value = ShoppingSnapshot("target")
        runCurrent()
        vm.onConfirmTransfer("target", true)
        runCurrent()
        assertThat(requests).isEmpty()
    }

    @Test fun `a partial move is reported explicitly and is not retried automatically`() = runTest(main.dispatcher) {
        response = { ItemTransferResult.SOURCE_KEPT }
        val vm = vm()
        vm.onTransferItem(vm.state.value.pending.single())
        vm.onConfirmTransfer("target", true)
        runCurrent()
        assertThat(vm.state.value.transferResult).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        assertThat(requests).hasSize(1)
        assertThat(vm.state.value.transfer).isNull()
        assertThat(vm.state.value.pending.single().id).isEqualTo(original.id)
    }

    @Test fun `history emissions cannot open another prompt over transfer review or result`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onTransferItem(vm.state.value.pending.single())
        shopping.value = ShoppingSnapshot("source", listOf(original.copy(checked = true, checkedAt = 1_000L)))
        runCurrent()
        assertThat(vm.state.value.prompt).isNull()
        vm.onConfirmTransfer("target", false)
        runCurrent()
        shopping.value = ShoppingSnapshot("source", listOf(original.copy(checked = true, checkedAt = 2_000L)))
        runCurrent()
        assertThat(vm.state.value.transferResult).isEqualTo(ItemTransferResult.COPIED)
        assertThat(vm.state.value.prompt).isNull()
    }
}

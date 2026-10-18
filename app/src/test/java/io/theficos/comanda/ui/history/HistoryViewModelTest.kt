package io.theficos.comanda.ui.history

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.ui.list.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HistoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private fun vm(store: FakeHistoryStore) = HistoryViewModel(store,
        { Instant.parse("2026-10-15T12:00:00Z").toEpochMilli() }, ZoneId.of("UTC"))

    @Test fun `history selections are scoped and a rebuy retains the tapped destination`() = runTest(main.dispatcher) {
        val purchase = io.theficos.comanda.model.Purchase(io.theficos.comanda.model.ItemId("milk"), "trip", "milk", "Milk", boughtAt = 1)
        val snapshots = kotlinx.coroutines.flow.MutableStateFlow(io.theficos.comanda.model.ShoppingSnapshot(listId = "home",
            history = io.theficos.comanda.model.HistorySnapshot(purchases = listOf(purchase))))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var destination: String? = null
        var calls = 0
        val store = object : ScopedHistoryStore, HistoryStore by FakeHistoryStore() {
            override fun observeShopping() = snapshots
            override suspend fun rebuy(rows: List<io.theficos.comanda.model.Purchase>, scope: String): Int {
                destination = scope; calls++; gate.await(); return rows.size
            }
        }
        val vm = HistoryViewModel(store)
        vm.select("milk")
        assertThat(vm.state.value.selected).containsExactly("milk")
        snapshots.value = snapshots.value.copy(listId = "other")
        assertThat(vm.state.value.selected).isEmpty()
        vm.rebuy(); assertThat(calls).isEqualTo(0)
        snapshots.value = snapshots.value.copy(listId = "home")
        vm.select("milk") // Deselect the retained home selection.
        vm.select("milk")
        vm.rebuy(); vm.rebuy()
        assertThat(calls).isEqualTo(1)
        snapshots.value = snapshots.value.copy(listId = "other")
        gate.complete(Unit)
        assertThat(destination).isEqualTo("home")
        assertThat(vm.state.value.notice).isEqualTo(HistoryNotice.REBOUGHT)
        assertThat(vm.state.value.added).isEqualTo(1)
    }

    @Test fun `history search and trip selection match trimmed accents`() = runTest(main.dispatcher) {
        assertThat(matchesHistory("Café au lait", "  cafe ")).isTrue()
        assertThat(matchesHistory("Milk", "bread")).isFalse()
        val store = FakeHistoryStore()
        store.data.value = io.theficos.comanda.model.HistorySnapshot(
            trips = listOf(io.theficos.comanda.model.Trip("past", 1)),
            purchases = listOf(io.theficos.comanda.model.Purchase(io.theficos.comanda.model.ItemId("coffee"), "past", "cafe", "café", boughtAt = 1)))
        val vm = vm(store)
        vm.search(" cafe ")
        vm.selectTrip("past")
        assertThat(vm.state.value.selected).containsExactly("coffee")
    }

    @Test fun `dated import rejects future or invalid dates before writing`() = runTest(main.dispatcher) {
        val store = FakeHistoryStore()
        val vm = vm(store)
        vm.importPast("milk", "2026-10-16")
        assertThat(vm.state.value.notice).isEqualTo(HistoryNotice.INVALID_DATE)
        vm.importPast("milk", "not-a-date")
        assertThat(vm.state.value.notice).isEqualTo(HistoryNotice.INVALID_DATE)
        assertThat(store.pasted).isNull()
        vm.importPast("milk", "2026-01-15")
        assertThat(store.pasted).isEqualTo("milk" to Instant.parse("2026-01-15T00:00:00Z").toEpochMilli())
        assertThat(vm.state.value.notice).isEqualTo(HistoryNotice.IMPORTED)
    }

    @Test fun `invalid archive errors have a structured notice`() = runTest(main.dispatcher) {
        val vm = vm(FakeHistoryStore().apply { fail = true })
        vm.importJson("invalid")
        assertThat(vm.state.value.notice).isEqualTo(HistoryNotice.INVALID_FILE)
    }
}

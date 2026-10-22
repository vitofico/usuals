package io.theficos.usuals.ui.history

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.ui.list.MainDispatcherRule
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
        val purchase = io.theficos.usuals.model.Purchase(io.theficos.usuals.model.ItemId("milk"), "trip", "milk", "Milk", boughtAt = 1)
        val snapshots = kotlinx.coroutines.flow.MutableStateFlow(io.theficos.usuals.model.ShoppingSnapshot(listId = "home",
            history = io.theficos.usuals.model.HistorySnapshot(purchases = listOf(purchase))))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var destination: String? = null
        var calls = 0
        val store = object : ScopedHistoryStore, HistoryStore by FakeHistoryStore() {
            override fun observeShopping() = snapshots
            override suspend fun rebuy(rows: List<io.theficos.usuals.model.Purchase>, scope: String): Int {
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
        val cafe = historyMatcher("  cafe ")
        assertThat(cafe("Café au lait")).isTrue()
        assertThat(cafe("Milk")).isFalse()
        assertThat(historyMatcher("Ñ")("piña")).isTrue()
        assertThat(historyMatcher("  ")("anything")).isTrue()
        val store = FakeHistoryStore()
        store.data.value = io.theficos.usuals.model.HistorySnapshot(
            trips = listOf(io.theficos.usuals.model.Trip("past", 1)),
            purchases = listOf(io.theficos.usuals.model.Purchase(io.theficos.usuals.model.ItemId("coffee"), "past", "cafe", "café", boughtAt = 1)))
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

    @Test fun `a past trip shows its basket overview only while the overview is on`() = runTest(main.dispatcher) {
        val milk = io.theficos.usuals.model.Gtin.parse("4006381333931")!!
        var enabled = false
        val basket = io.theficos.usuals.ui.basket.BasketSource(
            io.theficos.usuals.smart.CategoryClassifier(io.theficos.usuals.parse.InputLanguages.available),
            { gtins -> gtins.associateWith { io.theficos.usuals.enrich.CachedQuality(it,
                io.theficos.usuals.enrich.ProductQuality(novaGroup = io.theficos.usuals.enrich.NovaGroup.UNPROCESSED), false, 0) } },
            enabled = { enabled })
        val store = FakeHistoryStore()
        fun purchase(id: String, name: String, gtin: io.theficos.usuals.model.Gtin? = null) = io.theficos.usuals.model.Purchase(
            io.theficos.usuals.model.ItemId(id), "trip", io.theficos.usuals.parse.Normalizer.normalize(name), name, gtin = gtin, boughtAt = 1)
        store.data.value = io.theficos.usuals.model.HistorySnapshot(trips = listOf(io.theficos.usuals.model.Trip("trip", 1)),
            purchases = listOf(purchase("a", "milk", milk), purchase("b", "bananas"), purchase("c", "soap")))

        assertThat(HistoryViewModel(store, basket = basket).state.value.baskets).isEmpty()

        enabled = true
        val overview = HistoryViewModel(store, basket = basket).state.value.baskets.getValue("trip")
        assertThat(overview.foodItems).isEqualTo(2)
        assertThat(overview.notFood).isEqualTo(1)
        assertThat(overview.processing.unprocessed).isEqualTo(2)
        assertThat(overview.processing.freshProduce).isEqualTo(1)
        // Two known items are too few to describe.
        assertThat(overview.processing.enough).isFalse()
    }
}

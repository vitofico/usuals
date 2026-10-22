package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.smart.HistoryRanker
import io.theficos.usuals.smart.ItemMemory
import io.theficos.usuals.ui.history.FakeHistoryStore
import io.theficos.usuals.ui.shopping.SavedShoppingItem
import io.theficos.usuals.ui.shopping.ShoppingToolsStore
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class MemoryViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val memory = ItemMemory("milk", "milk", 1, 1_000)
    private fun history() = FakeHistoryStore().apply {
        data.value = HistorySnapshot(purchases = listOf(Purchase(ItemId("past"), "trip", "milk", "milk", boughtAt = 1_000)))
    }

    @Test fun `hiding a typing suggestion keeps the draft and manual capture available`() = runTest(main.dispatcher) {
        val history = history()
        val items = FakeItemStore()
        var muted: Pair<String, String>? = null
        val smart = object : SmartStore by NoSmartStore {
            override fun autocomplete(query: String, history: HistorySnapshot, active: List<Item>) = HistoryRanker().autocomplete(query, history)
            override suspend fun muteSuggestion(key: String, scope: String) {
                muted = key to scope
                history.data.value = history.data.value.copy(preferences = listOf(ItemPreference(key, muted = true)))
            }
        }
        val vm = ListViewModel(items, LocalUtteranceParser(), FakeSpeechSource(), clock = { 2_000 },
            historyStore = history, smartStore = smart, compute = main.dispatcher)
        vm.onType()
        vm.onDraftChange("mi")
        assertThat(vm.state.value.autocomplete).containsExactly(memory)
        vm.onMuteAutocomplete(memory)
        assertThat(muted).isEqualTo("milk" to "")
        assertThat(vm.state.value.autocomplete).isEmpty()
        assertThat(vm.state.value.capture).isEqualTo(CaptureState.Typing("mi"))
        assertThat(items.current).isEmpty()
        vm.onDraftChange("milk")
        vm.onDraftSubmit()
        assertThat(items.current.single().name).isEqualTo("milk")
        assertThat(history.data.value.purchases).hasSize(1)
        assertThat(history.data.value.preferences.single().muted).isTrue()
    }

    @Test fun `accepting a completion keeps the typed quantity and clears only the draft`() = runTest(main.dispatcher) {
        val items = FakeItemStore()
        val smart = object : SmartStore by NoSmartStore {
            override fun autocomplete(query: String, history: HistorySnapshot, active: List<Item>) = listOf(memory)
        }
        val vm = ListViewModel(items, LocalUtteranceParser(), FakeSpeechSource(), clock = { 2_000 },
            historyStore = history(), smartStore = smart, compute = main.dispatcher)
        vm.onType()
        vm.onDraftChange("2 l mi")
        vm.onAutocomplete(memory)
        assertThat(items.current.single().name).isEqualTo("milk")
        assertThat(items.current.single().quantity).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))
        assertThat(vm.state.value.capture).isEqualTo(CaptureState.Typing())
    }

    @Test fun `favourites already present are detected outside the visible store filter`() = runTest(main.dispatcher) {
        val items = FakeItemStore(listOf(item("milk", "milk").copy(store = "Market"), item("bread", "bread").copy(store = "Bakery")))
        val history = FakeHistoryStore().apply { data.value = HistorySnapshot(aliases = listOf(ItemAlias("whole milk", "milk"))) }
        val tools = ShoppingToolsStore().apply { pin(SavedShoppingItem("saved-milk", "whole milk", 2.0, "LITRE", store = "Market")) }
        val vm = ListViewModel(items, LocalUtteranceParser(), FakeSpeechSource(), historyStore = history,
            shoppingTools = tools, compute = main.dispatcher)
        vm.onStoreFilter("Bakery")
        assertThat(vm.state.value.pending.map { it.rawName }).containsExactly("bread")
        assertThat(vm.state.value.favoritesOnList).containsExactly("saved-milk")
    }
}

package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.ui.history.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ListColorViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun colorUsesTheReviewedListAndKeepsTheShoppingFilter() = runTest(main.dispatcher) {
        val store = FakeItemStore(listOf(item("milk", "milk").copy(store = "Market"), item("bread", "bread").copy(store = "Bakery")))
        val shown = NamedList("first", "Groceries", false, colorRevision = "reviewed")
        var changed: Triple<String, ListColor?, String?>? = null
        val directory = object : ListDirectoryStore {
            override fun observe() = flowOf(ListDirectory("first", listOf(shown)))
            override suspend fun create(name: String) = Unit
            override suspend fun select(id: String) = Unit
            override suspend fun setColor(id: String, color: ListColor?, expected: String?) { changed = Triple(id, color, expected) }
        }
        val history = object : ScopedHistoryStore, HistoryStore by NoHistoryStore {
            override fun observeShopping() = store.items().map { ShoppingSnapshot("first", it) }
        }
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(), historyStore = history, directoryStore = directory, compute = main.dispatcher)
        vm.onStoreFilter("Market")
        vm.onListColor(shown, ListColor.ROSE)
        assertThat(changed).isEqualTo(Triple("first", ListColor.ROSE, "reviewed"))
        assertThat(vm.state.value.selectedStore).isEqualTo("Market")
        assertThat(vm.state.value.pending.single().id).isEqualTo(ItemId("milk"))
        assertThat(vm.state.value.busy).isFalse()
    }
}

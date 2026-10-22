package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.LocalUtteranceParser
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class StoreFilterTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun `store filters affect shopping rows and can return to the whole living list`() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        store.add(listOf(
            Item(ItemId("a"), "milk", "milk", "milk", store = "market", createdAt = 0, updatedAt = 0),
            Item(ItemId("b"), "bread", "bread", "bread", store = "bakery", createdAt = 0, updatedAt = 0),
        ))
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), compute = main.dispatcher)
        vm.onStoreFilter("market")
        assertThat(vm.state.value.pending.map { it.name }).containsExactly("Milk")
        assertThat(vm.state.value.stores).containsExactly("bakery", "market").inOrder()
        vm.onStoreFilter(null)
        assertThat(vm.state.value.pending).hasSize(2)
    }
}

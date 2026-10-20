package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.smart.ItemMemory
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class AutocompleteTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun `choosing a completion keeps explicit quantity and preceding items`() = runTest(main.dispatcher) {
        val memory = ItemMemory("milk", "milk", 3, 1000)
        val smart = object : SmartStore by NoSmartStore {
            override fun autocomplete(query: String, history: HistorySnapshot, active: List<Item>) = listOf(memory)
        }
        val store = FakeItemStore()
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), smartStore = smart)
        vm.onSpeechUnavailable()
        vm.onDraftChange("bread, 1 l mi")
        vm.onAutocomplete(memory)
        assertThat(store.current.map { it.name }).containsExactly("bread", "milk").inOrder()
        assertThat(store.current.last().quantity).isEqualTo(Quantity(1.0, MeasureUnit.LITRE))
        assertThat(store.current.last().fromSuggestion).isFalse()
    }
}

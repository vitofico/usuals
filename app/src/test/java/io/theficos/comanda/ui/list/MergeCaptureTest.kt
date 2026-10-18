package io.theficos.comanda.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.model.*
import io.theficos.comanda.parse.LocalUtteranceParser
import io.theficos.comanda.smart.ItemMemory
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class MergeCaptureTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun `similar capture waits for a choice and No keeps the original name and amount`() = runTest(main.dispatcher) {
        val smart = object : SmartStore by NoSmartStore {
            override fun similar(name: String, history: HistorySnapshot, active: List<Item>) = ItemMemory("milk", "milk", 3, 0)
        }
        val store = FakeItemStore()
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), smartStore = smart)
        vm.onSharedText("1 l whole milk")
        assertThat(store.current).isEmpty()
        assertThat(vm.state.value.mergeReview!!.incoming.quantity).isEqualTo(Quantity(1.0, MeasureUnit.LITRE))
        vm.onMergeChoice(false)
        assertThat(store.current.single().name).isEqualTo("whole milk")
        assertThat(store.current.single().quantity).isEqualTo(Quantity(1.0, MeasureUnit.LITRE))
        assertThat(vm.state.value.mergeReview).isNull()
    }
}

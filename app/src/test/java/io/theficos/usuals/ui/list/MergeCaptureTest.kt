package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.smart.ItemMemory
import io.theficos.usuals.smart.NameMatch
import io.theficos.usuals.ui.history.FakeHistoryStore
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class MergeCaptureTest {
    @get:Rule val main = MainDispatcherRule()

    private class MatchingStore(val rows: FakeItemStore) : SmartStore by NoSmartStore {
        var calls = 0
        var fail = false
        val remembered = mutableListOf<CaptureMatchChoice>()
        override fun confirmedName(name: String, history: HistorySnapshot, active: List<Item>) =
            io.theficos.usuals.smart.SimilarItems(io.theficos.usuals.smart.HistoryRanker()).confirmedName(name, history, active)
        override fun nameMatches(name: String, history: HistorySnapshot, active: List<Item>) = when (name) {
            "bananna" -> listOf(NameMatch(ItemMemory("banana", "banana", 3, 0), true))
            "yogrut" -> listOf(NameMatch(ItemMemory("yogurt", "yogurt", 2, 0), true))
            else -> emptyList()
        }
        override suspend fun matchCaptures(choices: List<CaptureMatchChoice>, remember: Boolean): List<Item>? {
            calls++
            if (fail) return null
            if (remember) remembered += choices
            return choices.map { choice ->
                val replacement = choice.incoming.copy(name = choice.usual.name, normalizedName = Normalizer.normalize(choice.usual.name))
                rows.replace(replacement)
                replacement
            }
        }
    }

    @Test fun `dictation saves all original items immediately and reviews them as one batch by default`() = runTest(main.dispatcher) {
        val rows = FakeItemStore()
        val smart = MatchingStore(rows)
        val speech = FakeSpeechSource()
        val vm = ListViewModel(rows, LocalUtteranceParser(), speech, compute = main.dispatcher, smartStore = smart)
        vm.onCaptureStart()
        speech.finish("2 kg bananna, 1 l yogrut, oat milk")
        assertThat(rows.current.map { it.name }).containsExactly("bananna", "yogrut", "oat milk").inOrder()
        assertThat(rows.batches).hasSize(1)
        assertThat(vm.state.value.captureMatches).hasSize(2)
        assertThat(vm.state.value.capture).isEqualTo(CaptureState.Idle)
        assertThat(smart.calls).isEqualTo(0)
        val reviews = vm.state.value.captureMatches
        vm.onCaptureMatchSelect(reviews[0].incoming.id, "banana")
        vm.onCaptureMatchSelect(reviews[1].incoming.id, "yogurt")
        vm.onApplyCaptureMatches()
        assertThat(smart.calls).isEqualTo(1)
        assertThat(smart.remembered).hasSize(2)
        assertThat(rows.current.map { it.name }).containsExactly("banana", "yogurt", "oat milk")
        assertThat(rows.current.map { it.quantity }).containsExactly(Quantity(2.0, MeasureUnit.KILOGRAM), Quantity(1.0, MeasureUnit.LITRE), null)
        assertThat(vm.state.value.captureMatches).isEmpty()
    }

    @Test fun `keeping wording remembers negative choices and no match blocks capture`() = runTest(main.dispatcher) {
        val rows = FakeItemStore()
        val smart = MatchingStore(rows)
        val memory = InMemoryCaptureMatchMemory()
        val vm = ListViewModel(rows, LocalUtteranceParser(), FakeSpeechSource(false), compute = main.dispatcher, smartStore = smart, captureMatchMemory = memory)
        vm.onType(); vm.onDraftChange("3 bananna"); vm.onDraftSubmit()
        vm.onKeepCaptureWording()
        assertThat(rows.current.single().quantity).isEqualTo(Quantity.pieces(3))
        assertThat(vm.state.value.captureMatches).isEmpty()
        vm.onDraftChange("bananna, bread"); vm.onDraftSubmit()
        assertThat(vm.state.value.captureMatches).isEmpty()
        assertThat(rows.current.map { it.name }).contains("bread")
        assertThat(smart.calls).isEqualTo(0)
        assertThat(memory.declined("", "bananna", "banana")).isTrue()
        assertThat(memory.declined("another-list", "bananna", "banana")).isFalse()
    }

    @Test fun `automatic matching requires opt in and offers undo without learning an alias`() = runTest(main.dispatcher) {
        val rows = FakeItemStore()
        val smart = MatchingStore(rows)
        val vm = ListViewModel(rows, LocalUtteranceParser(), FakeSpeechSource(false), compute = main.dispatcher, smartStore = smart, automaticMatching = { true })
        vm.onType(); vm.onDraftChange("3 bananna"); vm.onDraftSubmit()
        assertThat(rows.current.single().name).isEqualTo("banana")
        assertThat(rows.current.single().quantity).isEqualTo(Quantity.pieces(3))
        assertThat(smart.remembered).isEmpty()
        val review = vm.state.value.captureMatches.single()
        assertThat(review.matched!!.name).isEqualTo("banana")
        vm.onUndoCaptureMatch(review.incoming.id)
        assertThat(rows.current.single().name).isEqualTo("bananna")
        assertThat(rows.current.single().quantity).isEqualTo(Quantity.pieces(3))
        assertThat(vm.state.value.captureMatches).isEmpty()
    }

    @Test fun `automatic mode leaves an occupied destination for explicit review`() = runTest(main.dispatcher) {
        val rows = FakeItemStore(listOf(item("old", "banana", Quantity.pieces(2))))
        val smart = MatchingStore(rows)
        val vm = ListViewModel(rows, LocalUtteranceParser(), FakeSpeechSource(false), compute = main.dispatcher, smartStore = smart, automaticMatching = { true })
        vm.onType(); vm.onDraftChange("3 bananna"); vm.onDraftSubmit()
        assertThat(rows.current.map { it.quantity }).containsExactly(Quantity.pieces(2), Quantity.pieces(3))
        assertThat(vm.state.value.captureMatches.single().matched).isNull()
        assertThat(smart.calls).isEqualTo(0)
    }

    @Test fun `a failed batch keeps the originals and review available`() = runTest(main.dispatcher) {
        val rows = FakeItemStore()
        val smart = MatchingStore(rows).also { it.fail = true }
        val vm = ListViewModel(rows, LocalUtteranceParser(), FakeSpeechSource(false), compute = main.dispatcher, smartStore = smart)
        vm.onType(); vm.onDraftChange("bananna"); vm.onDraftSubmit()
        vm.onCaptureMatchSelect(rows.current.single().id, "banana")
        vm.onApplyCaptureMatches()
        assertThat(rows.current.single().name).isEqualTo("bananna")
        assertThat(vm.state.value.matchingError).isTrue()
        assertThat(vm.state.value.captureMatches).hasSize(1)
    }
    @Test fun `a confirmed alias uses the remembered name on later capture without copying past quantities`() = runTest(main.dispatcher) {
        val rows = FakeItemStore()
        val history = FakeHistoryStore().also { it.data.value = HistorySnapshot(
            aliases = listOf(ItemAlias("bananna", "banana")),
            purchases = listOf(Purchase(ItemId("past"), "trip", "banana", "banana", Quantity.pieces(10), boughtAt = 1)),
        ) }
        val vm = ListViewModel(rows, LocalUtteranceParser(), FakeSpeechSource(false), compute = main.dispatcher, smartStore = MatchingStore(rows), historyStore = history)
        vm.onType(); vm.onDraftChange("3 bananna"); vm.onDraftSubmit()
        assertThat(rows.current.single().name).isEqualTo("banana")
        assertThat(rows.current.single().quantity).isEqualTo(Quantity.pieces(3))
        assertThat(rows.current.single().rawText).isEqualTo("3 bananna")
        assertThat(vm.state.value.captureMatches).isEmpty()
    }

}

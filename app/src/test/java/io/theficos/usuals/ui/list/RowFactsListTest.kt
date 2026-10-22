package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.CachedQuality
import io.theficos.usuals.enrich.NutriScore
import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.enrich.ProductType
import io.theficos.usuals.enrich.QualityLookup
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.HistorySnapshot
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.Purchase
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.smart.AllergenMatch
import io.theficos.usuals.ui.history.FakeHistoryStore
import io.theficos.usuals.ui.product.ProductInfoStore
import io.theficos.usuals.ui.product.RowFactsChoices
import io.theficos.usuals.ui.product.RowFactsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Rows show what the product cache already holds, follow both switches, and never fetch. */
class RowFactsListTest {
    @get:Rule val main = MainDispatcherRule()

    private val milk = Gtin.parse("4006381333931")!!
    private val pesto = Gtin.parse("4000000000020")!!
    private val beer = Gtin.parse("4000000000013")!!

    private class FakeRowFacts : RowFactsSource {
        override val lookupEnabled = MutableStateFlow(true)
        override val choices = MutableStateFlow(RowFactsChoices())
        val cache = MutableStateFlow<Map<Gtin, ProductQuality>>(emptyMap())
        val asked = mutableListOf<Set<Gtin>>()
        override fun cached(gtins: Set<Gtin>): Flow<Map<Gtin, ProductQuality>> {
            asked += gtins
            return cache.map { it.filterKeys(gtins::contains) }
        }
    }

    /** Counts every request for Open Food Facts; drawing the list must make none. */
    private var refreshes = 0
    private val noNetwork = object : ProductInfoStore {
        override fun enabled() = true
        override suspend fun cached(gtin: Gtin): CachedQuality? = null
        override suspend fun refresh(gtin: Gtin): QualityLookup { refreshes++; return QualityLookup.Missing(gtin) }
    }

    private fun vm(facts: FakeRowFacts): ListViewModel {
        val items = FakeItemStore(listOf(
            item("milk", "milk").copy(gtin = milk),
            item("pesto", "pesto", position = 1.0),
            item("bread", "bread", position = 2.0),
            item("beer", "beer", position = 3.0).copy(gtin = beer),
        ))
        val history = FakeHistoryStore().apply {
            data.value = HistorySnapshot(purchases = listOf(
                Purchase(ItemId("p1"), "t1", Normalizer.normalize("pesto"), "pesto", gtin = pesto, boughtAt = 5L)))
        }
        return ListViewModel(items, LocalUtteranceParser(), FakeSpeechSource(false), clock = { 10L }, historyStore = history,
            productInfoStore = noNetwork, rowFacts = facts, compute = main.dispatcher)
    }

    private fun ListViewModel.grades() = state.value.pending.associate { it.rawName to it.grade }

    @Test fun `own and remembered barcodes get their cached grade, uncached rows stay bare`() = runTest(main.dispatcher) {
        val facts = FakeRowFacts()
        facts.cache.value = mapOf(
            milk to ProductQuality(nutriScore = NutriScore.B),
            pesto to ProductQuality(nutriScore = NutriScore.D),
            beer to ProductQuality(nutriScoreNotApplicable = true),
        )
        val vm = vm(facts)
        assertThat(vm.grades()).containsExactly("milk", NutriScore.B, "pesto", NutriScore.D, "bread", null, "beer", null)
        // Only the rows' own barcodes are read; the typed row has none to ask about.
        assertThat(facts.asked.last()).containsExactly(milk, pesto, beer)
        assertThat(refreshes).isEqualTo(0)
    }

    @Test fun `a grade saved later reaches the row without a list change`() = runTest(main.dispatcher) {
        val facts = FakeRowFacts()
        val vm = vm(facts)
        assertThat(vm.grades()["milk"]).isNull()
        facts.cache.value = mapOf(milk to ProductQuality(nutriScore = NutriScore.A))
        assertThat(vm.grades()["milk"]).isEqualTo(NutriScore.A)
    }

    @Test fun `the grades switch and the lookup opt-in each hide the chips`() = runTest(main.dispatcher) {
        val facts = FakeRowFacts()
        facts.cache.value = mapOf(milk to ProductQuality(nutriScore = NutriScore.B))
        val vm = vm(facts)
        facts.choices.value = RowFactsChoices(showGrades = false)
        assertThat(vm.grades()["milk"]).isNull()
        facts.choices.value = RowFactsChoices(showGrades = true)
        assertThat(vm.grades()["milk"]).isEqualTo(NutriScore.B)
        facts.lookupEnabled.value = false
        assertThat(vm.grades()["milk"]).isNull()
        assertThat(refreshes).isEqualTo(0)
    }

    @Test fun `a non-food record shows no grade`() = runTest(main.dispatcher) {
        val facts = FakeRowFacts()
        facts.cache.value = mapOf(milk to ProductQuality(nutriScore = NutriScore.A, productType = ProductType.BEAUTY))
        assertThat(vm(facts).grades()["milk"]).isNull()
    }

    @Test fun `avoided allergens flag rows by their own or remembered barcode, unknown and none listed stay quiet`() = runTest(main.dispatcher) {
        val facts = FakeRowFacts()
        facts.choices.value = RowFactsChoices(showGrades = false, avoid = setOf("en:milk", "en:nuts"))
        facts.cache.value = mapOf(
            milk to ProductQuality(allergens = listOf("en:milk")),
            pesto to ProductQuality(allergens = listOf("en:milk"), traces = listOf("en:nuts")),
            beer to ProductQuality(allergens = null),
        )
        val vm = vm(facts)
        val rows = vm.state.value.pending.associate { it.rawName to it.allergens }
        assertThat(rows["milk"]).isEqualTo(AllergenMatch(contains = listOf("en:milk"), allergensStated = true))
        assertThat(rows["pesto"]).isEqualTo(AllergenMatch(listOf("en:milk"), listOf("en:nuts"), allergensStated = true))
        assertThat(rows["beer"]).isNull()
        assertThat(rows["bread"]).isNull()
        facts.cache.value = mapOf(milk to ProductQuality(allergens = emptyList()))
        assertThat(vm.state.value.pending.single { it.rawName == "milk" }.allergens).isNull()
        facts.lookupEnabled.value = false
        facts.cache.value = mapOf(milk to ProductQuality(allergens = listOf("en:milk")))
        assertThat(vm.state.value.pending.single { it.rawName == "milk" }.allergens).isNull()
        assertThat(refreshes).isEqualTo(0)
    }

    @Test fun `the product sheet knows what this phone avoids`() = runTest(main.dispatcher) {
        val facts = FakeRowFacts()
        facts.choices.value = RowFactsChoices(avoid = setOf("en:milk"))
        val vm = vm(facts)
        vm.onProductInfo(vm.state.value.pending.single { it.rawName == "milk" })
        assertThat(vm.state.value.productInfo!!.avoid).containsExactly("en:milk")
    }

    @Test fun `opening the sheet from a chip uses the remembered barcode`() = runTest(main.dispatcher) {
        val facts = FakeRowFacts()
        facts.cache.value = mapOf(pesto to ProductQuality(nutriScore = NutriScore.D))
        val vm = vm(facts)
        val row = vm.state.value.pending.single { it.rawName == "pesto" }
        vm.onProductInfo(row)
        assertThat(vm.state.value.productInfo!!.gtin).isEqualTo(pesto)
    }
}

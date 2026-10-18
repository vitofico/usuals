package io.theficos.comanda.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.di.RepositoryProductInfoStore
import io.theficos.comanda.enrich.*
import io.theficos.comanda.model.Gtin
import io.theficos.comanda.model.ItemId
import io.theficos.comanda.model.Item
import io.theficos.comanda.parse.LocalUtteranceParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ProductInfoTest {
    @get:Rule val main = MainDispatcherRule()
    private val gtin = Gtin.parse("4006381333931")!!
    private val row = ListRow(ItemId("saved"), "My own name", gtin = gtin)
    @Test fun `stale details appear before refresh and survive a failed refresh`() = runTest(main.dispatcher) {
        val cache = InMemoryQualityCache(clock = { 0L })
        cache.put(QualityLookup.Found(gtin, ProductQuality(productName = "Saved product", traces = emptyList())), 1)
        val gate = CompletableDeferred<Unit>()
        val reader = RepositoryProductInfoStore(cache, object : ProductEnricher {
            override suspend fun lookup(gtin: Gtin): QualityLookup { gate.await(); return QualityLookup.Failed(gtin, FailureReason.TIMEOUT) }
        }, { true }, { FreshnessPolicy.THIRTY_DAYS + 2 })
        val items = FakeItemStore(listOf(Item(row.id, row.name, row.name, row.name, gtin = gtin, createdAt = 0, updatedAt = 0)))
        val vm = ListViewModel(items, LocalUtteranceParser(), FakeSpeechSource(false),
            clock = { FreshnessPolicy.THIRTY_DAYS + 2 }, productInfoStore = reader)
        vm.onProductInfo(row)
        val waiting = vm.state.value.productInfo!!
        assertThat(waiting.loading).isTrue()
        assertThat(waiting.stale).isTrue()
        assertThat(waiting.cached!!.quality!!.productName).isEqualTo("Saved product")
        gate.complete(Unit)
        val finished = vm.state.value.productInfo!!
        assertThat(finished.loading).isFalse()
        assertThat(finished.result).isEqualTo(QualityLookup.Failed(gtin, FailureReason.TIMEOUT))
        assertThat(finished.cached).isEqualTo(waiting.cached)
        assertThat(items.current.single().name).isEqualTo("My own name")
    }
    @Test fun `fresh details avoid network until explicit refresh and dismissal ignores late results`() = runTest(main.dispatcher) {
        val cache = InMemoryQualityCache(clock = { 0L })
        cache.put(QualityLookup.Found(gtin, ProductQuality(productName = "Saved product")), 10)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val reader = RepositoryProductInfoStore(cache, object : ProductEnricher {
            override suspend fun lookup(gtin: Gtin): QualityLookup { calls++; gate.await(); return QualityLookup.Missing(gtin) }
        }, { true }, { 11 })
        val vm = ListViewModel(FakeItemStore(), LocalUtteranceParser(), FakeSpeechSource(false), clock = { 11 }, productInfoStore = reader)
        vm.onProductInfo(row)
        assertThat(vm.state.value.productInfo!!.loading).isFalse()
        assertThat(calls).isEqualTo(0)
        vm.onRefreshProduct()
        assertThat(calls).isEqualTo(1)
        vm.onDismissProduct()
        gate.complete(Unit)
        assertThat(vm.state.value.productInfo).isNull()
    }
}

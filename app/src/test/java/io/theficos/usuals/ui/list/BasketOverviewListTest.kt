package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.CachedQuality
import io.theficos.usuals.enrich.NovaGroup
import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.Item
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.smart.CategoryClassifier
import io.theficos.usuals.ui.basket.BasketSource
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class BasketOverviewListTest {
    @get:Rule val main = MainDispatcherRule()
    private var enabled = false
    private var reads = 0
    private val codes = (1..9).map { Gtin.parse(ean13("40000000000$it"))!! }
    private val source = BasketSource(CategoryClassifier(InputLanguages.available), { gtins ->
        reads++
        gtins.associateWith { CachedQuality(it, ProductQuality(novaGroup = NovaGroup.ULTRA_PROCESSED), false, 0) }
    }, enabled = { enabled })

    private fun item(name: String, gtin: Gtin? = null) =
        Item(ItemId(name), name, name, Normalizer.normalize(name), gtin = gtin, createdAt = 0, updatedAt = 0)
    private fun vm(items: List<Item>) = ListViewModel(FakeItemStore(items), LocalUtteranceParser(), FakeSpeechSource(false),
        clock = { 0 }, compute = main.dispatcher, basket = source)

    @Test fun `with the overview off the menu entry is hidden and opening it reads nothing`() = runTest(main.dispatcher) {
        val vm = vm(listOf(item("crisps", codes[0])))
        assertThat(vm.state.value.basketAvailable).isFalse()
        vm.onBasketOverview()
        assertThat(vm.state.value.basket).isNull()
        assertThat(reads).isEqualTo(0)
    }

    @Test fun `the entry follows the setting when the list comes back into view`() = runTest(main.dispatcher) {
        val vm = vm(listOf(item("crisps", codes[0])))
        enabled = true
        vm.onOpened()
        assertThat(vm.state.value.basketAvailable).isTrue()
        vm.onBasketOverview()
        assertThat(vm.state.value.basket?.overview).isNotNull()

        // Turned off in Settings while the sheet was open: back on the list, both go.
        enabled = false
        vm.onOpened()
        assertThat(vm.state.value.basketAvailable).isFalse()
        assertThat(vm.state.value.basket).isNull()
    }

    @Test fun `a short list opens to not enough data with its coverage`() = runTest(main.dispatcher) {
        enabled = true
        val vm = vm(listOf(item("crisps", codes[0]), item("cola", codes[1]), item("bread"), item("cheese")))
        vm.onBasketOverview()
        val overview = vm.state.value.basket!!.overview!!
        assertThat(overview.processing.enough).isFalse()
        assertThat(overview.processing.known).isEqualTo(2)
        assertThat(overview.processing.eligible).isEqualTo(4)
        assertThat(reads).isEqualTo(1)
        vm.onDismissBasket()
        assertThat(vm.state.value.basket).isNull()
    }

    @Test fun `a well known list opens to its processing mix`() = runTest(main.dispatcher) {
        enabled = true
        val vm = vm(codes.mapIndexed { i, code -> item("snack $i", code) } + item("tomatoes"))
        vm.onBasketOverview()
        val mix = vm.state.value.basket!!.overview!!.processing
        assertThat(mix.enough).isTrue()
        assertThat(mix.ultraProcessed).isEqualTo(9)
        assertThat(mix.freshProduce).isEqualTo(1)
    }

    private fun ean13(twelve: String): String {
        val sum = twelve.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return twelve + (10 - sum % 10) % 10
    }
}

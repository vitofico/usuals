package io.theficos.usuals.ui.basket

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.CachedQuality
import io.theficos.usuals.enrich.NovaGroup
import io.theficos.usuals.enrich.NutriScore
import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.HistorySnapshot
import io.theficos.usuals.model.Item
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.ItemPreference
import io.theficos.usuals.model.Purchase
import io.theficos.usuals.model.Trip
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.smart.CategoryClassifier
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BasketSourceTest {
    private val milk = Gtin.parse("4006381333931")!!
    private val cola = Gtin.parse("4000000000013")!!
    private val unknown = Gtin.parse("4000000000020")!!
    private val asked = mutableListOf<Set<Gtin>>()
    private val cache = mapOf(
        milk to ProductQuality(novaGroup = NovaGroup.UNPROCESSED, nutriScore = NutriScore.A),
        cola to ProductQuality(novaGroup = NovaGroup.ULTRA_PROCESSED, nutriScore = NutriScore.E),
    )
    private val source = BasketSource(CategoryClassifier(InputLanguages.available), { gtins ->
        asked += gtins.toSet()
        gtins.mapNotNull { gtin -> cache[gtin]?.let { gtin to CachedQuality(gtin, it, false, 0) } }.toMap()
    }, enabled = { true })

    private fun item(name: String, gtin: Gtin? = null) =
        Item(ItemId(name), name, name, Normalizer.normalize(name), gtin = gtin, createdAt = 0, updatedAt = 0)
    private fun purchase(id: String, trip: String, name: String, gtin: Gtin? = null, at: Long = 1) =
        Purchase(ItemId(id), trip, Normalizer.normalize(name), name, gtin = gtin, boughtAt = at)

    @Test fun `a list reads its own and remembered barcodes from the cache in one batch`() = runTest {
        val history = HistorySnapshot(trips = listOf(Trip("past", 1)), purchases = listOf(purchase("p1", "past", "milk", milk)))
        val overview = source.forList(listOf(item("milk"), item("cola", cola), item("crisps", unknown), item("tomatoes")), history)

        assertThat(asked).containsExactly(setOf(milk, cola, unknown))
        assertThat(overview.foodItems).isEqualTo(4)
        // Remembered milk, scanned cola and produce by name; the crisps' barcode has nothing saved.
        assertThat(overview.processing.unprocessed).isEqualTo(2)
        assertThat(overview.processing.freshProduce).isEqualTo(1)
        assertThat(overview.processing.ultraProcessed).isEqualTo(1)
        assertThat(overview.processing.known).isEqualTo(3)
        assertThat(overview.nutriScore.counts.getValue(NutriScore.A)).isEqualTo(1)
        assertThat(overview.nutriScore.eligible).isEqualTo(3)
    }

    @Test fun `a category the person chose decides food and produce`() = runTest {
        val history = HistorySnapshot(preferences = listOf(
            ItemPreference(Normalizer.normalize("tomatoes"), category = "SNACKS"),
            ItemPreference(Normalizer.normalize("candles"), category = "HOUSEHOLD"),
        ))
        val overview = source.forList(listOf(item("tomatoes"), item("candles")), history)
        assertThat(overview.notFood).isEqualTo(1)
        assertThat(overview.processing.freshProduce).isEqualTo(0)
    }

    @Test fun `each trip reads its purchases' barcode, else the one remembered for the key`() = runTest {
        val history = HistorySnapshot(
            trips = listOf(Trip("first", 1), Trip("second", 2)),
            purchases = listOf(
                purchase("a", "first", "milk"),
                purchase("b", "first", "shampoo"),
                purchase("c", "second", "milk", milk, at = 2),
                purchase("d", "second", "cola", cola, at = 2),
            ),
        )
        val trips = source.forTrips(history)

        assertThat(asked).containsExactly(setOf(milk, cola))
        assertThat(trips.keys).containsExactly("first", "second")
        assertThat(trips.getValue("first").processing.unprocessed).isEqualTo(1)
        assertThat(trips.getValue("first").notFood).isEqualTo(1)
        assertThat(trips.getValue("second").processing.known).isEqualTo(2)
        assertThat(trips.getValue("second").nutriScore.counts.getValue(NutriScore.E)).isEqualTo(1)
    }
}

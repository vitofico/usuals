package io.theficos.usuals.smart

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import org.junit.Test

class ShoppingStatsTest {
    private fun purchase(id: String, trip: String, key: String, at: Long, name: String = key) =
        Purchase(ItemId(id), trip, key, name, boughtAt = at)

    @Test fun `empty history has no invented trends`() {
        assertThat(shoppingStats(100, HistorySnapshot())).isEqualTo(ShoppingStats())
    }

    @Test fun `saved rows remain a count while aliases share one distinct item and shop frequency`() {
        val history = HistorySnapshot(trips = listOf(Trip("first", 10), Trip("second", 20)), purchases = listOf(
            purchase("a", "first", "milk", 10), purchase("b", "first", "leche", 10),
            purchase("c", "second", "leche", 20, "Leche"), purchase("d", "second", "bread", 20)),
            aliases = listOf(ItemAlias("leche", "milk")))
        val stats = shoppingStats(100, history)
        assertThat(stats.shopCount).isEqualTo(2)
        assertThat(stats.purchaseCount).isEqualTo(4)
        assertThat(stats.differentItems).isEqualTo(2)
        assertThat(stats.recentShopCount).isEqualTo(2)
        assertThat(stats.usuals.first()).isEqualTo(ShoppingUsual("milk", "Leche", 2, 20))
        assertThat(history.purchases).hasSize(4)
    }

    @Test fun `future and orphan purchases cannot affect saved history facts`() {
        val history = HistorySnapshot(trips = listOf(Trip("past", 10), Trip("future", 200)), purchases = listOf(
            purchase("a", "past", "milk", 10), purchase("b", "past", "bread", 101),
            purchase("c", "future", "eggs", 90), purchase("d", "orphan", "soap", 10)))
        val stats = shoppingStats(100, history)
        assertThat(stats).isEqualTo(ShoppingStats(1, 1, 1, 1, listOf(ShoppingUsual("milk", "milk", 1, 10))))
    }

    @Test fun `recent usuals use eight shops with stable five-item ordering and do not hide all-time totals`() {
        val trips = (0..9).map { Trip("trip-$it", it.toLong()) }
        val history = HistorySnapshot(trips, listOf(purchase("old", "trip-0", "old", 0)) +
            (2..9).flatMap { index -> listOf("milk", "bread", "tea", "soap", "eggs", "apples").map {
                purchase("$index-$it", "trip-$index", it, index.toLong())
            } })
        val stats = shoppingStats(100, history)
        assertThat(stats.shopCount).isEqualTo(10)
        assertThat(stats.purchaseCount).isEqualTo(49)
        assertThat(stats.differentItems).isEqualTo(7)
        assertThat(stats.recentShopCount).isEqualTo(8)
        assertThat(stats.usuals.map { it.key }).containsExactly("apples", "bread", "eggs", "milk", "soap").inOrder()
        assertThat(stats.usuals.map { it.shopCount }.distinct()).containsExactly(8)
        assertThat(shoppingStats(100, history.copy(trips = trips.reversed(), purchases = history.purchases.reversed()))).isEqualTo(stats)
    }

    @Test fun `a confirmed barcode alias counts with its named item`() {
        val history = HistorySnapshot(trips = listOf(Trip("trip", 10)), purchases = listOf(
            purchase("a", "trip", "milk", 10), purchase("b", "trip", "other", 10).copy(gtin = Gtin.parse("4006381333931"))),
            aliases = listOf(ItemAlias("gtin:4006381333931", "milk")))
        assertThat(shoppingStats(100, history).differentItems).isEqualTo(1)
        assertThat(shoppingStats(100, history).usuals.single().shopCount).isEqualTo(1)
    }
}

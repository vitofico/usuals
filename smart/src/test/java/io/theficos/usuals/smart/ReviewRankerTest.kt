package io.theficos.usuals.smart

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import org.junit.Test

class ReviewRankerTest {
    private val day = RepeatPlanner.DAY_MILLIS
    private val ranker = ReviewRanker()
    private fun history(vararg shops: Pair<Int, List<String>>): HistorySnapshot {
        val trips = shops.mapIndexed { index, (at, _) -> Trip("trip-$index", at * day) }
        return HistorySnapshot(trips, shops.flatMapIndexed { index, (at, names) -> names.mapIndexed { row, name ->
            Purchase(ItemId("$index-$row"), trips[index].id, name, name, boughtAt = at * day)
        } })
    }

    @Test fun `frequency then recent shop coverage then elapsed time and key order the review`() {
        val facts = history(1 to listOf("milk", "bread", "eggs", "soap"), 8 to listOf("milk", "bread", "eggs"),
            15 to listOf("milk", "bread"), 22 to listOf("milk"))
        val result = ranker.rank(30 * day, facts, emptyList())
        assertThat(result.map { it.name }).containsExactly("milk", "bread", "eggs").inOrder()
        assertThat(result.first().shopCount).isEqualTo(4)
        assertThat(result.first().windowSize).isEqualTo(4)
        val tied = history(1 to listOf("bread", "apple"), 8 to listOf("bread", "apple"))
        assertThat(ranker.rank(20 * day, tied, emptyList()).map { it.key }).containsExactly("apple", "bread").inOrder()
        val age = history(1 to listOf("eggs", "milk"), 8 to listOf("eggs"), 15 to listOf("milk"))
        assertThat(ranker.rank(30 * day, age, emptyList()).map { it.name }).containsExactly("milk", "eggs").inOrder()
        val coverage = history(1 to listOf("old", "old"), 8 to listOf("old"), 15 to listOf("old"),
            22 to listOf("new"), 29 to listOf("new"))
        assertThat(ranker.rank(40 * day, coverage, emptyList()).map { it.name }).containsExactly("old", "new").inOrder()
        val equalCoverage = history(1 to listOf("a"), 8 to listOf("b"), 15 to listOf("b"), 22 to listOf("a"))
        assertThat(ranker.rank(40 * day, equalCoverage, emptyList()).map { it.name }).containsExactly("b", "a").inOrder()
    }

    @Test fun `aliases deduplicate trips and checked rows also exclude candidates`() {
        val facts = history(1 to listOf("milk", "whole milk", "bread"), 8 to listOf("whole milk", "bread"))
            .copy(aliases = listOf(ItemAlias("whole milk", "milk")))
        val result = ranker.rank(20 * day, facts, emptyList())
        assertThat(result.single { it.key == "milk" }.shopCount).isEqualTo(2)
        val checked = Item(ItemId("on-list"), "milk", "milk", "milk", checked = true, createdAt = 0, updatedAt = 0)
        assertThat(ranker.rank(20 * day, facts, listOf(checked)).map { it.key }).containsExactly("bread")
    }

    @Test fun `recent purchases rules muted and snoozed items stay out`() {
        val facts = history(1 to listOf("milk", "bread", "eggs", "soap", "tea"),
            8 to listOf("milk", "bread", "eggs", "soap", "tea")).copy(preferences = listOf(
                ItemPreference("milk", repeatEveryDays = 7), ItemPreference("bread", muted = true),
                ItemPreference("eggs", snoozedUntil = 8 * day), ItemPreference("soap", snoozedUntil = 7 * day)))
        assertThat(ranker.rank(9 * day - 1, facts, emptyList())).isEmpty()
        assertThat(ranker.rank(9 * day, facts, emptyList()).map { it.key }).containsExactly("soap", "tea")
        val next = facts.copy(trips = facts.trips + Trip("later", 10 * day))
        assertThat(ranker.rank(11 * day, next, emptyList()).map { it.key }).containsExactly("eggs", "soap", "tea")
    }

    @Test fun `small histories fall back only to the latest shop`() {
        assertThat(ranker.rank(20 * day, HistorySnapshot(), emptyList())).isEmpty()
        val facts = history(1 to listOf("old"), 8 to listOf("new"))
        assertThat(ranker.rank(20 * day, facts, emptyList()).map { it.key }).containsExactly("new")
        val third = facts.copy(trips = facts.trips + Trip("third", 12 * day))
        assertThat(ranker.rank(20 * day, third, emptyList())).isEmpty()
    }

    @Test fun `only eight finished shops count and one shop cannot inflate frequency`() {
        val facts = history(*(0..9).map { it * 7 to (if (it < 2) listOf("old") else listOf("milk", "milk")) }.toTypedArray())
        val result = ranker.rank(80 * day, facts, emptyList()).single()
        assertThat(result.key).isEqualTo("milk")
        assertThat(result.shopCount).isEqualTo(8)
        assertThat(result.windowSize).isEqualTo(8)
        assertThat(ranker.rank(80 * day, history(1 to listOf("milk", "milk"), 8 to emptyList(), 15 to emptyList()), emptyList())).isEmpty()
    }

    @Test fun `future trips cannot displace history or supply candidates`() {
        val facts = history(1 to listOf("milk"), 8 to listOf("milk"), 40 to listOf("future"))
        assertThat(ranker.rank(20 * day, facts, emptyList()).single().key).isEqualTo("milk")
    }
}

package io.theficos.comanda.smart

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.model.*
import io.theficos.comanda.parse.Normalizer
import org.junit.Test

class SuggestionTest {
    private val day = RepeatPlanner.DAY_MILLIS
    private val ranker = HistoryRanker()
    private val intervals = IntervalModel()
    private val engine = SuggestionEngine(RepeatPlanner(), intervals, ranker)
    private fun facts(names: List<List<String>>, spacing: Int = 7): HistorySnapshot {
        val trips = names.indices.map { Trip("t$it", it * spacing * day) }
        return HistorySnapshot(trips, names.flatMapIndexed { index, row -> row.mapIndexed { n, name ->
            Purchase(ItemId("p$index-$n"), "t$index", Normalizer.normalize(name), name, boughtAt = trips[index].closedAt)
        } })
    }

    @Test fun `staples count distinct trips in the latest eight and cap the row at six`() {
        val common = (0..8).map { "item $it" }
        val history = facts(listOf(listOf("old only")) + (0..7).map { if (it < 4) common + "rare" else common })
        assertThat(engine.staples(history).map { it.name }).doesNotContain("old only")
        assertThat(engine.staples(history).map { it.name }).contains("rare")
        assertThat(engine.rank(100 * day, history, emptyList())).hasSize(6)
        val duplicates = facts(listOf(List(8) { "milk" }, listOf("bread"), listOf("bread"), listOf("bread")))
        assertThat(engine.staples(duplicates).map { it.name }).containsExactly("bread")
    }

    @Test fun `active aliases Never and Not now suppress suggestions until a later close`() {
        val history = facts(List(4) { listOf("milk", "bread", "eggs") }).copy(
            aliases = listOf(ItemAlias("whole milk", "milk")),
            preferences = listOf(ItemPreference("bread", muted = true), ItemPreference("eggs", snoozedUntil = 30 * day)),
        )
        val active = Item(ItemId("active"), "whole milk", "whole milk", "whole milk", createdAt = 0, updatedAt = 0)
        assertThat(engine.rank(30 * day, history, listOf(active))).isEmpty()
        val next = history.copy(trips = history.trips + Trip("next", 31 * day))
        assertThat(engine.rank(31 * day, next, listOf(active)).map { it.name }).containsExactly("eggs")
    }

    @Test fun `interval predictions require three purchases regular gaps and no dormancy`() {
        val regular = facts(List(3) { listOf("milk") })
        assertThat(intervals.due(20 * day, regular)).isEmpty()
        assertThat(intervals.due(21 * day, regular).single().days).isEqualTo(7)
        assertThat(intervals.due(40 * day, regular)).isEmpty()
        assertThat(intervals.estimates(facts(List(2) { listOf("milk") }))).isEmpty()
        assertThat(intervals.estimates(facts(List(4) { listOf("milk") }, spacing = 2))).isEmpty()
        val irregular = regular.copy(purchases = regular.purchases.mapIndexed { i, p -> p.copy(boughtAt = listOf(0L, day, 40 * day)[i]) })
        assertThat(intervals.estimates(irregular)).isEmpty()
        assertThat(engine.rank(21 * day, regular, emptyList()).single().reason).isEqualTo(SuggestionReason.STAPLE)
        assertThat(engine.rank(21 * day, regular, emptyList(), true).single().reason).isEqualTo(SuggestionReason.INTERVAL)
    }

    @Test fun `due explicit rules rank first even with no history`() {
        val history = facts(List(8) { listOf("bread") }).copy(preferences = listOf(ItemPreference("milk", repeatEveryDays = 21)))
        assertThat(engine.rank(60 * day, history, emptyList()).map { it.reason })
            .containsExactly(SuggestionReason.REPEAT, SuggestionReason.STAPLE).inOrder()
    }

    @Test fun `replay withholds future trips and reports both baselines without enabling anything`() {
        val history = facts(List(12) { listOf("milk") })
        val report = ReplayHarness(engine, ranker).evaluate(history)
        assertThat(report.trips).isEqualTo(9)
        assertThat(report.candidate.hits).isEqualTo(9)
        assertThat(report.lastTrip.hits).isEqualTo(9)
        assertThat(report.staples.hits).isEqualTo(9)
        assertThat(report.candidate.slots).isEqualTo(54)
        assertThat(report.beatsBoth).isFalse()
        val unseen = facts(List(12) { listOf("unique $it") })
        assertThat(ReplayHarness(engine, ranker).evaluate(unseen).candidate.hits).isEqualTo(0)
        val equalDates = history.copy(trips = history.trips.map { it.copy(closedAt = 0) })
        assertThat(ReplayHarness(engine, ranker).evaluate(equalDates).trips).isEqualTo(0)
    }
}

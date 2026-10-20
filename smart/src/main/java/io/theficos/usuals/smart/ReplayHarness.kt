package io.theficos.usuals.smart

import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ItemIdentity

data class ReplayScore(val hits: Int, val slots: Int) {
    val precisionAtSix: Double get() = if (slots == 0) 0.0 else hits.toDouble() / slots
}
data class ReplayReport(val trips: Int, val candidate: ReplayScore, val lastTrip: ReplayScore, val staples: ReplayScore) {
    /** A win is evidence for review, never an automatic rollout switch. */
    val beatsBoth: Boolean get() = trips >= 8 && candidate.hits > lastTrip.hits && candidate.hits > staples.hits
}

/** Walk-forward evaluation. A held-out trip and later purchases never enter its training set. */
class ReplayHarness(private val engine: SuggestionEngine, private val ranker: HistoryRanker) {
    fun evaluate(history: HistorySnapshot): ReplayReport {
        val ordered = history.trips.sortedWith(compareBy<Trip> { it.closedAt }.thenBy { it.id })
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        var count = 0
        var candidate = 0
        var previous = 0
        var staples = 0
        for (trip in ordered) {
            // Equal timestamps are withheld together to prevent import ordering leaking the target.
            val earlier = ordered.filter { it.closedAt < trip.closedAt }
            if (earlier.size < 3) continue
            val ids = earlier.map { it.id }.toSet()
            val training = HistorySnapshot(earlier, history.purchases.filter { it.tripId in ids && it.boughtAt < trip.closedAt },
                aliases = history.aliases)
            val actual = history.purchases.filter { it.tripId == trip.id }.map { ItemIdentity.purchaseKey(it, aliases) }.toSet()
            val lastId = earlier.last().id
            val lastKeys = training.purchases.filter { it.tripId == lastId }.map { ItemIdentity.purchaseKey(it, aliases) }.toSet()
            val lastRanked = ranker.memories(training).filter { it.key in lastKeys }
                .sortedWith(compareByDescending<ItemMemory> { it.purchaseCount }.thenBy { it.key }).take(6)
            candidate += engine.rank(trip.closedAt, training, emptyList(), intervalsEnabled = true).count { it.key in actual }
            previous += lastRanked.count { it.key in actual }
            staples += engine.staples(training).take(6).count { it.key in actual }
            count++
        }
        return ReplayReport(count, ReplayScore(candidate, count * 6), ReplayScore(previous, count * 6), ReplayScore(staples, count * 6))
    }
}

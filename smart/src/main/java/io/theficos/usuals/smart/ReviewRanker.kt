package io.theficos.usuals.smart

import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ItemIdentity

data class ReviewCandidate(val key: String, val name: String, val lastBoughtAt: Long,
    val shopCount: Int, val windowSize: Int, val gtin: Gtin? = null, val recentShopScore: Int = 0)

/** A factual history review. Inferred purchase intervals remain separately gated. */
class ReviewRanker {
    fun rank(now: Long, history: HistorySnapshot, active: List<Item>): List<ReviewCandidate> {
        val trips = history.trips.filter { it.closedAt <= now }
            .sortedWith(compareByDescending<Trip> { it.closedAt }.thenBy { it.id }).take(8)
        if (trips.isEmpty()) return emptyList()
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val present = active.map { ItemIdentity.key(it, aliases) }.toSet()
        val preferences = ItemIdentity.preferences(history.preferences, aliases)
        val ids = trips.map { it.id }.toSet()
        val weights = trips.mapIndexed { index, trip -> trip.id to (trips.size - index) }.toMap()
        return history.purchases.filter { it.tripId in ids && it.boughtAt <= now }
            .groupBy { ItemIdentity.purchaseKey(it, aliases) }.mapNotNull { (key, purchases) ->
                val pref = preferences[key]
                if (key.isBlank() || key in present || pref?.muted == true || pref?.repeatEveryDays != null ||
                    pref?.snoozedUntil?.let { trips.first().closedAt <= it } == true) return@mapNotNull null
                val count = purchases.map { it.tripId }.distinct().size
                if (count < 2 && (trips.size >= 3 || purchases.none { it.tripId == trips.first().id })) return@mapNotNull null
                val latest = purchases.sortedWith(compareByDescending<Purchase> { it.boughtAt }.thenBy { it.id.value }).first()
                if (latest.boughtAt > now - RepeatPlanner.DAY_MILLIS) return@mapNotNull null
                val recentScore = purchases.map { it.tripId }.distinct().sumOf { weights.getValue(it) }
                ReviewCandidate(key, latest.name, latest.boughtAt, count, trips.size, latest.gtin, recentScore)
            }.sortedWith(compareByDescending<ReviewCandidate> { it.shopCount }.thenByDescending { it.recentShopScore }
                .thenBy { it.lastBoughtAt }.thenBy { it.key })
    }
}

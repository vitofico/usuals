package io.theficos.comanda.smart

import io.theficos.comanda.model.*
import io.theficos.comanda.parse.ItemIdentity
import kotlin.math.abs

data class IntervalEstimate(val key: String, val name: String, val days: Int, val lastBoughtAt: Long)

/** Experimental predictions. Production keeps these off until real-history replay wins. */
class IntervalModel {
    fun estimates(history: HistorySnapshot): List<IntervalEstimate> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        return history.purchases.groupBy { ItemIdentity.purchaseKey(it, aliases) }.mapNotNull { (key, purchases) ->
            val dates = purchases.groupBy { it.tripId }.values.map { group -> group.maxOf { it.boughtAt } }.sorted()
            if (dates.size < 3) return@mapNotNull null
            val gaps = dates.zipWithNext { a, b -> (b - a).toDouble() / RepeatPlanner.DAY_MILLIS }
            val median = median(gaps)
            if (median < 5 || median(gaps.map { abs(it - median) }) / median > 0.35 ||
                gaps.any { it < median / 2 || it > median * 2 }) return@mapNotNull null
            val last = purchases.sortedWith(compareByDescending<Purchase> { it.boughtAt }.thenBy { it.id.value }).first()
            IntervalEstimate(key, last.name, median.toInt().coerceAtLeast(5), dates.last())
        }.sortedBy { it.key }
    }

    fun due(now: Long, history: HistorySnapshot): List<IntervalEstimate> = estimates(history).filter {
        val elapsed = (now - it.lastBoughtAt).toDouble() / RepeatPlanner.DAY_MILLIS
        elapsed >= it.days && elapsed <= it.days * 2.5
    }.sortedWith(compareByDescending<IntervalEstimate> { (now - it.lastBoughtAt).toDouble() / it.days }
        .thenBy { it.key })

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }
}

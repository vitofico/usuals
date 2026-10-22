package io.theficos.usuals.smart

import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ItemIdentity

/**
 * A barcode this household has used for an item key. [lastBoughtAt] is the
 * latest purchase that carried it, or null when the only record is a scan the
 * person named and has not bought yet.
 */
data class RememberedBarcode(val gtin: Gtin, val lastBoughtAt: Long?)

/**
 * Which product a row without a barcode most likely is, read from the
 * household's own history.
 *
 * It is a lookup at read time, never a write. A remembered barcode stored on a
 * row would reach `Purchase.gtin` at Finish and later be indistinguishable from
 * a real scan, so rows keep `gtin = null` and the screen attaches this answer.
 * Aliases and purchases sync, so every phone sharing the list derives the same
 * answer without any protocol change.
 *
 * It answers only when history is unambiguous: milk bought as two brands stays
 * unknown rather than showing one brand's facts for the other.
 */
class BarcodeMemory(history: HistorySnapshot) {
    private val aliases = history.aliases.associate { it.fromKey to it.toKey }

    // Grouped once per snapshot, because the list asks for every row.
    private val bought: Map<String, List<Purchase>> = history.purchases.filter { it.gtin != null }
        .groupBy { ItemIdentity.purchaseKey(it, aliases) }
        .mapValues { (_, purchases) -> purchases.sortedWith(compareByDescending<Purchase> { it.boughtAt }.thenBy { it.id.value }) }

    // A named scan records `gtin:X -> key` before the product is ever bought.
    private val named: Map<String, Set<Gtin>> = aliases.keys.mapNotNull { from ->
        Gtin.parse(from.removePrefix(PREFIX)).takeIf { from.startsWith(PREFIX) }?.let { ItemIdentity.resolve(from, aliases) to it }
    }.groupBy({ it.first }, { it.second }).mapValues { (_, codes) -> codes.toSet() }

    fun remembered(item: Item): RememberedBarcode? = remembered(ItemIdentity.key(item, aliases))

    fun remembered(key: String): RememberedBarcode? {
        // A household that switched brand a while ago should see the new one,
        // so only the most recent barcoded purchases vote.
        val recent = bought[key].orEmpty().take(RECENT)
        // Barcoded purchases that disagree are an answer too: refuse rather
        // than fall back to an alias that would pick one brand arbitrarily.
        if (recent.isNotEmpty()) return recent.mapNotNull { it.gtin }.distinct().singleOrNull()
            ?.let { RememberedBarcode(it, recent.first().boughtAt) }
        return named[key]?.singleOrNull()?.let { RememberedBarcode(it, null) }
    }

    companion object {
        const val RECENT = 5
        private const val PREFIX = "gtin:"
    }
}

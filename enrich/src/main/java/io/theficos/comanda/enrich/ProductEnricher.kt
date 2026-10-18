package io.theficos.comanda.enrich

import io.theficos.comanda.model.Gtin

/**
 * On-device lookup of a validated barcode. Typed shopping names are never searched.
 */
interface ProductEnricher {
    suspend fun lookup(gtin: Gtin): QualityLookup
}

/** Local answers with explicit freshness. Reads never perform network work. */
interface QualityCache {

    /** What is known now, or null if nothing is. Must not perform a network call. */
    suspend fun get(gtin: Gtin): CachedQuality?

    /**
     * Records an outcome.
     *
     * Implementations must ignore an outcome whose [QualityLookup.cacheable] is
     * false. That check lives in the implementation rather than in every caller
     * because there will be more callers than implementations, and the failure
     * mode of forgetting it is silent and long-lived: a timeout cached as
     * "Open Food Facts has nothing" looks exactly like the truth.
     */
    suspend fun put(outcome: QualityLookup, atEpochMillis: Long)
}

/**
 * A cache entry: what was learned, when, and whether it was a miss.
 *
 * A confirmed miss is stored as [quality] = null with [confirmedMissing] = true,
 * which is a different row from "we have never asked", which is no row at all.
 * Without that distinction every cold start re-asks OFF about the same products
 * it already knows are absent, which is exactly the traffic the rate limit is
 * there to prevent.
 */
data class CachedQuality(
    val gtin: Gtin,
    val quality: ProductQuality?,
    val confirmedMissing: Boolean,
    val fetchedAtEpochMillis: Long,
) {
    /** True once the entry is old enough to be worth refreshing. Still served meanwhile. */
    fun refreshEligible(nowEpochMillis: Long, policy: FreshnessPolicy = FreshnessPolicy.DEFAULT): Boolean =
        nowEpochMillis - fetchedAtEpochMillis >= policy.refreshAfterMillis(confirmedMissing)
}

/**
 * When an entry becomes worth re-asking about.
 *
 * Two different ages, because the two kinds of entry age differently. A record
 * that exists changes slowly: a volunteer corrects a Nutri-Score now and then.
 * A confirmed miss is the one most likely to become wrong, because the ordinary
 * life of a missing OFF product is that somebody eventually adds it. So misses
 * are re-checked sooner than hits, which is the opposite of what a single TTL
 * would do.
 */
data class FreshnessPolicy(
    val hitRefreshAfterMillis: Long = THIRTY_DAYS,
    val missRefreshAfterMillis: Long = SEVEN_DAYS,
) {
    fun refreshAfterMillis(confirmedMissing: Boolean): Long =
        if (confirmedMissing) missRefreshAfterMillis else hitRefreshAfterMillis

    companion object {
        const val SEVEN_DAYS: Long = 7L * 24 * 60 * 60 * 1000
        const val THIRTY_DAYS: Long = 30L * 24 * 60 * 60 * 1000
        val DEFAULT = FreshnessPolicy()
    }
}

package io.theficos.usuals.enrich

import io.theficos.usuals.model.Gtin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [QualityCache] that lives for as long as the process does.
 *
 * It is what the JVM tests run against, and it is a serviceable first
 * implementation on device too: a household scans a few hundred distinct
 * barcodes, not a few hundred thousand. `:data:local` swaps in a Room-backed
 * one against this same interface once the rows have to survive a restart.
 *
 * The rule this class exists to hold is the one [QualityCache.put] states: an
 * outcome that is not [QualityLookup.cacheable] is dropped on the floor. A
 * timeout is a fact about the network, not about the product, and writing it
 * down as an answer turns a thirty second outage into a week of "Open Food
 * Facts has nothing about this", which at the point where it is read looks
 * exactly like the truth. Callers are meant to hand every outcome over without
 * thinking about it; this is where the ones that are not answers stop.
 *
 * The clock is injected rather than read off the wall, as everywhere in this
 * module, so that "now" has one definition per process and a test can move it.
 * The two argument [put] the interface declares stays alongside the one
 * argument convenience because a caller that has just made a request knows
 * something the cache cannot: when the request went out. Stamping the entry
 * with that instant rather than with the moment a slow response finally landed
 * keeps a record from looking fresher than the data inside it.
 */
class InMemoryQualityCache(private val clock: () -> Long) : QualityCache {

    /**
     * Keyed by [Gtin], whose equality is by canonical value, so a UPC-A and the
     * EAN-13 it widens into land on one row instead of two.
     */
    private val entries = HashMap<Gtin, CachedQuality>()

    private val mutex = Mutex()

    override suspend fun get(gtin: Gtin): CachedQuality? = mutex.withLock { entries[gtin] }

    override suspend fun put(outcome: QualityLookup, atEpochMillis: Long) {
        // The gate is [QualityLookup.cacheable] and not a check on the type,
        // so that the rule lives in one place and reads the same here as it
        // does in the interface that promises it.
        if (!outcome.cacheable) return

        val entry = when (outcome) {
            is QualityLookup.Found -> CachedQuality(
                gtin = outcome.gtin,
                quality = outcome.quality,
                confirmedMissing = false,
                fetchedAtEpochMillis = atEpochMillis,
            )

            // A confirmed absence is a row with no quality in it. "Never asked"
            // is no row at all, and keeping the two apart is what stops every
            // cold start from re-asking about the products OFF has already said
            // it does not have.
            is QualityLookup.Missing -> CachedQuality(
                gtin = outcome.gtin,
                quality = null,
                confirmedMissing = true,
                fetchedAtEpochMillis = atEpochMillis,
            )

            // Unreachable for as long as [QualityLookup.cacheable] means what
            // it says. Spelled out rather than folded into an `else` so that a
            // fifth outcome type breaks this file at compile time instead of
            // quietly slipping past the guard above and being written down.
            is QualityLookup.Skipped, is QualityLookup.Failed -> return
        }

        mutex.withLock { entries[outcome.gtin] = entry }
    }

    /** Records [outcome] as learned now. Same rules, including the one about failures. */
    suspend fun put(outcome: QualityLookup) = put(outcome, clock())
}

/** Cached scan naming. Old records refresh on demand; offline scans may use a known name. */
class CachingEnricher(
    private val delegate: ProductEnricher,
    private val cache: QualityCache,
    private val clock: () -> Long,
    private val policy: FreshnessPolicy = FreshnessPolicy.DEFAULT,
) : ProductEnricher {
    override suspend fun lookup(gtin: Gtin): QualityLookup {
        val entry = cache.get(gtin)
        val known = entry?.asAnswer()
        val requestedAt = clock()
        if (known != null && entry?.refreshEligible(requestedAt, policy) == false) return known
        val outcome = delegate.lookup(gtin)
        cache.put(outcome, requestedAt)
        return if (outcome.cacheable) outcome else known ?: outcome
    }

    private fun CachedQuality.asAnswer(): QualityLookup? = when {
        confirmedMissing -> QualityLookup.Missing(gtin)
        quality != null -> QualityLookup.Found(gtin, quality)
        else -> null
    }
}

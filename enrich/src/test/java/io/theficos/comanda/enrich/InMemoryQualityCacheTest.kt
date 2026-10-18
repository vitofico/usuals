package io.theficos.comanda.enrich

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.model.Gtin
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Guards the failure that would never show up in a crash report: a cache that
 * writes down an outage as an answer.
 *
 * A timeout cached as "Open Food Facts has nothing about this" is silent, it is
 * indistinguishable from the truth at every point where it is read, and it
 * lasts until the freshness policy lets go of it a week later. Nothing here
 * sleeps or reaches the network, and the clock is a field, so ages measured in
 * weeks are asserted exactly and the suite still runs in milliseconds.
 */
class InMemoryQualityCacheTest {

    private var now: Long = 0L

    private val clock: () -> Long = { now }

    private val leche = gtin("8400000001236")
    private val pan = gtin("8400000004565")
    private val huevos = gtin("8400000007894")

    private val stated = ProductQuality(nutriScore = NutriScore.B, novaGroup = NovaGroup.PROCESSED)
    private val corrected = ProductQuality(nutriScore = NutriScore.C, ecoScore = EcoScore.D)

    // region what the cache refuses to remember

    @Test
    fun `an outage is never remembered as an absence`() = runTest {
        val cache = InMemoryQualityCache(clock)

        cache.put(QualityLookup.Failed(leche, FailureReason.TIMEOUT), now)
        cache.put(QualityLookup.Failed(pan, FailureReason.RATE_LIMITED), now)
        cache.put(QualityLookup.Skipped(huevos, SkipReason.RESTRICTED_CIRCULATION), now)

        assertThat(cache.get(leche)).isNull()
        assertThat(cache.get(pan)).isNull()
        assertThat(cache.get(huevos)).isNull()
    }

    @Test
    fun `a failure never overwrites what is already known`() = runTest {
        val cache = InMemoryQualityCache(clock)
        cache.put(QualityLookup.Found(leche, stated), now)

        now += FreshnessPolicy.THIRTY_DAYS
        cache.put(QualityLookup.Failed(leche, FailureReason.SERVER_ERROR), now)

        val entry = cache.get(leche)!!
        assertThat(entry.quality).isEqualTo(stated)
        assertThat(entry.fetchedAtEpochMillis).isEqualTo(0L)
    }

    // endregion

    // region the three states of knowing

    @Test
    fun `a record, a confirmed absence and a barcode never asked about stay distinct`() = runTest {
        val cache = InMemoryQualityCache(clock)

        cache.put(QualityLookup.Found(leche, stated), now)
        cache.put(QualityLookup.Missing(pan), now)

        val found = cache.get(leche)!!
        assertThat(found.quality).isEqualTo(stated)
        assertThat(found.confirmedMissing).isFalse()

        val missing = cache.get(pan)!!
        assertThat(missing.quality).isNull()
        assertThat(missing.confirmedMissing).isTrue()

        assertThat(cache.get(huevos)).isNull()
    }

    @Test
    fun `a record that says nothing is not the same as no record`() = runTest {
        val cache = InMemoryQualityCache(clock)

        cache.put(QualityLookup.Found(leche, ProductQuality.EMPTY), now)
        cache.put(QualityLookup.Missing(pan), now)

        val blank = cache.get(leche)!!
        assertThat(blank.quality).isEqualTo(ProductQuality.EMPTY)
        assertThat(blank.confirmedMissing).isFalse()
        assertThat(cache.get(pan)!!.confirmedMissing).isTrue()
    }

    @Test
    fun `an entry is stamped with the injected clock, never with the wall clock`() = runTest {
        now = 1_700_000_000_000
        val cache = InMemoryQualityCache(clock)

        cache.put(QualityLookup.Found(leche, stated))

        assertThat(cache.get(leche)!!.fetchedAtEpochMillis).isEqualTo(1_700_000_000_000)
    }

    // endregion

    // region the decision path

    @Test
    fun `a cached answer costs no request, however many times it is asked for`() = runTest {
        val delegate = CountingEnricher { QualityLookup.Found(it, stated) }
        val enricher = CachingEnricher(delegate, InMemoryQualityCache(clock), clock)

        assertThat(enricher.lookup(leche)).isEqualTo(QualityLookup.Found(leche, stated))
        repeat(3) { enricher.lookup(leche) }

        assertThat(delegate.calls).isEqualTo(1)
    }

    @Test
    fun `an old entry refreshes during an asynchronous naming lookup`() = runTest {
        val delegate = CountingEnricher { QualityLookup.Found(it, stated) }
        val cache = InMemoryQualityCache(clock)
        val enricher = CachingEnricher(delegate, cache, clock)
        enricher.lookup(leche)

        // Getting on for five years, which is well past anything the policy
        // calls fresh.
        now += 60L * FreshnessPolicy.THIRTY_DAYS
        val served = enricher.lookup(leche)

        assertThat(cache.get(leche)!!.refreshEligible(now)).isFalse()
        assertThat(served).isEqualTo(QualityLookup.Found(leche, stated))
        assertThat(delegate.calls).isEqualTo(2)
    }

    @Test
    fun `a confirmed absence is replayed as a miss, not re-asked`() = runTest {
        val delegate = CountingEnricher { QualityLookup.Missing(it) }
        val enricher = CachingEnricher(delegate, InMemoryQualityCache(clock), clock)

        assertThat(enricher.lookup(pan)).isEqualTo(QualityLookup.Missing(pan))
        assertThat(enricher.lookup(pan)).isEqualTo(QualityLookup.Missing(pan))

        assertThat(delegate.calls).isEqualTo(1)
    }

    @Test
    fun `a failed lookup leaves nothing behind, so the next one tries again`() = runTest {
        var answer: (Gtin) -> QualityLookup = { QualityLookup.Failed(it, FailureReason.TIMEOUT) }
        val delegate = CountingEnricher { answer(it) }
        val cache = InMemoryQualityCache(clock)
        val enricher = CachingEnricher(delegate, cache, clock)

        val failed = enricher.lookup(leche)
        assertThat(failed).isEqualTo(QualityLookup.Failed(leche, FailureReason.TIMEOUT))
        assertThat(cache.get(leche)).isNull()

        answer = { QualityLookup.Found(it, stated) }
        assertThat(enricher.lookup(leche)).isEqualTo(QualityLookup.Found(leche, stated))
        assertThat(delegate.calls).isEqualTo(2)
    }

    // endregion

    // region the background top-up

    @Test
    fun `lookup spends nothing on an entry that is still fresh`() = runTest {
        val delegate = CountingEnricher { QualityLookup.Found(it, stated) }
        val enricher = CachingEnricher(delegate, InMemoryQualityCache(clock), clock)
        enricher.lookup(leche)

        now += FreshnessPolicy.THIRTY_DAYS - 1

        assertThat(enricher.lookup(leche)).isEqualTo(QualityLookup.Found(leche, stated))
        assertThat(delegate.calls).isEqualTo(1)
    }

    @Test
    fun `lookup re-asks once an entry is old enough and writes the new answer`() = runTest {
        var answer: (Gtin) -> QualityLookup = { QualityLookup.Found(it, stated) }
        val delegate = CountingEnricher { answer(it) }
        val cache = InMemoryQualityCache(clock)
        val enricher = CachingEnricher(delegate, cache, clock)
        enricher.lookup(leche)

        now += FreshnessPolicy.THIRTY_DAYS
        answer = { QualityLookup.Found(it, corrected) }
        val refreshed = enricher.lookup(leche)

        assertThat(refreshed).isEqualTo(QualityLookup.Found(leche, corrected))
        assertThat(delegate.calls).isEqualTo(2)
        assertThat(cache.get(leche)!!.quality).isEqualTo(corrected)
        assertThat(cache.get(leche)!!.fetchedAtEpochMillis).isEqualTo(now)
    }

    @Test
    fun `lookup fetches a barcode nothing is known about`() = runTest {
        val delegate = CountingEnricher { QualityLookup.Found(it, stated) }
        val enricher = CachingEnricher(delegate, InMemoryQualityCache(clock), clock)

        assertThat(enricher.lookup(leche)).isEqualTo(QualityLookup.Found(leche, stated))
        assertThat(delegate.calls).isEqualTo(1)
    }

    @Test
    fun `a refresh that fails leaves the previous answer standing`() = runTest {
        var answer: (Gtin) -> QualityLookup = { QualityLookup.Found(it, stated) }
        val delegate = CountingEnricher { answer(it) }
        val cache = InMemoryQualityCache(clock)
        val enricher = CachingEnricher(delegate, cache, clock)
        enricher.lookup(leche)

        now += FreshnessPolicy.THIRTY_DAYS
        answer = { QualityLookup.Failed(it, FailureReason.UNREACHABLE) }
        val outcome = enricher.lookup(leche)

        assertThat(outcome).isEqualTo(QualityLookup.Found(leche, stated))
        assertThat(cache.get(leche)!!.quality).isEqualTo(stated)
        assertThat(enricher.lookup(leche)).isEqualTo(QualityLookup.Found(leche, stated))
    }

    /**
     * The asymmetry is the point: the ordinary life of a product OFF does not
     * have is that somebody eventually adds it, so a miss goes stale sooner
     * than a record does. A single TTL would get this exactly backwards.
     */
    @Test
    fun `a confirmed absence is worth re-asking about sooner than a record`() = runTest {
        val delegate = CountingEnricher {
            if (it == leche) QualityLookup.Found(it, stated) else QualityLookup.Missing(it)
        }
        val cache = InMemoryQualityCache(clock)
        val enricher = CachingEnricher(delegate, cache, clock)
        enricher.lookup(leche)
        enricher.lookup(pan)

        now += FreshnessPolicy.SEVEN_DAYS

        assertThat(cache.get(pan)!!.refreshEligible(now)).isTrue()
        assertThat(cache.get(leche)!!.refreshEligible(now)).isFalse()
        assertThat(enricher.lookup(pan)).isNotNull()
        assertThat(enricher.lookup(leche)).isEqualTo(QualityLookup.Found(leche, stated))
        assertThat(delegate.calls).isEqualTo(3)

        now += FreshnessPolicy.THIRTY_DAYS - FreshnessPolicy.SEVEN_DAYS

        assertThat(enricher.lookup(leche)).isNotNull()
        assertThat(delegate.calls).isEqualTo(4)
    }

    // endregion

    private fun gtin(raw: String): Gtin =
        requireNotNull(Gtin.parse(raw)) { "fixture is not a valid GTIN: $raw" }

    /**
     * An enricher that answers from a lambda and counts how often it was asked.
     *
     * The count is the assertion in most of these tests. "Served from cache" is
     * not observable in the return value, by design, since a hit comes back as
     * the same [QualityLookup.Found] a live lookup produces, so the only
     * evidence that the network was spared is that nobody called this.
     */
    private class CountingEnricher(
        private val answer: (Gtin) -> QualityLookup,
    ) : ProductEnricher {

        var calls = 0
            private set

        override suspend fun lookup(gtin: Gtin): QualityLookup {
            calls++
            return answer(gtin)
        }
    }
}

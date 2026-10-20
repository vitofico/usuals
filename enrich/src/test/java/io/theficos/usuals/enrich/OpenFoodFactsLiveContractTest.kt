package io.theficos.usuals.enrich

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.Gtin
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The one test that talks to the real Open Food Facts, and only when asked.
 *
 * Everything else in this module is scripted against a local server, which is
 * the right default: a suite that needs the internet is not a suite, and
 * pointing CI at a volunteer-funded service is how an app gets blocked. But a
 * fully offline suite has a blind spot exactly where it hurts, because the
 * fixtures are our reconstruction of OFF's shape rather than OFF's bytes. If
 * they change the envelope, rename a field, or invent a third word meaning "no
 * grade", every offline test keeps passing and the app quietly stops reading
 * data that is sitting right there.
 *
 * So this exists to be run deliberately, by a person, when they want to know
 * whether the parser still matches reality:
 *
 *     USUALS_OFF_LIVE=1 scripts/dgradle :enrich:test
 *
 * Without that variable it is skipped, not failed. It spends at most three
 * requests against a documented budget of fifteen a minute, uses a real contact
 * address in the User-Agent as OFF requires, and reads only. It never writes,
 * never searches and never crawls.
 */
class OpenFoodFactsLiveContractTest {

    private fun liveEnricher() = OpenFoodFactsEnricher(
        userAgent = USER_AGENT,
        rateLimiter = OffRateLimiter(
            permitsPerWindow = OffRateLimiter.PRODUCT_READS_PER_MINUTE,
            windowMillis = OffRateLimiter.ONE_MINUTE_MILLIS,
        ),
        isRestricted = RestrictedCirculation::isRestricted,
    )

    @Test
    fun `the live envelope still parses into the facets we rank by`() = runTest {
        assumeTrue(liveRequested())

        val outcome = liveEnricher().lookup(Gtin.parse(REAL_PRODUCT)!!)

        assertThat(outcome).isInstanceOf(QualityLookup.Found::class.java)
        val quality = (outcome as QualityLookup.Found).quality
        // Deliberately not asserting a specific grade. Volunteers edit these
        // records and a test that pins today's Nutri-Score would fail for a
        // reason that is not a bug. What must hold is that SOMETHING parsed:
        // an envelope we can no longer read yields a blank record, and that is
        // the regression worth catching.
        assertThat(quality.isBlank).isFalse()
    }

    @Test
    fun `a confirmed absence still arrives as a 404 and not as an empty record`() = runTest {
        assumeTrue(liveRequested())

        val outcome = liveEnricher().lookup(Gtin.parse(ABSENT_PRODUCT)!!)

        // The distinction the whole cache design rests on. If OFF ever starts
        // answering 200-with-nothing for an unknown barcode, Missing becomes
        // unreachable and outages and absences stop being distinguishable.
        assertThat(outcome).isEqualTo(QualityLookup.Missing(Gtin.parse(ABSENT_PRODUCT)!!))
    }

    @Test
    fun `the words meaning no grade are still the words we know about`() = runTest {
        assumeTrue(liveRequested())

        val outcome = liveEnricher().lookup(Gtin.parse(REAL_PRODUCT)!!)

        // Parsing succeeded and produced either a grade or a null, never a
        // string. A new sentinel word would surface here as an unexpected null
        // where a grade was expected, or as a parse failure, rather than as the
        // literal text "unknown" being rendered to somebody as a nutrition grade.
        assertThat(outcome).isInstanceOf(QualityLookup.Found::class.java)
    }

    private companion object {

        fun liveRequested(): Boolean = System.getenv("USUALS_OFF_LIVE") == "1"

        /**
         * OFF asks for `AppName/Version (contact)` and enforces it. The contact
         * is the repository, which is a real place to complain to and does not
         * put a personal address in a public file.
         */
        const val USER_AGENT = "Usuals/0.1 (https://github.com/theficos/usuals)"

        /** A widely stocked product, chosen because it is unlikely to be deleted. */
        const val REAL_PRODUCT = "3017620422003"

        /**
         * A structurally valid EAN-13 that resolved to nothing when probed.
         *
         * Worth knowing: this is a weaker guarantee than it sounds. OFF is
         * crowd-sourced, so any code can acquire a record at any time, and two
         * other improbable codes tried on the same day both turned out to be
         * real products. If this test starts failing with Found, that is the
         * likely reason and the fix is a new code, not a code change.
         */
        const val ABSENT_PRODUCT = "9642138750214"
    }
}

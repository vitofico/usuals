package io.theficos.usuals.enrich

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.usuals.model.Gtin
import org.junit.Test

/**
 * Guards the boundaries of the in-store skip, in both directions.
 *
 * Two failures are possible and both are expensive. Skipping too little spends
 * the request budget on codes that no global database can resolve, which is the
 * whole reason this check exists. Skipping too much is worse and quieter: an
 * ordinary product would be dropped from enrichment and look identical to one
 * Open Food Facts has never heard of, with nothing anywhere saying it was never
 * asked about. So every prefix on each side of a boundary is asserted, not just
 * the ones inside the reserved ranges.
 *
 * The tests about padding are here for a different reason. [Gtin] widens shorter
 * encodings by adding zeros on the left, which makes a UPC-A in-store code
 * detectable by accident and a restricted EAN-8 undetectable by construction.
 * Both facts are pinned so that the accident is not lost in a refactor and the
 * limitation is not "fixed" by someone reading the wrong digits.
 */
class RestrictedCirculationTest {

    @Test
    fun `restricted circulation covers the whole reserved prefix range`() {
        val cases = mapOf(
            "0212345678909" to true,
            "0412345678903" to true,
            "0112345678902" to false,
            "0312345678906" to false,
            "0512345678900" to false,
            "1912345678907" to false,
            "2012345678903" to true,
            "2112345678900" to true,
            "2212345678907" to true,
            "2312345678904" to true,
            "2412345678901" to true,
            "2512345678908" to true,
            "2612345678905" to true,
            "2712345678902" to true,
            "2812345678909" to true,
            "2912345678906" to true,
            "3012345678902" to false,
            "3017620422003" to false,
            "212345002506" to true,
        )
        for ((code, restricted) in cases) {
            val barcode = gtin(code)
            assertWithMessage("restricted %s", code).that(RestrictedCirculation.isRestricted(barcode)).isEqualTo(restricted)
        }
        // Widening UPC-A moves its leading 2 into the reserved EAN-13 prefix.
        assertThat(gtin("212345002506").value).isEqualTo("0212345002506")
    }

    @Test
    fun `a restricted EAN-8 is not detected, and that limitation is pinned deliberately`() {
        // Eight digits gain five zeros, so the code's own leading "20" moves to
        // positions 5 and 6 and the prefix reads "00" like every other short
        // code. This assertion says false on purpose. If it ever fails, the fix
        // is not to start reading positions 5 and 6, which would skip real
        // EAN-13 products whose middle digits happen to land in the band. It is
        // to carry the original encoding alongside the canonical form, which is
        // a change to Gtin and a decision worth making explicitly.
        val widened = gtin("20123451")

        assertThat(widened.value).isEqualTo("0000020123451")
        assertThat(RestrictedCirculation.isRestricted(widened)).isFalse()
    }

    /**
     * Parses [raw] and fails the test if it is not a barcode at all.
     *
     * Every fixture in this file is hand-built with a computed check digit, and a
     * typo in one would make [Gtin.parse] return null. Without this assertion the
     * test using it would then be asking about nothing and quietly passing.
     */
    private fun gtin(raw: String): Gtin {
        val parsed = Gtin.parse(raw)
        assertThat(parsed).isNotNull()
        return parsed!!
    }
}

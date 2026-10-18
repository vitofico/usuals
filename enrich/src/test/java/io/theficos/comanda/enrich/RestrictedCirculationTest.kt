package io.theficos.comanda.enrich

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.model.Gtin
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
    fun `the GS1 in-store prefixes 02 and 04 are skipped`() {
        assertThat(RestrictedCirculation.isRestricted(gtin("0212345678909"))).isTrue()
        assertThat(RestrictedCirculation.isRestricted(gtin("0412345678903"))).isTrue()
    }

    @Test
    fun `the prefixes on either side of 02 and 04 stay eligible for lookup`() {
        assertThat(RestrictedCirculation.isRestricted(gtin("0112345678902"))).isFalse()
        assertThat(RestrictedCirculation.isRestricted(gtin("0312345678906"))).isFalse()
        assertThat(RestrictedCirculation.isRestricted(gtin("0512345678900"))).isFalse()
    }

    @Test
    fun `the whole 20 to 29 band is skipped, not only its endpoints`() {
        val band = listOf(
            "2012345678903",
            "2112345678900",
            "2212345678907",
            "2312345678904",
            "2412345678901",
            "2512345678908",
            "2612345678905",
            "2712345678902",
            "2812345678909",
            "2912345678906",
        )

        for (code in band) {
            assertThat(RestrictedCirculation.isRestricted(gtin(code))).isTrue()
        }
    }

    @Test
    fun `the prefixes just outside the 20 to 29 band stay eligible for lookup`() {
        assertThat(RestrictedCirculation.isRestricted(gtin("1912345678907"))).isFalse()
        assertThat(RestrictedCirculation.isRestricted(gtin("3012345678902"))).isFalse()
    }

    @Test
    fun `an ordinary manufacturer barcode is never skipped`() {
        // Nutella, 400g, a real code and about as mainstream as a jar gets.
        assertThat(RestrictedCirculation.isRestricted(gtin("3017620422003"))).isFalse()
    }

    @Test
    fun `a UPC-A in-store code is still caught after being widened to thirteen digits`() {
        // A North American scale-printed code: leading 2, then the shop's own
        // item number and an embedded price. Twelve digits gain one zero on the
        // left, so the UPC in-store range lands on the EAN-13 prefix 02 and this
        // check sees it. That alignment is luck, not design, which is exactly why
        // it is asserted here rather than left implied.
        val widened = gtin("212345002506")

        assertThat(widened.value).isEqualTo("0212345002506")
        assertThat(RestrictedCirculation.isRestricted(widened)).isTrue()
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

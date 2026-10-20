package io.theficos.usuals.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GtinTest {

    // 8480000123459 carries the Spanish 84 prefix, 4006381333931 is the
    // canonical GS1 example, and 9780261103573 is a book: an ISBN-13 is an
    // EAN-13, and the same check digit routine covers both.
    private val spanishProduct = "8480000123459"
    private val gs1Example = "4006381333931"
    private val isbnAsEan = "9780261103573"

    @Test
    fun `a valid EAN-13 is kept as it is`() {
        assertThat(Gtin.parse(spanishProduct)?.value).isEqualTo(spanishProduct)
        assertThat(Gtin.parse(gs1Example)?.value).isEqualTo(gs1Example)
        assertThat(Gtin.parse(isbnAsEan)?.value).isEqualTo(isbnAsEan)
    }

    @Test
    fun `hyphens and whitespace are stripped before parsing`() {
        assertThat(Gtin.parse("84-8000-0123-459")?.value).isEqualTo(spanishProduct)
        assertThat(Gtin.parse("  4006 3813 3393 1 ")?.value).isEqualTo(gs1Example)
        assertThat(Gtin.parse("978-0-261-10357-3")?.value).isEqualTo(isbnAsEan)
    }

    @Test
    fun `a UPC-A widens to EAN-13 with a leading zero`() {
        val upcA = "036000291452"
        assertThat(Gtin.parse(upcA)?.value).isEqualTo("0036000291452")
    }

    @Test
    fun `an EAN-8 widens to EAN-13 with leading zeros`() {
        assertThat(Gtin.parse("96385074")?.value).isEqualTo("0000096385074")
    }

    @Test
    fun `a widened code parses to the same value as its already-widened form`() {
        assertThat(Gtin.parse("036000291452")).isEqualTo(Gtin.parse("0036000291452"))
        assertThat(Gtin.parse("96385074")).isEqualTo(Gtin.parse("0000096385074"))
    }

    @Test
    fun `a wrong check digit is rejected`() {
        assertThat(Gtin.parse("8480000123450")).isNull()
        assertThat(Gtin.parse("4006381333930")).isNull()
        assertThat(Gtin.parse("9780261103574")).isNull()
        // A 12-digit code is read as a UPC-A, and this one fails that check too.
        assertThat(Gtin.parse("123456789010")).isNull()
    }

    @Test
    fun `a wrong length is rejected`() {
        assertThat(Gtin.parse("1234567")).isNull()
        assertThat(Gtin.parse("123456789")).isNull()
        assertThat(Gtin.parse("1234567890")).isNull()
        // GTIN-14 identifies a case, not the thing a person picks up.
        assertThat(Gtin.parse("14006381333938")).isNull()
    }

    @Test
    fun `non-digits are rejected`() {
        assertThat(Gtin.parse("")).isNull()
        assertThat(Gtin.parse("   ")).isNull()
        assertThat(Gtin.parse("leche entera")).isNull()
        assertThat(Gtin.parse("400638133393X")).isNull()
        assertThat(Gtin.parse("848000012345+")).isNull()
    }

    @Test
    fun `an all-zero code is rejected even though its check digit passes`() {
        assertThat(Gtin.parse("0000000000000")).isNull()
        assertThat(Gtin.parse("00000000")).isNull()
    }

    @Test
    fun `isValid agrees with parse`() {
        val inputs = listOf(
            spanishProduct, gs1Example, isbnAsEan, "036000291452", "96385074",
            "84-8000-0123-459", "8480000123450", "", "leche", "0000000000000",
        )
        for (input in inputs) {
            assertThat(Gtin.isValid(input)).isEqualTo(Gtin.parse(input) != null)
        }
    }

    @Test
    fun `equality is by canonical value, so a scan and a cached string are one key`() {
        val scanned = Gtin.parse(spanishProduct)!!
        val typed = Gtin.parse("84 8000 0123 459")!!

        assertThat(scanned).isEqualTo(typed)
        assertThat(scanned.hashCode()).isEqualTo(typed.hashCode())
        assertThat(setOf(scanned, typed)).hasSize(1)
        assertThat(mapOf(scanned to "leche")[typed]).isEqualTo("leche")
    }

    @Test
    fun `a Gtin is not equal to its own string`() {
        val gtin = Gtin.parse(spanishProduct)!!
        assertThat(gtin).isNotEqualTo(spanishProduct)
        assertThat(gtin.toString()).isEqualTo(spanishProduct)
    }
}

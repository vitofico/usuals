package io.theficos.usuals.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class GtinTest {

    // 8480000123459 carries the Spanish 84 prefix, 4006381333931 is the
    // canonical GS1 example, and 9780261103573 is a book: an ISBN-13 is an
    // EAN-13, and the same check digit routine covers both.
    private val spanishProduct = "8480000123459"
    private val gs1Example = "4006381333931"
    private val isbnAsEan = "9780261103573"

    @Test
    fun `barcodes parse to their canonical form`() {
        val cases = listOf(
            spanishProduct to spanishProduct,
            gs1Example to gs1Example,
            isbnAsEan to isbnAsEan,
            "84-8000-0123-459" to spanishProduct,
            "  4006 3813 3393 1 " to gs1Example,
            "978-0-261-10357-3" to isbnAsEan,
            "036000291452" to "0036000291452",
            "96385074" to "0000096385074",
        )
        for ((input, canonical) in cases) {
            assertWithMessage("GTIN %s", input).that(Gtin.parse(input)?.value).isEqualTo(canonical)
            assertThat(Gtin.parse(input)).isEqualTo(Gtin.parse(canonical))
        }
    }

    @Test
    fun `invalid barcodes are rejected`() {
        val cases = listOf(
            "8480000123450", "4006381333930", "9780261103574", "123456789010",
            "1234567", "123456789", "1234567890", "14006381333938",
            "", "   ", "leche entera", "400638133393X", "848000012345+",
            "0000000000000", "00000000",
        )
        for (input in cases) {
            assertWithMessage("invalid GTIN %s", input).that(Gtin.parse(input)).isNull()
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
        assertThat(scanned).isNotEqualTo(spanishProduct)
        assertThat(scanned.toString()).isEqualTo(spanishProduct)
    }
}

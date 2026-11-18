package io.theficos.usuals.parse

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.MemberId
import io.theficos.usuals.model.Quantity
import org.junit.Test

class ItemDraftTest {

    private val parser = LocalUtteranceParser()

    /** A clock that does not move, so a test can assert on timestamps at all. */
    private fun fixedClock(millis: Long): () -> Long = { millis }

    @Test
    fun `parsed input becomes a row with the original text and a stable key`() {
        data class Draft(
            val raw: String,
            val name: String,
            val key: String,
            val quantity: Quantity? = null,
            val note: String? = null,
            val gtin: Gtin? = null,
        )
        val gtin = requireNotNull(Gtin.parse("8480000123459"))
        val cases = listOf(
            Draft("añade dos litros de leche entera", "leche entera", "leche entera", Quantity(2.0, MeasureUnit.LITRE)),
            Draft("  necesitamos PAN de payés!!  ", "PAN de payés", "pan payes"),
            Draft("leche para el café", "leche", "leche", note = "para el café"),
            Draft("8480000123459", "leche entera", "leche entera", gtin = gtin),
            Draft("Tomates", "Tomates", "tomate"),
            Draft("tres tomate", "tomate", "tomate", Quantity.pieces(3)),
        )
        for (row in cases) {
            val parsed = if (row.gtin == null) parser.parse(row.raw).single() else ParsedItem(row.name)
            val item = parsed.toItem(
                id = ItemId("i-1"),
                rawText = row.raw,
                addedBy = MemberId("marta"),
                position = 4.5,
                gtin = row.gtin,
                clock = fixedClock(1_700_000_000_000),
            )
            assertWithMessage("input %s", row.raw).that(item.name).isEqualTo(row.name)
            assertThat(item.id).isEqualTo(ItemId("i-1"))
            assertThat(item.rawText).isEqualTo(row.raw)
            assertThat(item.normalizedName).isEqualTo(row.key)
            assertThat(item.quantity).isEqualTo(row.quantity)
            assertThat(item.note).isEqualTo(row.note)
            assertThat(item.gtin).isEqualTo(row.gtin)
            assertThat(item.checked).isFalse()
            assertThat(item.position).isEqualTo(4.5)
            assertThat(item.addedBy).isEqualTo(MemberId("marta"))
        }
    }

    @Test
    fun `both timestamps come from one reading of the clock`() {
        // A row created and updated in the same instant has to compare equal,
        // not differ by whatever elapsed between two calls.
        var reads = 0
        val clock = {
            reads++
            1_700_000_000_000 + reads
        }

        val item = ParsedItem("pan").toItem(ItemId("i-2"), rawText = "pan", clock = clock)

        assertThat(item.createdAt).isEqualTo(item.updatedAt)
        assertThat(reads).isEqualTo(1)
    }
}

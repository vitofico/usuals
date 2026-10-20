package io.theficos.usuals.parse

import com.google.common.truth.Truth.assertThat
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
    fun `an utterance becomes a list row with its cache key already stamped`() {
        val parsed = parser.parse("añade dos litros de leche entera").single()

        val item = parsed.toItem(
            id = ItemId("i-1"),
            rawText = "añade dos litros de leche entera",
            addedBy = MemberId("marta"),
            position = 4.5,
            clock = fixedClock(1_700_000_000_000),
        )

        assertThat(item.id).isEqualTo(ItemId("i-1"))
        assertThat(item.rawText).isEqualTo("añade dos litros de leche entera")
        assertThat(item.name).isEqualTo("leche entera")
        assertThat(item.normalizedName).isEqualTo("leche entera")
        assertThat(item.quantity).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))
        assertThat(item.note).isNull()
        assertThat(item.gtin).isNull()
        assertThat(item.checked).isFalse()
        assertThat(item.position).isEqualTo(4.5)
        assertThat(item.addedBy).isEqualTo(MemberId("marta"))
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

    @Test
    fun `the raw text is kept exactly, and the name is not`() {
        val raw = "  necesitamos PAN de payés!!  "
        val parsed = parser.parse(raw).single()
        val item = parsed.toItem(ItemId("i-3"), rawText = raw, clock = fixedClock(0))

        assertThat(item.rawText).isEqualTo(raw)
        assertThat(item.name).isEqualTo("PAN de payés")
        assertThat(item.normalizedName).isEqualTo("pan payes")
    }

    @Test
    fun `a note survives onto the row`() {
        val parsed = parser.parse("leche para el café").single()
        val item = parsed.toItem(ItemId("i-4"), rawText = "leche para el café", clock = fixedClock(0))

        assertThat(item.name).isEqualTo("leche")
        assertThat(item.note).isEqualTo("para el café")
    }

    @Test
    fun `a scanned barcode rides along and is the rung-1 identity`() {
        val gtin = Gtin.parse("8480000123459")
        val item = ParsedItem("leche entera").toItem(
            id = ItemId("i-5"),
            rawText = "8480000123459",
            gtin = gtin,
            clock = fixedClock(1_700_000_000_000),
        )

        assertThat(item.gtin).isEqualTo(gtin)
        assertThat(item.normalizedName).isEqualTo("leche entera")
    }

    @Test
    fun `two spellings of the same item share a cache key`() {
        val first = parser.parse("Tomates").single().toItem(ItemId("a"), "Tomates", clock = fixedClock(0))
        val second = parser.parse("tres tomate").single().toItem(ItemId("b"), "tres tomate", clock = fixedClock(0))

        assertThat(first.normalizedName).isEqualTo(second.normalizedName)
        assertThat(first.quantity).isNull()
        assertThat(second.quantity).isEqualTo(Quantity.pieces(3))
    }
}

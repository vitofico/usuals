package io.theficos.usuals.smart

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ItemIdentity
import io.theficos.usuals.parse.Normalizer
import org.junit.Test

class BarcodeMemoryTest {
    private val day = RepeatPlanner.DAY_MILLIS
    private val brandA = Gtin.parse("4000000000013")!!
    private val brandB = Gtin.parse("4000000000020")!!
    private var ids = 0

    private fun bought(name: String, gtin: Gtin?, at: Long) =
        Purchase(ItemId("p${++ids}"), "t$at", Normalizer.normalize(name), name, gtin = gtin, boughtAt = at)

    private fun typed(name: String, gtin: Gtin? = null) =
        Item(ItemId(name), name, name, Normalizer.normalize(name), gtin = gtin, createdAt = 0, updatedAt = 0)

    @Test fun `one barcode across recent purchases is remembered with its latest date`() {
        val memory = BarcodeMemory(HistorySnapshot(purchases = listOf(
            bought("milk", brandA, 1 * day), bought("milk", null, 2 * day), bought("milk", brandA, 3 * day))))
        assertThat(memory.remembered(typed("Milk"))).isEqualTo(RememberedBarcode(brandA, 3 * day))
    }

    @Test fun `two brands among recent purchases are ambiguous, even when an alias names one`() {
        val history = HistorySnapshot(
            purchases = listOf(bought("milk", brandA, 1 * day), bought("milk", brandB, 2 * day)),
            aliases = listOf(ItemAlias("gtin:${brandA.value}", "milk")),
        )
        assertThat(BarcodeMemory(history).remembered("milk")).isNull()
    }

    @Test fun `only the last five barcoded purchases vote, so a brand switch settles`() {
        val switched = listOf(bought("milk", brandA, 1 * day)) + (2..6).map { bought("milk", brandB, it * day) }
        assertThat(BarcodeMemory(HistorySnapshot(purchases = switched)).remembered("milk"))
            .isEqualTo(RememberedBarcode(brandB, 6 * day))
        val recentMix = switched.drop(2) + bought("milk", brandA, 7 * day)
        assertThat(BarcodeMemory(HistorySnapshot(purchases = recentMix)).remembered("milk")).isNull()
    }

    @Test fun `an alias for a named scan is the fallback, undated, and resolves through chains`() {
        val history = HistorySnapshot(aliases = listOf(
            ItemAlias("gtin:${brandA.value}", "leche"), ItemAlias("leche", "milk")))
        assertThat(BarcodeMemory(history).remembered(typed("milk"))).isEqualTo(RememberedBarcode(brandA, null))
        val twoScans = history.copy(aliases = history.aliases + ItemAlias("gtin:${brandB.value}", "milk"))
        assertThat(BarcodeMemory(twoScans).remembered("milk")).isNull()
    }

    @Test fun `no barcoded history means no memory`() {
        val history = HistorySnapshot(
            purchases = listOf(bought("milk", null, day), bought("bread", brandA, day)),
            aliases = listOf(ItemAlias("whole milk", "milk")),
        )
        assertThat(BarcodeMemory(history).remembered(typed("milk"))).isNull()
        assertThat(BarcodeMemory(HistorySnapshot()).remembered("milk")).isNull()
    }

    /**
     * Memory never changes identity. Rows keep `gtin = null`, and even a row
     * that did carry the remembered code would resolve to the key it already
     * has, through the alias or because an unaliased code keys by name.
     */
    @Test fun `memory never changes a row's identity key`() {
        val viaAlias = HistorySnapshot(aliases = listOf(ItemAlias("gtin:${brandA.value}", "milk")))
        val viaPurchase = HistorySnapshot(purchases = listOf(bought("milk", brandB, day)))
        for (history in listOf(viaAlias, viaPurchase)) {
            val aliases = history.aliases.associate { it.fromKey to it.toKey }
            val row = typed("Milk")
            val remembered = BarcodeMemory(history).remembered(row)!!
            assertThat(row.gtin).isNull()
            assertThat(ItemIdentity.key(row.copy(gtin = remembered.gtin), aliases)).isEqualTo(ItemIdentity.key(row, aliases))
            assertThat(ItemIdentity.key(row, aliases)).isEqualTo("milk")
        }
    }
}

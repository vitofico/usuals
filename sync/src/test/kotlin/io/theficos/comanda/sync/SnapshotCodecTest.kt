package io.theficos.comanda.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.json.*
import io.theficos.comanda.model.NORMALIZER_VERSION

class SnapshotCodecTest {
    @Test fun actualShoppingProjectionRoundTripsWithNullableFactsHistoryRulesAndRevisions() {
        val row = Row("item", buildJsonObject { put("key", "milk"); put("name", "Milk"); put("quantity", "2.0"); put("unit", "LITRE"); put("checked", true); put("note", JsonNull) }, mapOf("name" to "capture", "quantity" to "edit"), lifecycle = "close", archived = true)
        val projection = Projection(name = "Home", rows = mapOf("item" to row),
            trips = mapOf("trip" to buildJsonObject { put("closedAt", 123L) }),
            purchases = mapOf("item" to buildJsonObject { put("id", "item"); put("tripId", "trip"); put("name", "Milk"); put("key", "milk"); put("boughtAt", 120L) }),
            rules = mapOf("milk" to buildJsonObject { put("revision", "rule"); put("everyDays", 21) }),
            closes = mapOf("close" to Close("trip", listOf("item"))), aliases = mapOf("whole milk" to "milk"))
        assertThat(SnapshotCodec.decode(SnapshotCodec.encode(projection))).isEqualTo(projection)
    }
    @Test fun rejectsAliasCyclesDanglingPurchasesInvalidQuantitiesAndIncompatibleVersions() {
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(Projection(aliases = mapOf("a" to "b", "b" to "a"))) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(Projection(purchases = mapOf("p" to buildJsonObject { put("id", "p"); put("tripId", "missing") }))) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(Projection(rows = mapOf("r" to Row("r", buildJsonObject { put("name", "Milk"); put("quantity", "Infinity") })))) }
        val bytes = "{\"schema\":2,\"normalizer\":$NORMALIZER_VERSION,\"projection\":{}}".toByteArray()
        assertThrows(UnsupportedWireVersion::class.java) { SnapshotCodec.decode(bytes) }
    }
}

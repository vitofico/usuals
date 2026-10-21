package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.json.*

class SnapshotCodecTest {
    @Test fun actualShoppingProjectionIsEncodedWithNullableFactsHistoryRulesAndRevisions() {
        val row = Row("item", buildJsonObject { put("key", "milk"); put("name", "Milk"); put("quantity", "2.0"); put("unit", "LITRE"); put("checked", true); put("note", JsonNull) }, mapOf("name" to "capture", "quantity" to "edit"), lifecycle = "close", archived = true)
        val projection = Projection(name = "Home", rows = mapOf("item" to row),
            trips = mapOf("trip" to buildJsonObject { put("closedAt", 123L) }),
            purchases = mapOf("item" to buildJsonObject { put("id", "item"); put("tripId", "trip"); put("name", "Milk"); put("key", "milk"); put("boughtAt", 120L) }),
            rules = mapOf("milk" to buildJsonObject { put("revision", "rule"); put("everyDays", 21) }),
            closes = mapOf("close" to Close("trip", listOf("item"))), aliases = mapOf("whole milk" to "milk"))
        val encoded = Wire.json.parseToJsonElement(SnapshotCodec.encode(projection).decodeToString()).jsonObject.getValue("projection")
        assertThat(Wire.json.decodeFromJsonElement(Projection.serializer(), encoded)).isEqualTo(projection)
    }
    @Test fun rejectsAliasCyclesDanglingPurchasesAndInvalidQuantities() {
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(Projection(aliases = mapOf("a" to "b", "b" to "a"))) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(Projection(purchases = mapOf("p" to buildJsonObject { put("id", "p"); put("tripId", "missing") }))) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(Projection(rows = mapOf("r" to Row("r", buildJsonObject { put("name", "Milk"); put("quantity", "Infinity") })))) }
    }
}

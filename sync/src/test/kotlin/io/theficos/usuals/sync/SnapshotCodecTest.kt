package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import io.theficos.usuals.model.NORMALIZER_VERSION
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
        assertThat(Wire.json.decodeFromString(Projection.serializer(), SnapshotCodec.encode(projection).decodeToString())).isEqualTo(projection)
    }
    @Test fun rejectsAliasCyclesDanglingPurchasesAndInvalidQuantities() {
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(Projection(aliases = mapOf("a" to "b", "b" to "a"))) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(Projection(purchases = mapOf("p" to buildJsonObject { put("id", "p"); put("tripId", "missing") }))) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(Projection(rows = mapOf("r" to Row("r", buildJsonObject { put("name", "Milk"); put("quantity", "Infinity") })))) }
    }
    @Test fun withoutReplayEveryBoundStillHolds() {
        val dangling = Projection(purchases = mapOf("p" to buildJsonObject { put("id", "p"); put("tripId", "missing") }))
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(dangling) }
        SnapshotCodec.encode(dangling, replay = false)
        val milk = Row("milk", buildJsonObject { put("name", "Milk") })
        for (invalid in listOf(
            Projection(rows = mapOf("other" to milk)), Projection(rows = mapOf(" " to milk.copy(id = " "))),
            Projection(purchases = mapOf("p" to buildJsonObject { put("id", "q") })), Projection(trips = mapOf("t" to buildJsonObject { put("name", "Shop") })),
            Projection(aliases = mapOf("a" to "b", "b" to "a")), Projection(redirects = mapOf("a" to "a")),
            Projection(rules = mapOf("milk" to buildJsonObject { put("everyDays", 0) })),
            Projection(seen = mapOf("i" to Intent("i", "l", "capture", JsonObject(emptyMap()), schema = 2, normalizer = NORMALIZER_VERSION))),
            Projection(seen = mapOf("i" to Intent("j", "l", "capture", JsonObject(emptyMap()), normalizer = NORMALIZER_VERSION))),
            Projection(closes = mapOf("c" to Close("", emptyList()))), Projection(name = "x".repeat(101)),
        )) assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(invalid, replay = false) }
    }
    @Test fun theCapCountsTheSnapshotEnvelope() {
        fun sized(filler: Int) = Projection(preferences = mapOf("filler" to buildJsonObject { put("text", "x".repeat(filler)) }))
        fun envelope(p: Projection) = Wire.json.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("schema", 1); put("normalizer", NORMALIZER_VERSION); put("projection", Wire.json.encodeToJsonElement(Projection.serializer(), p))
        }).toByteArray().size
        val limit = 7 * 1024 * 1024 - 288
        val filler = limit - envelope(sized(0))
        assertThat(envelope(sized(filler))).isEqualTo(limit)
        assertThat(SnapshotCodec.encode(sized(filler), replay = false)).isEqualTo(Wire.json.encodeToString(Projection.serializer(), sized(filler)).toByteArray())
        assertThrows(WireTooLarge::class.java) { SnapshotCodec.encode(sized(filler + 1), replay = false) }
    }
}

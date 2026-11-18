package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.assertThrows

class HistoryDeletionTest {
    private val reducer = Reducer()
    private val trip = buildJsonObject { put("closedAt", 10) }
    private val purchase = buildJsonObject { put("id", "milk"); put("tripId", "shop"); put("key", "milk"); put("name", "Milk"); put("boughtAt", 10) }
    private val bystander = JsonObject(purchase + mapOf("id" to JsonPrimitive("tea"), "tripId" to JsonPrimitive("other")))
    private val initial = Projection(trips = mapOf("shop" to trip, "other" to trip), purchases = mapOf("milk" to purchase, "tea" to bystander),
        rows = mapOf("milk" to Row("milk", buildJsonObject { put("name", "Milk"); put("key", "milk") }, lifecycle = "finish", archived = true),
            "open" to Row("open", buildJsonObject { put("name", "Bread"); put("key", "bread") })),
        closes = mapOf("finish" to Close("shop", listOf("milk"))), seen = mapOf("finish" to Intent("finish", "home", "finish", buildJsonObject {})))
    private fun delete(state: Projection = initial, id: String = "delete") = reducer.apply(state,
        Intent(id, "home", "delete_trip", buildJsonObject { put("tripId", "shop") }))

    @Test fun deletionRemovesTheWholeShopWithoutRestoringOrChangingRows() {
        val result = delete()
        assertThat(result.outcome).isEqualTo(Outcome.APPLIED)
        assertThat(result.projection.trips.keys).containsExactly("other")
        assertThat(result.projection.purchases).isEqualTo(mapOf("tea" to bystander))
        assertThat(result.projection.rows).isEqualTo(initial.rows)
        assertThat(HistoryDeletion.contains(result.projection, "shop")).isTrue()
        assertThat(result.projection.cancelledCloses).contains("finish")
        assertThat(delete(result.projection).outcome).isEqualTo(Outcome.NO_OP)
        assertThat(delete(result.projection, "another-delete").outcome).isEqualTo(Outcome.NO_OP)
        val undo = reducer.apply(result.projection, Intent("undo", "home", "undo", buildJsonObject { put("finishId", "finish"); put("tripId", "shop") }))
        assertThat(undo.outcome).isEqualTo(Outcome.NO_OP)
        assertThat(undo.projection.rows).isEqualTo(initial.rows)
    }

    @Test fun importedAndOldShopsCanBeDeletedWithoutACloseRecord() {
        val result = delete(initial.copy(closes = emptyMap(), seen = emptyMap(), rows = emptyMap()))
        assertThat(result.outcome).isEqualTo(Outcome.APPLIED)
        assertThat(result.projection.purchases).isEqualTo(mapOf("tea" to bystander))
    }

    @Test fun deletionSurvivesSnapshotRestartAndTheResendHorizon() {
        var state = delete().projection
        repeat(Reducer.RESEND_HORIZON + 1) { index ->
            state = reducer.apply(state, Intent("later-$index", "home", "list_name", buildJsonObject {
                put("name", "Home"); put("expected", state.nameRevision?.let(::JsonPrimitive) ?: JsonNull)
            })).projection
        }
        assertThat(state.closes).isEmpty()
        val restored = Wire.json.decodeFromString(Projection.serializer(), SnapshotCodec.encode(state).decodeToString())
        assertThat(HistoryDeletion.contains(restored, "shop")).isTrue()
        val stale = reducer.apply(restored, Intent("stale", "home", "import", buildJsonObject {
            put("trips", JsonArray(listOf(JsonObject(trip + ("id" to JsonPrimitive("shop"))))))
            put("purchases", JsonArray(listOf(purchase)))
        }))
        assertThat(stale.outcome).isEqualTo(Outcome.APPLIED)
        assertThat(stale.projection.trips.keys).containsExactly("other")
        assertThat(stale.projection.purchases).isEqualTo(mapOf("tea" to bystander))
        val facts = buildJsonObject {
            put("name", "Bread"); put("key", "bread"); put("checkedAt", 10)
        }
        val checked = restored.rows.getValue("open").copy(facts = JsonObject(facts + ("checked" to JsonPrimitive(true))))
        val beforeFinish = restored.copy(rows = restored.rows + ("open" to checked))
        val candidate = buildJsonObject {
            put("id", "open"); put("facts", facts); put("expectedFields", buildJsonObject {})
        }
        val finish = reducer.apply(beforeFinish, Intent("stale-finish", "home", "finish", buildJsonObject {
            put("tripId", "shop"); put("closedAt", 10); put("rows", JsonArray(listOf(candidate)))
        }))
        assertThat(finish.outcome).isEqualTo(Outcome.NO_OP)
        assertThat(finish.projection.trips).isEqualTo(beforeFinish.trips)
        assertThat(finish.projection.rows).isEqualTo(beforeFinish.rows)
        assertThat(finish.projection.purchases).isEqualTo(beforeFinish.purchases)
    }

    @Test fun unknownAndMalformedDeletionCannotRemoveOtherHistory() {
        val unknown = reducer.apply(initial, Intent("unknown", "home", "delete_trip", buildJsonObject { put("tripId", "missing") }))
        assertThat(unknown.outcome).isEqualTo(Outcome.NO_OP)
        assertThat(unknown.projection.trips).isEqualTo(initial.trips)
        val malformed = reducer.apply(initial, Intent("bad", "home", "delete_trip", buildJsonObject { put("tripId", "shop"); put("extra", true) }))
        assertThat(malformed.outcome).isEqualTo(Outcome.REJECTED)
        assertThat(malformed.projection.purchases).isEqualTo(initial.purchases)
    }

    @Test fun deletionMetadataCannotBeChangedAsAnItemPreferenceOrContradictASnapshot() {
        val state = delete().projection
        val overwrite = reducer.apply(state, Intent("overwrite", "home", "preference", buildJsonObject {
            put("key", HistoryDeletion.KEY); put("expected", JsonNull)
            put("changes", buildJsonObject { put("category", "DAIRY") })
        }))
        assertThat(overwrite.outcome).isEqualTo(Outcome.REJECTED)
        assertThat(HistoryDeletion.contains(overwrite.projection, "shop")).isTrue()
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(state.copy(trips = state.trips + ("shop" to trip))) }
        assertThrows(IllegalArgumentException::class.java) { SnapshotCodec.encode(state.copy(purchases = mapOf("milk" to purchase))) }
    }
}

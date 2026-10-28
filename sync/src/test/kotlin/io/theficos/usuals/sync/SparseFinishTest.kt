package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class SparseFinishTest {
    private val reducer = Reducer()

    @Test fun acceptedSparseCapturesCanBeCheckedAndFinishedWithoutInventingQuantityFacts() {
        for (quantity in listOf(buildJsonObject { }, buildJsonObject { put("quantity", JsonNull) },
            buildJsonObject { put("unit", JsonNull) }, buildJsonObject { put("quantity", JsonNull); put("unit", JsonNull) })) {
            val checked = checked(quantity)
            val result = finish(checked, checked.rows.getValue("bread").facts)
            assertThat(result.outcome).isEqualTo(Outcome.APPLIED)
            assertThat(result.projection.rows.getValue("bread").archived).isTrue()
            assertThat(result.projection.purchases.getValue("bread")).isEqualTo(JsonObject(quantity + mapOf(
                "id" to JsonPrimitive("bread"), "tripId" to JsonPrimitive("trip"), "boughtAt" to JsonPrimitive(100L),
                "key" to JsonPrimitive("bread"), "name" to JsonPrimitive("Bread"))))
            SnapshotCodec.encode(result.projection)
        }
    }

    @Test fun aFinishCannotOmitStoredQuantityOrUnitEvenWhenExplicitlyNull() {
        for (quantity in listOf(buildJsonObject { put("quantity", "2.0"); put("unit", "PIECE") },
            buildJsonObject { put("quantity", JsonNull); put("unit", JsonNull) })) {
            val checked = checked(quantity)
            for (field in listOf("quantity", "unit")) {
                val result = finish(checked, JsonObject(checked.rows.getValue("bread").facts - field))
                assertThat(result.outcome).isEqualTo(Outcome.REJECTED)
                assertThat(result.projection.rows).isEqualTo(checked.rows)
                assertThat(result.projection.purchases).isEmpty()
                assertThat(result.projection.trips).isEmpty()
            }
        }
    }

    @Test fun sparseCompatibilityKeepsExactFactsAndCompleteRevisionGuards() {
        val checked = checked(buildJsonObject { put("quantity", "2.0"); put("unit", "PIECE") })
        val row = checked.rows.getValue("bread")
        val different = finish(checked, JsonObject(row.facts + ("quantity" to JsonPrimitive("3.0"))))
        assertThat(different.outcome).isEqualTo(Outcome.CONFLICT)
        assertThat(different.projection.purchases).isEmpty()
        val missingRevision = finish(checked, row.facts, row.revisions - "quantity")
        assertThat(missingRevision.outcome).isEqualTo(Outcome.CONFLICT)
        assertThat(missingRevision.projection.purchases).isEmpty()
        val complete = finish(checked, row.facts)
        assertThat(complete.outcome).isEqualTo(Outcome.APPLIED)
        assertThat(complete.projection.purchases.getValue("bread")["quantity"]).isEqualTo(JsonPrimitive("2.0"))
        assertThat(complete.projection.purchases.getValue("bread")["unit"]).isEqualTo(JsonPrimitive("PIECE"))
    }

    private fun checked(quantity: JsonObject): Projection {
        val capture = reducer.apply(Projection(), Intent("capture", "list", "capture", JsonObject(quantity + mapOf(
            "proposedId" to JsonPrimitive("bread"), "key" to JsonPrimitive("bread"), "name" to JsonPrimitive("Bread")))))
        assertThat(capture.outcome).isEqualTo(Outcome.APPLIED)
        val restored = Wire.json.decodeFromString(Projection.serializer(), SnapshotCodec.encode(capture.projection).decodeToString())
        val checked = reducer.apply(restored, Intent("check", "list", "check", buildJsonObject {
            put("target", "bread"); put("expected", buildJsonObject { put("check", restored.rows.getValue("bread").revisions["check"]) })
            put("checked", true); put("checkedAt", 100L)
        }))
        assertThat(checked.outcome).isEqualTo(Outcome.APPLIED)
        return checked.projection
    }

    private fun finish(state: Projection, facts: JsonObject, revisions: Map<String, String?> = state.rows.getValue("bread").revisions): Reduction =
        reducer.apply(state, Intent("finish", "list", "finish", buildJsonObject {
            put("tripId", "trip"); put("closedAt", 200L)
            put("rows", buildJsonArray { add(buildJsonObject {
                put("id", "bread"); put("facts", facts); put("expectedLifecycle", state.rows.getValue("bread").lifecycle)
                put("expectedFields", JsonObject(revisions.mapValues { it.value?.let(::JsonPrimitive) ?: JsonNull }))
            }) })
        }))
}

package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class CaptureReuseTest {
    private val reducer = Reducer()
    private fun capture(id: String, reuse: Boolean = true) = Intent(id, "home", if (reuse) "reuse" else "capture", buildJsonObject {
        put("proposedId", "row-$id"); put("key", "milk"); put("name", "Milk")
        put("quantity", "2.0"); put("unit", "LITRE"); put("normalizerVersion", 1)
    })
    @Test fun concurrentAddAgainNeverChangesAnExistingQuantityOrCreatesARedirect() {
        val first = reducer.apply(Projection(), capture("first"))
        assertThat(first.outcome).isEqualTo(Outcome.APPLIED)
        val reused = reducer.apply(first.projection, capture("phone"))
        assertThat(reused.outcome).isEqualTo(Outcome.NO_OP)
        assertThat(reused.projection.rows).isEqualTo(first.projection.rows)
        assertThat(reused.projection.redirects).isEmpty()
        val restored = Wire.json.decodeFromString<Projection>(SnapshotCodec.encode(reused.projection).decodeToString())
        assertThat(reducer.apply(restored, capture("phone")).outcome).isEqualTo(Outcome.NO_OP)
        val regular = reducer.apply(reused.projection, capture("regular", false))
        assertThat(regular.outcome).isEqualTo(Outcome.MERGED)
        assertThat(regular.projection.rows.getValue("row-first").facts.getValue("quantity").jsonPrimitive.content).isEqualTo("4.0")
    }
    @Test fun checkedRowsCanBeBoughtAgainAndUnknownCommandsAreRejected() {
        val first = reducer.apply(Projection(), capture("first")).projection
        val checked = reducer.apply(first, Intent("checked", "home", "check", buildJsonObject {
            put("target", "row-first"); put("expected", buildJsonObject { put("check", "first") })
            put("checked", true); put("checkedAt", 100)
        }))
        assertThat(checked.outcome).isEqualTo(Outcome.APPLIED)
        val reused = reducer.apply(checked.projection, capture("new"))
        assertThat(reused.outcome).isEqualTo(Outcome.APPLIED)
        assertThat(reused.projection.rows).hasSize(2)
        assertThat(reducer.apply(Projection(), capture("future").copy(kind = "unknown_reuse")).outcome).isEqualTo(Outcome.REJECTED)
    }
    @Test fun undoGuardsFormerlyAbsentFieldsAgainstAConcurrentEdit() {
        val before = reducer.apply(Projection(), capture("first")).projection
        val changed = reducer.apply(before, Intent("store", "home", "edit", buildJsonObject {
            put("target", "row-first"); put("changes", buildJsonObject { put("store", "Bakery") })
            put("expected", buildJsonObject { put("store", JsonNull) })
        })).projection
        val removal = reducer.apply(changed, Intent("undo", "home", "delete", buildJsonObject {
            put("target", "row-first"); put("expected", buildJsonObject { put("lifecycle", "first"); put("store", JsonNull) })
            put("deletedAt", 200); put("updatedAt", 200)
        }))
        assertThat(removal.outcome).isEqualTo(Outcome.CONFLICT)
        assertThat(removal.projection.tombstones).isEmpty()
    }
}

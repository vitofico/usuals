package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class MergeTest {
    private val reducer = Reducer()
    private fun facts(key: String) = buildJsonObject { put("name", key); put("key", key); put("checked", false) }
    private fun state() = Projection(rows = mapOf("a" to Row("a", facts("scan"), lifecycle = "import"), "b" to Row("b", facts("milk"), lifecycle = "import")))
    private fun merge(id: String = "merge") = Intent(id, "list", "merge", buildJsonObject {
        put("source", "a"); put("target", "b")
        put("sourceExpected", buildJsonObject { put("lifecycle", "import") })
        put("targetExpected", buildJsonObject { put("lifecycle", "import") })
        put("expectedAliasRevision", JsonNull); put("aliasDelta", buildJsonObject { put("scan", "milk") })
        put("updatedAt", 10); put("deletedAt", 10)
    })
    @Test fun delayedGuardedActionsAgainstMergedGenesisIncarnationCannotTouchDestination() {
        val merged = reducer.apply(state(), merge())
        assertThat(merged.outcome).isEqualTo(Outcome.MERGED)
        for (kind in listOf("delete", "check", "edit")) {
            val delayed = Intent("late-$kind", "list", kind, buildJsonObject {
                put("target", "a"); put("expected", buildJsonObject { put("lifecycle", "import"); if (kind == "check") put("check", JsonNull); if (kind == "edit") put("name", JsonNull) })
                put("checked", true); put("checkedAt", 11); put("changes", buildJsonObject { put("name", "Unexpected") })
            })
            val reaped = merged.projection.copy(rows = merged.projection.rows - "a")
            assertThat(reducer.apply(reaped, delayed).outcome).isEqualTo(Outcome.CONFLICT)
            val after = reducer.apply(merged.projection, delayed)
            assertThat(after.outcome).isEqualTo(Outcome.CONFLICT)
            assertThat(after.projection.rows.getValue("b")).isEqualTo(state().rows.getValue("b"))
            assertThat(after.projection.tombstones.keys).containsExactly("a")
        }
        assertThat(reducer.apply(merged.projection, merge()).outcome).isEqualTo(Outcome.NO_OP)
    }
    @Test fun newlyPopulatedFieldRetainsAStaleMergeAndUnrelatedAliasDeltaIsRejected() {
        val state = state()
        val changed = state.copy(rows = state.rows + ("a" to state.rows.getValue("a").copy(revisions = mapOf("note" to "new-note"))))
        val stale = reducer.apply(changed, merge())
        assertThat(stale.outcome).isEqualTo(Outcome.CONFLICT)
        assertThat(stale.projection.conflicts["merge"]).isNotNull()
        assertThat(stale.projection.tombstones).isEmpty()
        val bad = merge().let { it.copy(payload = JsonObject(it.payload + ("aliasDelta" to buildJsonObject { put("unrelated", "milk") }))) }
        val rejected = reducer.apply(state, bad)
        assertThat(rejected.outcome).isEqualTo(Outcome.REJECTED)
        assertThat(rejected.projection.rows).isEqualTo(state.rows)
        assertThat(rejected.projection.aliases).isEmpty()
    }
}

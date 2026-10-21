package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

/** The resend horizon of docs/sync/lists-v2.md: a projection remembers the last 1,000 intents applied to it. */
class ResendHorizonTest {
    private val reducer = Reducer()
    private val horizon = 1000
    private var next = 0

    @Test fun aLongLivedListStaysWellUnderTheCapAndPhonesStillDecodeIt() {
        val state = toggle(add(Projection(name = "Groceries"), "milk", "Milk"), "milk", 25_000)
        assertThat(state.seen).hasSize(horizon)
        val stored = SnapshotCodec.encode(state)
        assertThat(stored.size).isLessThan(1024 * 1024)
        // As a phone reads a list from the server, with the strict decoder's limits on keys and nodes.
        val body = Wire.json.encodeToString(Projection.serializer(), state).toByteArray()
        val decoded = Wire.json.decodeFromJsonElement(Projection.serializer(), Wire.strictJson(body, 10 * 1024 * 1024, payload = true))
        assertThat(decoded).isEqualTo(state)
        assertThat(decoded.seen.keys.toList()).isEqualTo(state.seen.keys.toList())
    }

    @Test fun aResendWithinTheHorizonIsANoOpAndAnOlderOneNeverTakesEffectTwice() {
        val milk = capture("milk", "Milk")
        var state = toggle(reducer.apply(Projection(), milk).projection, "milk", horizon - 1)
        val inside = reducer.apply(state, milk)
        assertThat(inside.outcome).isEqualTo(Outcome.NO_OP)
        assertThat(inside.projection).isSameInstanceAs(state)

        state = toggle(state, "milk", 1)
        val older = reducer.apply(state, milk)
        assertThat(older.outcome).isEqualTo(Outcome.REJECTED)
        assertThat(older.projection.rows).isEqualTo(state.rows)
        assertThat(older.projection.seen).hasSize(horizon)
    }

    @Test fun repeatCyclesStillFollowADeletionAndALaterTripOnceOlderIntentsAreDropped() {
        var state = add(Projection(), "tea", "Tea")
        state = apply(state, "preference", buildJsonObject { put("key", "milk"); put("expected", JsonNull); put("changes", buildJsonObject { put("repeatEveryDays", 1) }) })
        state = shop(add(state, "milk", "Milk"), "milk", at = DAY)
        val rule = state.rules.getValue("milk").getValue("revision").jsonPrimitive.content
        state = repeat(state, rule, at = 3 * DAY)
        state = toggle(state, "tea", 2 * horizon)
        assertThat(state.seen).hasSize(horizon)

        val suggested = state.occurrences.values.single()
        val deleted = apply(state, "delete", buildJsonObject {
            put("target", suggested); put("expected", buildJsonObject { put("lifecycle", state.rows.getValue(suggested).lifecycle) })
        })
        val cycled = repeat(shop(add(deleted, "bread", "Bread"), "bread", at = 4 * DAY), rule, at = 5 * DAY)
        assertThat(cycled.occurrences).hasSize(2)

        // A deletion the list no longer remembers cannot anchor a cycle: the rule waits for the next purchase.
        val forgotten = shop(add(toggle(deleted, "tea", horizon), "bread", "Bread"), "bread", at = 4 * DAY)
        assertThat(nextRepeatCycle(forgotten, LIST, "milk", rule, "milk")).isNull()
    }

    private fun id() = "intent-${next++}"
    private fun apply(state: Projection, kind: String, payload: JsonObject): Projection {
        val result = reducer.apply(state, Intent(id(), LIST, kind, payload))
        assertThat(result.outcome).isEqualTo(Outcome.APPLIED)
        return result.projection
    }
    private fun capture(row: String, name: String) = Intent(id(), LIST, "capture", buildJsonObject {
        put("proposedId", row); put("key", name.lowercase()); put("name", name); put("quantity", JsonNull); put("unit", JsonNull)
    })
    private fun add(state: Projection, row: String, name: String): Projection {
        val result = reducer.apply(state, capture(row, name))
        assertThat(result.outcome).isEqualTo(Outcome.APPLIED)
        return result.projection
    }
    private fun check(state: Projection, row: String, checked: Boolean, at: Long = 1) = apply(state, "check", buildJsonObject {
        put("target", row); put("expected", buildJsonObject { put("check", state.rows.getValue(row).revisions["check"]) })
        put("checked", checked); put("checkedAt", if (checked) JsonPrimitive(at) else JsonNull)
    })
    private fun toggle(initial: Projection, row: String, times: Int): Projection {
        var state = initial
        repeat(times) { state = check(state, row, !state.rows.getValue(row).facts.bool("checked")) }
        return state
    }
    private fun shop(initial: Projection, row: String, at: Long): Projection {
        val state = check(initial, row, true, at)
        val bought = state.rows.getValue(row)
        return apply(state, "finish", buildJsonObject {
            put("tripId", "trip-$row-$at"); put("closedAt", at)
            put("rows", buildJsonArray { add(buildJsonObject {
                put("id", row); put("facts", bought.facts); put("expectedLifecycle", bought.lifecycle)
                put("expectedFields", JsonObject(bought.revisions.mapValues { JsonPrimitive(it.value) }))
            }) })
        })
    }
    private fun repeat(state: Projection, rule: String, at: Long): Projection {
        val (occurrence, cycle) = requireNotNull(nextRepeatCycle(state, LIST, "milk", rule, "milk"))
        return apply(state, "repeat", buildJsonObject {
            put("proposedId", "suggested-$at"); put("key", "milk"); put("ruleRevision", rule); put("enablingPurchase", "milk")
            put("asOf", at); put("occurrence", occurrence)
            if (cycle != null) put("cycle", Wire.json.encodeToJsonElement(RepeatCycle.serializer(), cycle))
        })
    }
    private companion object {
        const val LIST = "home"
        const val DAY = 86_400_000L
    }
}

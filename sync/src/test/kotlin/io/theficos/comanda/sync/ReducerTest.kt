package io.theficos.comanda.sync

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Test

class ReducerTest {
    private val json = Json { encodeDefaults = true }
    private val reducer = Reducer()

    @Test fun reviewedScenariosConvergeAcrossReplayAndRestart() {
        val fixture = json.parseToJsonElement(javaClass.getResource("/shared-list-v1/reducer.json")!!.readText()).jsonObject
        val cases = fixture.getValue("cases").jsonArray
        assertThat(cases).hasSize(38)
        for (scenario in cases.map { it.jsonObject }) {
            val initial = scenario.obj("initial")
            val scoped = (initial["lists"] as? JsonObject) ?: jsonObject("home" to initial)
            var first = scoped.mapValues { projection(it.value.jsonObject) }
            var second = first
            val outcomes = mutableMapOf<String, String>()
            val retries = mutableSetOf<String>()
            val occurrences = mutableMapOf<String, String>()
            for (op in scenario.getValue("operations").jsonArray.map { it.jsonObject }) {
                val list = op["list"].stringOrNull() ?: "home"
                val id = op.text("id")
                val payload = if (op.text("kind") == "repeat") {
                    val symbolic = op["cycle"] as? JsonObject
                    val cycle = symbolic?.let { RepeatCycle(occurrences.getValue(it.text("previous")), it.text("deletion"), it.text("finish")) }
                    val occurrence = Reducer.occurrenceId(list, op.text("key"), op.text("ruleRevision"), op.text("enablingPurchase"), cycle)
                    occurrences[op.text("occurrence")] = occurrence
                    JsonObject(op + ("occurrence" to JsonPrimitive(occurrence)) + if (cycle == null) emptyMap() else mapOf("cycle" to json.encodeToJsonElement(cycle)))
                } else op
                val intent = Intent(id, list, op.text("kind"), JsonObject(payload - setOf("id", "kind", "list")))
                if (id in outcomes) retries += id
                val result = reducer.apply(first.getValue(list), intent)
                if (id !in outcomes) outcomes[id] = result.outcome.name.lowercase()
                first = first + (list to result.projection)
                // A second replica resumes from disk before every delivery, with duplicated delivery.
                val restored = json.decodeFromString<Projection>(json.encodeToString(second.getValue(list)))
                val duplicate = reducer.apply(reducer.apply(restored, intent).projection, intent)
                second = second + (list to duplicate.projection)
            }
            assertWithMessage(scenario.text("id")).that(second).isEqualTo(first)
            val actual = if (initial.containsKey("lists")) jsonObject("lists" to JsonObject(first.mapValues { facts(it.value) })) else facts(first.getValue("home"))
            for ((path, expected) in scenario.obj("expected").obj("facts")) {
                var value: JsonElement? = actual
                for (segment in path.split('.')) value = (value as? JsonObject)?.get(segment)
                assertWithMessage("${scenario.text("id")}: $path").that(value ?: JsonNull).isEqualTo(expected)
            }
            for ((id, expected) in scenario.obj("expected").obj("outcomes")) {
                val outcome = if (id in retries && outcomes[id] in setOf("applied", "merged") && expected.jsonPrimitive.content == "applied_once") "applied_once" else outcomes[id]
                assertWithMessage("${scenario.text("id")}: outcome $id").that(outcome).isEqualTo(expected.jsonPrimitive.content)
            }
        }
    }

    @Test fun unknownVersionDoesNotConsumeOrAdvance() {
        val before = Projection()
        val result = reducer.apply(before, Intent("a", "list", "capture", JsonObject(emptyMap()), schema = 2))
        assertThat(result.outcome).isEqualTo(Outcome.UPGRADE_REQUIRED)
        assertThat(result.projection).isEqualTo(before)
    }

    @Test fun malformedQuantityRollsBackWholeOperation() {
        val op = Intent("a", "list", "capture", json.parseToJsonElement("""{"proposedId":"x","key":"milk","name":"Milk","quantity":"NaN","unit":"LITRE"}""").jsonObject)
        val result = reducer.apply(Projection(), op)
        assertThat(result.outcome).isEqualTo(Outcome.REJECTED)
        assertThat(result.projection.rows).isEmpty()
    }

    @Test fun repeatRetryAfterCloseDoesNotResurrectOrLoseRedirect() {
        val occurrence = Reducer.occurrenceId("home", "milk", "rule", "buy")
        val initial = Projection(rows = mapOf("winner" to Row("winner", jsonObject("key" to JsonPrimitive("milk")), lifecycle = "closed", archived = true)), occurrences = mapOf(occurrence to "winner"))
        val op = Intent("retry", "home", "repeat", json.parseToJsonElement("""{"proposedId":"loser","key":"milk","ruleRevision":"rule","enablingPurchase":"buy","occurrence":"$occurrence"}""").jsonObject)
        val result = reducer.apply(initial, op)
        assertThat(result.outcome).isEqualTo(Outcome.NO_OP)
        assertThat(result.projection.rows.getValue("winner").archived).isTrue()
        assertThat(result.projection.redirects["loser"]).isEqualTo("winner")
    }

    private fun projection(initial: JsonObject): Projection {
        val rows = (initial["rows"] as? JsonArray).orEmpty().map { it.jsonObject }.associate { row ->
            val revisions = row.filterKeys { it.endsWith("Revision") }.mapKeys { it.key.removeSuffix("Revision").let { name -> if (name == "check") "check" else name } }.mapValues { it.value.stringOrNull() }
            row.text("id") to Row(row.text("id"), JsonObject(row.filterKeys { it !in setOf("id", "lifecycle", "archived") && !it.endsWith("Revision") }), revisions, row["lifecycle"].stringOrNull(), row.bool("archived"))
        }.toMutableMap()
        for (auto in (initial["automaticRows"] as? JsonArray).orEmpty().map { it.jsonObject }) {
            rows[auto.text("id")] = Row(auto.text("id"), JsonObject(auto - setOf("id", "enabledBy", "unchanged")), enabledBy = auto.text("enabledBy"), automaticUnchanged = auto.bool("unchanged"))
        }
        val purchases = (initial["purchases"] as? JsonArray).orEmpty().map { it.jsonObject }.map { purchase ->
            if ("key" in purchase) purchase else JsonObject(purchase + ("key" to JsonPrimitive(purchase.text("name").lowercase())))
        }.associateBy { it.text("id") }
        return Projection(rows = rows, purchases = purchases,
            trips = purchases.values.associate { it.text("tripId") to JsonObject(emptyMap()) },
            rules = (initial["rules"] as? JsonObject).orEmpty().mapValues { it.value.jsonObject },
            preferences = (initial["preferences"] as? JsonObject).orEmpty().mapValues { it.value.jsonObject },
            aliases = (initial["aliases"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content },
            aliasRevision = if (initial.containsKey("aliases")) "genesis" else null,
            name = initial["name"].stringOrNull().orEmpty(), nameRevision = initial["nameRevision"].stringOrNull(),
            closes = (initial["effectiveCloses"] as? JsonArray).orEmpty().map { it.jsonObject }.associate { it.text("id") to Close(it.text("tripId"), it.getValue("itemIds").jsonArray.map { id -> id.jsonPrimitive.content }) })
    }
    private fun facts(state: Projection): JsonObject = buildJsonObject {
        put("rows", JsonObject(state.rows.mapValues { it.value.facts }))
        put("repeatReady", JsonObject(state.rules.mapValues { (key, rule) ->
            val purchase = state.purchases.values.filter { it["key"].stringOrNull() == key }
                .sortedWith(compareByDescending<JsonObject> { it["boughtAt"]?.jsonPrimitive?.longOrNull ?: 0 }.thenBy { it.text("id") }).firstOrNull()
            JsonPrimitive(nextRepeatCycle(state, "home", key, rule.text("revision"), purchase?.text("id") ?: rule.text("revision")) != null)
        }))
        put("activeRowCount", state.rows.values.count { !it.archived && it.id !in state.tombstones })
        put("purchases", JsonObject(state.purchases))
        put("purchaseCount", state.purchases.size)
        put("tripCount", state.trips.size)
        put("conflicts", JsonObject(state.conflicts.mapValues { jsonObject("incoming" to it.value.incoming) }))
        put("redirects", JsonObject(state.redirects.mapValues { JsonPrimitive(it.value) }))
        put("tombstones", JsonObject(state.tombstones.mapValues { JsonPrimitive(it.value) }))
        put("cancelledCloses", JsonObject(state.cancelledCloses.associateWith { JsonPrimitive(true) }))
        put("aliases", JsonObject(state.aliases.mapValues { JsonPrimitive(it.value) }))
        put("preferences", JsonObject(state.preferences))
        put("name", state.name)
    }
}

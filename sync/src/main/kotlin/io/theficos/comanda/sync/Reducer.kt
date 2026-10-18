package io.theficos.comanda.sync

import io.theficos.comanda.model.Gtin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Wire facts stay explicit. Text normalization and physical clocks never arbitrate a write. */
@Serializable
data class Intent(
    val id: String,
    val listId: String,
    val kind: String,
    val payload: JsonObject,
    val schema: Int = 1,
    val normalizer: Int = 1,
)

@Serializable
data class Row(
    val id: String,
    val facts: JsonObject,
    val revisions: Map<String, String?> = emptyMap(),
    val lifecycle: String? = null,
    val archived: Boolean = false,
    val enabledBy: String? = null,
    val automaticUnchanged: Boolean = false,
    val repeatOccurrence: String? = null,
    val repeatRule: String? = null,
    val repeatPurchase: String? = null,
    val repeatBase: String? = null,
    val repeatPrevious: String? = null,
)

@Serializable
data class Close(val tripId: String, val itemIds: List<String>)
@Serializable
data class Conflict(val incoming: JsonObject, val current: JsonObject, val code: String)
@Serializable
data class Projection(
    val rows: Map<String, Row> = emptyMap(),
    val purchases: Map<String, JsonObject> = emptyMap(),
    val trips: Map<String, JsonObject> = emptyMap(),
    val preferences: Map<String, JsonObject> = emptyMap(),
    val rules: Map<String, JsonObject> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    val aliasRevision: String? = null,
    val name: String = "",
    val nameRevision: String? = null,
    val redirects: Map<String, String> = emptyMap(),
    val tombstones: Map<String, String> = emptyMap(),
    val closes: Map<String, Close> = emptyMap(),
    val cancelledCloses: Set<String> = emptySet(),
    val occurrences: Map<String, String> = emptyMap(),
    val repeatHeads: Map<String, String> = emptyMap(),
    val conflicts: Map<String, Conflict> = emptyMap(),
    val rejected: Map<String, String> = emptyMap(),
    val seen: Map<String, Intent> = emptyMap(),
)

enum class Outcome { APPLIED, MERGED, NO_OP, CONFLICT, REJECTED, UPGRADE_REQUIRED }
data class Reduction(val projection: Projection, val outcome: Outcome)

/** Pure reducer for one ordered, authenticated list stream. The caller commits it with its cursor. */
class Reducer(private val normalizer: Int = 1) {
    fun apply(before: Projection, intent: Intent): Reduction {
        if (intent.schema != 1 || intent.normalizer != normalizer || incompatibleVersion(intent.payload)) return Reduction(before, Outcome.UPGRADE_REQUIRED)
        val previous = before.seen[intent.id]
        if (previous != null) {
            return if (previous == intent) Reduction(before, Outcome.NO_OP)
            else Reduction(before, Outcome.REJECTED)
        }
        val work = Work(before, intent)
        val outcome = try { work.run() }
        catch (_: IllegalArgumentException) { Outcome.REJECTED }
        catch (_: NoSuchElementException) { Outcome.REJECTED }
        catch (_: IllegalStateException) { Outcome.REJECTED }
        catch (_: ArithmeticException) { Outcome.REJECTED }
        // Invalid bounded commands consume an authenticated log entry without partial application.
        val result = when (outcome) {
            Outcome.REJECTED -> before.copy(rejected = before.rejected + (intent.id to "invalid"))
            else -> work.state
        }
        return Reduction(result.copy(seen = result.seen + (intent.id to intent)), outcome)
    }

    private fun incompatibleVersion(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.any { (key, child) ->
            (key == "normalizerVersion" && child.jsonPrimitive.intOrNull != normalizer) ||
                (key == "schema" && child.jsonPrimitive.intOrNull != 1) || incompatibleVersion(child)
        }
        is JsonArray -> value.any(::incompatibleVersion)
        else -> false
    }

    private class Work(initial: Projection, private val intent: Intent) {
        var state = initial
        private val p = intent.payload
        private val id = intent.id
        private fun resolve(target: String): String {
            var value = target
            val seen = mutableSetOf<String>()
            while (state.redirects.containsKey(value)) {
                require(seen.add(value))
                value = state.redirects.getValue(value)
            }
            return value
        }
        private fun row(): Row? = p["target"].stringOrNull()?.let { if (it in state.tombstones) state.rows[it] else state.rows[it] ?: state.rows[resolve(it)] }
        private fun alive(row: Row?) = row != null && !row.archived && row.id !in state.tombstones
        private fun conflict(incoming: JsonObject = p, current: JsonObject = row()?.facts ?: JsonObject(emptyMap()), code: String = "stale"): Outcome {
            state = state.copy(conflicts = state.conflicts + (id to Conflict(incoming, current, code)))
            return Outcome.CONFLICT
        }
        private fun put(row: Row) { state = state.copy(rows = state.rows + (row.id to row)) }
        private fun guard(row: Row, expected: JsonObject): Boolean = expected.all { (field, revision) ->
            (if (field == "lifecycle") row.lifecycle else row.revisions[field]) == revision.stringOrNull()
        }
        fun run(): Outcome {
            require(id.isNotBlank() && intent.listId.isNotBlank())
            return when (intent.kind) {
                "capture" -> capture()
                "repeat" -> repeat()
                "edit" -> edit()
                "merge" -> merge()
                "check" -> check()
                "delete" -> delete()
                "finish" -> finish()
                "undo" -> undo()
                "alias" -> alias()
                "preference" -> preference()
                "list_name" -> renameList()
                "import" -> import()
                else -> Outcome.REJECTED
            }
        }
        private fun capture(): Outcome {
            val proposed = p.text("proposedId")
            val key = canonical(p.text("key"))
            require(proposed !in state.rows && proposed !in state.redirects && proposed !in state.tombstones)
            validateQuantity(p)
            validateFacts(p)
            require(key.isNotBlank())
            val existing = state.rows.values.firstOrNull { alive(it) && !it.facts.bool("checked") && canonical(it.facts.text("key")) == key }
            if (existing == null) {
                require(p.text("name").isNotBlank())
                val facts = JsonObject(p.filterKeys { it in ROW_FIELDS } + mapOf("key" to JsonPrimitive(key), "checked" to JsonPrimitive(false)))
                put(Row(proposed, facts, facts.keys.filter { it in REVISION_FIELDS && facts[it] != JsonNull }.associate { revisionField(it) to id }, id))
                return Outcome.APPLIED
            }
            val changes = existing.facts.toMutableMap()
            val incoming = p["quantity"]?.stringOrNull()?.toDouble()
            val amount = changes["quantity"]?.stringOrNull()?.toDouble()
            if (incoming != null) {
                val sum = if (amount == null) incoming else {
                    val factor = factor(p.text("unit"), existing.facts.text("unit")) ?: return conflict(p, existing.facts, "unit")
                    amount + incoming * factor
                }
                require(sum.isFinite())
                changes["quantity"] = JsonPrimitive(sum.toString())
                changes["unit"] = if (amount == null) p.getValue("unit") else existing.facts.getValue("unit")
            }
            for (field in listOf("note", "gtin", "store")) if (changes[field].stringOrNull() == null && p[field].stringOrNull() != null) changes[field] = p.getValue(field)
            val changed = changes.filter { (k, v) -> v != existing.facts[k] && k in REVISION_FIELDS }.keys.map(::revisionField)
            if (changed.isNotEmpty()) p["updatedAt"]?.let { changes["updatedAt"] = it }
            put(existing.copy(facts = JsonObject(changes), revisions = existing.revisions + changed.associateWith { id }))
            state = state.copy(redirects = state.redirects + (proposed to existing.id))
            return Outcome.MERGED
        }
        private fun edit(): Outcome {
            val row = row()
            if (!alive(row)) return conflict()
            row!!
            val changes = p.obj("changes")
            require(changes.isNotEmpty() && changes.keys.all { it in ROW_FIELDS && it !in setOf("checked", "checkedAt") })
            val required = changes.keys.filter { it in REVISION_FIELDS }.map(::revisionField).toSet()
            val expected = p.obj("expected")
            require(expected.keys.containsAll(required))
            if (!guard(row, expected)) return conflict(changes, row.facts)
            val facts = JsonObject(row.facts + changes)
            validateQuantity(facts)
            validateFacts(facts)
            require(facts.text("name").isNotBlank())
            val aliasDelta = (p["aliasDelta"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
            if (aliasDelta.isNotEmpty()) {
                if (p["expectedAliasRevision"].stringOrNull() != state.aliasRevision) return conflict(changes, row.facts, "alias_stale")
                val aliases = state.aliases + aliasDelta
                aliases.keys.forEach { canonical(it, aliases) }
            }
            if (changes.keys.any { it in setOf("name", "gtin", "key") }) {
                if (state.rows.values.any { it.id != row.id && alive(it) && !it.facts.bool("checked") && canonical(it.facts.text("key"), state.aliases + aliasDelta) == canonical(facts.text("key"), state.aliases + aliasDelta) }) return conflict(changes, row.facts, "identity")
            }
            if (aliasDelta.isNotEmpty()) state = state.copy(aliases = state.aliases + aliasDelta, aliasRevision = id)
            put(row.copy(facts = facts, revisions = row.revisions + required.associateWith { id }, automaticUnchanged = false))
            return Outcome.APPLIED
        }
        private fun merge(): Outcome {
            val source = state.rows[p.text("source")]
            val target = state.rows[p.text("target")]
            if (!alive(source) || !alive(target) || source!!.id == target!!.id || source.facts.bool("checked") || target.facts.bool("checked")) return conflict()
            val sourceExpected = p.obj("sourceExpected"); val targetExpected = p.obj("targetExpected")
            if (!sourceExpected.keys.containsAll(source.revisions.keys + "lifecycle") || !targetExpected.keys.containsAll(target.revisions.keys + "lifecycle")) return conflict(p, target.facts)
            require(p.containsKey("expectedAliasRevision"))
            if (!guard(source, sourceExpected) || !guard(target, targetExpected) || p["expectedAliasRevision"].stringOrNull() != state.aliasRevision) return conflict(p, target.facts)
            val destination = canonical(target.facts.text("key"))
            val delta = p.obj("aliasDelta").mapValues { it.value.jsonPrimitive.content }
            val sourceKeys = (listOf(source.facts.text("key")) + listOfNotNull(source.facts["gtin"].stringOrNull()?.let { "gtin:$it" })).toSet() - destination
            require(delta.isNotEmpty() && delta.keys == sourceKeys && delta.values.all { canonical(it) == destination })
            val aliases = state.aliases + delta
            require(sourceKeys.all { canonical(it, aliases) == destination })
            aliases.keys.forEach { canonical(it, aliases) }
            require(state.rows.values.none { alive(it) && it.id !in setOf(source.id, target.id) && !it.facts.bool("checked") && canonical(it.facts.text("key"), aliases) == destination })
            // Explicit confirmation keeps the destination's exact quantity, including an absent quantity.
            put(source.copy(lifecycle = id, facts = JsonObject(source.facts + p.filterKeys { it == "deletedAt" || it == "updatedAt" })))
            put(target.copy(automaticUnchanged = false))
            state = state.copy(aliases = aliases, aliasRevision = id, tombstones = state.tombstones + (source.id to id), redirects = state.redirects + (source.id to target.id))
            return Outcome.MERGED
        }
        private fun check(): Outcome {
            val row = row()
            if (!alive(row)) return conflict()
            row!!
            require(p.obj("expected").containsKey("check"))
            if (!guard(row, p.obj("expected"))) return conflict()
            val checked = p.getValue("checked").jsonPrimitive.boolean
            require(!checked || p["checkedAt"]?.jsonPrimitive?.longOrNull != null)
            require(checked || p["checkedAt"].stringOrNull() == null)
            put(row.copy(facts = JsonObject(row.facts + mapOf("checked" to JsonPrimitive(checked), "checkedAt" to (p["checkedAt"] ?: JsonNull)) + p.filterKeys { it == "updatedAt" }), revisions = row.revisions + ("check" to id), automaticUnchanged = false))
            return Outcome.APPLIED
        }
        private fun delete(): Outcome {
            val row = row()
            if (!alive(row)) return conflict()
            row!!
            require(p.obj("expected").containsKey("lifecycle"))
            if (!guard(row, p.obj("expected"))) return conflict()
            put(row.copy(lifecycle = id, facts = JsonObject(row.facts + p.filterKeys { it == "updatedAt" || it == "deletedAt" })))
            state = state.copy(tombstones = state.tombstones + (row.id to id))
            return Outcome.APPLIED
        }
        private fun finish(): Outcome {
            val trip = p.text("tripId")
            require(trip !in state.trips && state.closes.values.none { it.tripId == trip })
            val accepted = mutableListOf<String>()
            var stale = false
            for (candidate in p.getValue("rows").jsonArray.map { it.jsonObject }) {
                val requested = candidate.text("id")
                val target = if (requested in state.tombstones) requested else resolve(requested)
                if (target in state.purchases) continue
                val row = state.rows[target]
                val facts = candidate.obj("facts")
                if (!alive(row) || !row!!.facts.bool("checked") || row.lifecycle != candidate["expectedLifecycle"].stringOrNull() ||
                    !guard(row, candidate.obj("expectedFields")) || row.revisions.keys.any { it !in candidate.obj("expectedFields") } ||
                    facts.any { (field, value) -> row.facts[field] != value }) {
                    stale = true
                    conflict(candidate, row?.facts ?: JsonObject(emptyMap()))
                    continue
                }
                require(facts.keys.containsAll(setOf("key", "name", "quantity", "unit", "checkedAt")))
                require(facts["checkedAt"]?.jsonPrimitive?.longOrNull != null)
                val purchase = JsonObject(facts.filterKeys { it != "checkedAt" } + mapOf("id" to JsonPrimitive(target), "tripId" to JsonPrimitive(trip), "boughtAt" to facts.getValue("checkedAt")))
                state = state.copy(purchases = state.purchases + (target to purchase))
                put(row.copy(archived = true, lifecycle = id, facts = JsonObject(row.facts + ("deletedAt" to p.getValue("closedAt")))))
                accepted += target
            }
            if (accepted.isEmpty()) return if (stale) Outcome.CONFLICT else Outcome.NO_OP
            state = state.copy(trips = state.trips + (trip to jsonObject("closedAt" to p.getValue("closedAt"))), closes = state.closes + (id to Close(trip, accepted)))
            return Outcome.APPLIED
        }
        private fun undo(): Outcome {
            val finish = p.text("finishId")
            if (finish in state.cancelledCloses) return Outcome.NO_OP
            val close = state.closes[finish] ?: return Outcome.NO_OP
            require(close.tripId == p.text("tripId"))
            val remove = close.itemIds.filter { state.purchases[it]?.get("tripId").stringOrNull() == close.tripId }.toSet()
            state = state.copy(purchases = state.purchases - remove, trips = state.trips - close.tripId, cancelledCloses = state.cancelledCloses + finish)
            for (row in state.rows.values.toList()) {
                if (row.id in remove && row.lifecycle == finish) put(row.copy(archived = false, lifecycle = id))
                else if (row.enabledBy == finish && row.automaticUnchanged && alive(row)) {
                    put(row.copy(lifecycle = id))
                    state = state.copy(tombstones = state.tombstones + (row.id to id))
                    val base = row.repeatBase
                    if (base != null && state.repeatHeads[base] == row.repeatOccurrence && row.repeatPrevious != null) {
                        state = state.copy(repeatHeads = state.repeatHeads + (base to row.repeatPrevious))
                    }
                }
            }
            return Outcome.APPLIED
        }
        private fun repeat(): Outcome {
            val key = canonical(p.text("key"))
            val ruleRevision = p.text("ruleRevision")
            val enabling = p.text("enablingPurchase")
            val cycle = p["cycle"]?.takeIf { it != JsonNull }?.let { Json.decodeFromJsonElement<RepeatCycle>(it) }
            val base = occurrenceId(intent.listId, key, ruleRevision, enabling)
            val occurrence = occurrenceId(intent.listId, key, ruleRevision, enabling, cycle)
            require(p.text("occurrence") == occurrence)
            val proposed = p.text("proposedId")
            require(proposed !in state.rows && proposed !in state.redirects)
            state.occurrences[occurrence]?.let { winner ->
                state = state.copy(redirects = state.redirects + (proposed to winner))
                return Outcome.NO_OP
            }
            val rule = state.rules[key] ?: return Outcome.REJECTED
            require(rule.text("revision") == ruleRevision)
            val purchase = state.purchases[enabling]
            require(purchase != null || enabling == ruleRevision)
            val latest = state.purchases.values.filter { canonical(it.text("key")) == key }
                .sortedWith(compareByDescending<JsonObject> { it.long("boughtAt") }.thenBy { it.text("id") }).firstOrNull()
            require(latest?.text("id") == purchase?.text("id"))
            if (purchase != null) {
                require(canonical(purchase.text("key")) == key)
                val now = p.long("asOf")
                val boughtAt = purchase.long("boughtAt")
                val days = rule.long("everyDays")
                require(days in 1..Int.MAX_VALUE.toLong() && now >= boughtAt && Math.subtractExact(now, boughtAt) >= days * 86_400_000L)
            }
            require(state.rows.values.none { alive(it) && canonical(it.facts.text("key")) == key })
            if (cycle != null) {
                val winner = state.occurrences[cycle.previous] ?: return Outcome.REJECTED
                val prior = state.rows[winner] ?: return Outcome.REJECTED
                require(prior.repeatBase == base && state.repeatHeads[base] == cycle.previous)
                require(prior.repeatRule == ruleRevision && prior.repeatPurchase == enabling && prior.repeatOccurrence == cycle.previous)
                require(prior.lifecycle == cycle.deletion && state.tombstones[winner] == cycle.deletion && state.seen[cycle.deletion]?.kind == "delete")
                require(firstEffectiveFinishAfter(state, cycle.deletion) == cycle.finish)
            }
            val enabledBy = cycle?.finish ?: state.closes.entries.firstOrNull { enabling in it.value.itemIds && it.key !in state.cancelledCloses }?.key
            put(Row(proposed, jsonObject("key" to JsonPrimitive(key), "name" to JsonPrimitive(p["name"].stringOrNull() ?: purchase?.get("name").stringOrNull() ?: key), "quantity" to JsonNull, "unit" to JsonNull, "checked" to JsonPrimitive(false), "fromSuggestion" to JsonPrimitive(true)) .let { JsonObject(it + p.filterKeys { field -> field in setOf("position", "createdAt", "updatedAt") }) }, mapOf("name" to id, "quantity" to id, "check" to id), id, enabledBy = enabledBy, automaticUnchanged = true, repeatOccurrence = occurrence, repeatRule = ruleRevision, repeatPurchase = enabling, repeatBase = base, repeatPrevious = cycle?.previous))
            state = state.copy(occurrences = state.occurrences + (occurrence to proposed), repeatHeads = state.repeatHeads + (base to occurrence))
            return Outcome.APPLIED
        }
        private fun canonical(key: String, aliases: Map<String, String> = state.aliases): String {
            var value = key
            val visited = mutableSetOf<String>()
            while (aliases.containsKey(value)) {
                require(visited.add(value))
                value = aliases.getValue(value)
            }
            return value
        }
        private fun alias(): Outcome {
            val delta = p.obj("delta").mapValues { it.value.jsonPrimitive.content }
            if (p["expected"].stringOrNull() != state.aliasRevision) {
                val relevant = (delta.keys + delta.values).mapNotNull { key -> state.aliases[key]?.let { key to JsonPrimitive(it) } }.toMap()
                return conflict(p, JsonObject(relevant), "alias_stale")
            }
            val aliases = state.aliases + delta
            aliases.keys.forEach { canonical(it, aliases) }
            val identities = state.rows.values.filter { alive(it) && !it.facts.bool("checked") }.groupBy { canonical(it.facts.text("key"), aliases) }
            if (identities.any { it.value.size > 1 }) return conflict(code = "identity")
            state = state.copy(aliases = aliases, aliasRevision = id)
            return Outcome.APPLIED
        }
        private fun preference(): Outcome {
            val key = p.text("key")
            val current = state.preferences[key] ?: JsonObject(emptyMap())
            if (current["revision"].stringOrNull() != p["expected"].stringOrNull()) return conflict(p.obj("changes"), current)
            val changes = p.obj("changes")
            require(changes.keys.all { it in setOf("category", "repeatEveryDays") })
            changes["repeatEveryDays"]?.takeIf { it != JsonNull }?.let { require(it.jsonPrimitive.int > 0) }
            state = state.copy(preferences = state.preferences + (key to JsonObject(current + changes + ("revision" to JsonPrimitive(id)))))
            if ("repeatEveryDays" in changes) state = state.copy(rules = if (changes["repeatEveryDays"] == JsonNull) state.rules - key else state.rules + (key to jsonObject("revision" to JsonPrimitive(id), "everyDays" to changes.getValue("repeatEveryDays"))))
            return Outcome.APPLIED
        }
        private fun renameList(): Outcome {
            if (p["expected"].stringOrNull() != state.nameRevision) return conflict()
            val name = p.text("name")
            require(name.isNotBlank() && name.length <= 100)
            state = state.copy(name = name, nameRevision = id)
            return Outcome.APPLIED
        }
        private fun import(): Outcome {
            require(p.keys.all { it in setOf("rows", "purchases", "trips") })
            val patches = (p["rows"] as? JsonArray).orEmpty().map { it.jsonObject }
            val purchases = (p["purchases"] as? JsonArray).orEmpty().map { it.jsonObject }
            val trips = (p["trips"] as? JsonArray).orEmpty().map { it.jsonObject }
            require(patches.size + purchases.size + trips.size <= 100)
            require(purchases.map { it.text("id") }.distinct().size == purchases.size)
            require(patches.map { it.text("id") }.distinct().size == patches.size)
            for (patch in patches) {
                val target = patch.text("id")
                val existing = state.rows[target]
                require(existing?.lifecycle == patch["expectedLifecycle"].stringOrNull())
                require(existing == null || guard(existing, patch.obj("expected")))
                val facts = patch.obj("facts")
                require(facts.text("name").isNotBlank())
                validateQuantity(facts)
                validateFacts(facts)
                // An archived incarnation can repeat identically, but a patch cannot revive it.
                require(existing == null || !existing.archived || patch.bool("archived"))
                require(target !in state.tombstones || patch.bool("deleted"))
                put(Row(target, facts, facts.keys.filter { it in REVISION_FIELDS && facts[it] != JsonNull }.associate { revisionField(it) to id },
                    id, patch.bool("archived")))
                if (patch.bool("deleted")) state = state.copy(tombstones = state.tombstones + (target to id))
            }
            for (purchase in purchases) {
                val previous = state.purchases[purchase.text("id")]
                require(previous == null || previous == purchase)
                validateQuantity(purchase)
                validateFacts(purchase)
            }
            for (trip in trips) {
                val previous = state.trips[trip.text("id")]
                val facts = JsonObject(trip - "id")
                require(previous == null || previous == facts)
                state = state.copy(trips = state.trips + (trip.text("id") to facts))
            }
            require(purchases.all { it.text("tripId") in state.trips || state.purchases[it.text("id")] == it })
            state = state.copy(purchases = state.purchases + purchases.associateBy { it.text("id") })
            return Outcome.APPLIED
        }

    }
    companion object {
        private val ROW_FIELDS = setOf("key", "name", "rawText", "quantity", "unit", "note", "gtin", "store", "checked", "checkedAt", "position", "createdAt", "updatedAt", "fromSuggestion", "normalizerVersion", "addedBy", "deletedAt")
        private val REVISION_FIELDS = ROW_FIELDS - setOf("createdAt", "updatedAt", "normalizerVersion", "fromSuggestion", "addedBy", "deletedAt")
        private fun revisionField(field: String): String = when (field) { "unit" -> "quantity"; "checked", "checkedAt" -> "check"; "key", "gtin" -> "name"; else -> field }
        private fun factor(from: String, to: String): Double? {
            val mass = mapOf("GRAM" to 1.0, "KILOGRAM" to 1000.0)
            val volume = mapOf("MILLILITRE" to 1.0, "LITRE" to 1000.0)
            if (from == to && from in mass.keys + volume.keys + setOf("PIECE", "PACK")) return 1.0
            val family = when { from in mass && to in mass -> mass; from in volume && to in volume -> volume; else -> return null }
            return family.getValue(from) / family.getValue(to)
        }
        private fun validateFacts(facts: JsonObject) {
            facts["name"].stringOrNull()?.let { require(it.isNotBlank() && it.length <= 1000) }
            facts["rawText"].stringOrNull()?.let { require(it.length <= 8000) }
            facts["store"].stringOrNull()?.let { require(it.length <= 80) }
            facts["gtin"].stringOrNull()?.let { require(Gtin.parse(it)?.value == it) }
            facts["position"]?.let { require(it.jsonPrimitive.double.isFinite()) }
        }
        private fun validateQuantity(facts: JsonObject) {
            val quantity = facts["quantity"].stringOrNull()
            val unit = facts["unit"].stringOrNull()
            require((quantity == null) == (unit == null))
            if (quantity != null) require(quantity.toDouble().let { it.isFinite() && it >= 0 && it.toString() == quantity } && factor(unit!!, unit) != null)
        }
        fun occurrenceId(list: String, key: String, ruleRevision: String, purchase: String, cycle: RepeatCycle? = null): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out -> for (part in listOf(list, key, ruleRevision, purchase) + (cycle?.let { listOf("cycle/1", it.previous, it.deletion, it.finish) } ?: emptyList())) { val encoded = part.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) } }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun JsonElement?.stringOrNull(): String? = if (this == null || this == JsonNull) null else jsonPrimitive.content
internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
internal fun JsonObject.bool(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: false
internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
internal fun jsonObject(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))

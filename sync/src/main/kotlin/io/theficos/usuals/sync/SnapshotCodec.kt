package io.theficos.usuals.sync

import io.theficos.usuals.model.NORMALIZER_VERSION
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable private data class SnapshotPayload(val schema: Int = 1, val normalizer: Int = NORMALIZER_VERSION, val projection: Projection)
object SnapshotCodec {
    fun encode(projection: Projection): ByteArray {
        validate(projection)
        return Wire.json.encodeToString(SnapshotPayload(projection = projection)).toByteArray().also { if (it.size > 7 * 1024 * 1024 - 288) throw WireTooLarge() }
    }
    fun decode(bytes: ByteArray): Projection {
        val value = Wire.json.decodeFromJsonElement<SnapshotPayload>(Wire.strictJson(bytes, 7 * 1024 * 1024, payload = true))
        if (value.schema != 1 || value.normalizer != NORMALIZER_VERSION) throw UnsupportedWireVersion()
        validate(value.projection)
        return value.projection
    }
    private fun validate(p: Projection) {
        require(p.rows.size <= 20000 && p.purchases.size <= 20000 && p.trips.size <= 20000 && p.aliases.size <= 20000 && p.name.length <= 100)
        fun validId(value: String) = value.isNotBlank() && value.length <= 500
        require(p.rows.all { (id,row) -> validId(id) && row.id == id })
        require(p.purchases.all { (id,facts) -> validId(id) && facts["id"]?.jsonPrimitive?.content == id })
        require(p.trips.all { (id,facts) -> validId(id) && facts["closedAt"]?.jsonPrimitive?.longOrNull != null })
        fun acyclic(edges: Map<String,String>) {
            for (start in edges.keys) {
                require(validId(start) && validId(edges.getValue(start)))
                val visited = mutableSetOf<String>(); var key = start
                while (edges[key] != null) { require(visited.add(key)); key = edges.getValue(key) }
            }
        }
        acyclic(p.aliases); acyclic(p.redirects)
        // Reuse command validation for shopping facts without replacing authoritative revision metadata.
        val reducer = Reducer(NORMALIZER_VERSION); var checked = Projection(); var validationId = 0
        fun check(payload: JsonObject) {
            val result = reducer.apply(checked, Intent("snapshot-validation-${validationId++}", "snapshot-validation", "import", payload, normalizer = NORMALIZER_VERSION))
            require(result.outcome == Outcome.APPLIED || result.outcome == Outcome.NO_OP)
            checked = result.projection
        }
        for (batch in p.trips.map { (id,facts) -> JsonObject(facts + ("id" to JsonPrimitive(id))) }.chunked(100)) check(buildJsonObject { put("trips", JsonArray(batch)) })
        for (batch in p.purchases.values.chunked(100)) check(buildJsonObject { put("purchases", JsonArray(batch)) })
        for (batch in p.rows.values.chunked(100)) check(buildJsonObject {
            put("rows", JsonArray(batch.map { row -> buildJsonObject { put("id", row.id); put("facts", row.facts); put("archived", row.archived); put("deleted", row.id in p.tombstones) } }))
        })
        require(p.preferences.size <= 20000 && p.rules.size <= 20000)
        p.rules.values.forEach { require(it["everyDays"]?.jsonPrimitive?.intOrNull?.let { n -> n > 0 } == true) }
        require(p.seen.all { (id,intent) -> id == intent.id && intent.schema == 1 && intent.normalizer == NORMALIZER_VERSION })
        require(p.closes.all { (id,close) -> validId(id) && validId(close.tripId) && close.itemIds.size <= 20000 })
    }
}

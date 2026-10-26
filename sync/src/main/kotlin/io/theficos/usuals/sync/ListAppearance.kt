package io.theficos.usuals.sync

import io.theficos.usuals.model.ListColor
import kotlinx.serialization.json.*

/** Namespaced metadata keeps the existing projection envelope readable by older clients. */
object ListAppearance {
    const val KEY = "usuals:list-appearance"
    fun color(projection: Projection): ListColor? = ListColor.fromId(projection.preferences[KEY]?.get("color")?.jsonPrimitive?.contentOrNull)
    fun revision(projection: Projection): String? = projection.preferences[KEY]?.get("revision")?.jsonPrimitive?.contentOrNull
    fun withColor(projection: Projection, color: ListColor?, revision: String? = null): Projection = projection.copy(
        preferences = projection.preferences + (KEY to buildJsonObject {
            put("color", color?.id?.let(::JsonPrimitive) ?: JsonNull)
            revision?.let { put("revision", it) }
        }))
    fun validate(projection: Projection) {
        projection.preferences[KEY]?.let { metadata ->
            require(metadata.keys.all { it in setOf("color", "revision") })
            val revision = metadata["revision"]
            require(revision == null || revision == JsonNull || (revision is JsonPrimitive && revision.isString && revision.content.isNotBlank() && revision.content.length <= 500))
            require("color" in metadata)
            val color = metadata["color"]
            require(color == JsonNull || (color is JsonPrimitive && color.isString && ListColor.fromId(color.content) != null))
        }
    }
}

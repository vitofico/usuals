package io.theficos.usuals.sync

import kotlinx.serialization.json.*

/** Namespaced metadata preserves deletion across resends without changing the projection envelope. */
object HistoryDeletion {
    const val KEY = "usuals:deleted-trips"
    fun contains(projection: Projection, trip: String) = projection.preferences[KEY]?.containsKey(trip) == true
    fun record(projection: Projection, trip: String, revision: String) = projection.copy(preferences =
        projection.preferences + (KEY to JsonObject(projection.preferences[KEY].orEmpty() + (trip to JsonPrimitive(revision)))))
    fun validate(projection: Projection) {
        projection.preferences[KEY]?.let { deleted ->
            require(deleted.size <= 20000)
            require(deleted.all { (trip, revision) -> trip.isNotBlank() && trip.length <= 500 && trip !in projection.trips &&
                revision is JsonPrimitive && revision.isString && revision.content.isNotBlank() && revision.content.length <= 500 })
            require(projection.purchases.values.none { it["tripId"]?.jsonPrimitive?.content in deleted })
        }
    }
}

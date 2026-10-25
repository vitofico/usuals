package io.theficos.usuals.smart

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class ReplayArchiveTest {
    @Test fun receipt_archives_use_only_the_selected_lists_history() {
        val root = Json.parseToJsonElement("""{
            "formatVersion":4,"selectedListId":"home","lists":[{"id":"home"},{"id":"work"}],
            "trips":[{"id":"shop","closedAt":10,"listId":"home"},{"id":"shop","closedAt":20,"listId":"work"}],
            "purchases":[{"id":"milk","tripId":"shop","key":"milk","name":"milk","boughtAt":10,"listId":"home"},
                {"id":"coffee","tripId":"shop","key":"coffee","name":"coffee","boughtAt":20,"listId":"work"}],
            "aliases":[],"receipts":[{"jpeg":"outside ranking"}]
        }""").jsonObject
        for (version in 2..4) {
            val history = historyFromArchive(JsonObject(root + ("formatVersion" to JsonPrimitive(version))))
            assertThat(history.trips.single().closedAt).isEqualTo(10)
            assertThat(history.purchases.single().name).isEqualTo("milk")
        }
    }
    @Test fun original_unscoped_archives_still_work() {
        val history = historyFromArchive(Json.parseToJsonElement("""{"formatVersion":1,"trips":[{"id":"shop","closedAt":10}],"purchases":[],"aliases":[]}""").jsonObject)
        assertThat(history.trips.single().id).isEqualTo("shop")
    }
}

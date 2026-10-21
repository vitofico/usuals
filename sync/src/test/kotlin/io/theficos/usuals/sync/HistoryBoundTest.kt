package io.theficos.usuals.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test
import java.util.UUID

/** What a list keeps, docs/sync/lists-v2.md: a bounded history, so a list in use for years stays well under the cap. */
class HistoryBoundTest {
    private val reducer = Reducer()
    private val horizon = 1000
    private var next = 0L

    @Test fun aBoughtItemsRowLeavesWithItsFinishButItsPurchaseKeepsItOffTheList() {
        val milk = capture("milk", "Milk")
        var state = shop(reducer.apply(Projection(), milk).projection, "milk", at = DAY)
        assertThat(state.rows.getValue("milk").archived).isTrue()
        assertThat(state.closes).hasSize(1)

        state = toggle(add(state, "tea", "Tea"), "tea", horizon)
        assertThat(state.rows.keys).containsExactly("tea")
        assertThat(state.closes).isEmpty()
        assertThat(state.purchases.keys).containsExactly("milk")

        val resent = reducer.apply(state, milk)
        assertThat(resent.outcome).isEqualTo(Outcome.REJECTED)
        assertThat(resent.projection.rows.keys).containsExactly("tea")
    }

    @Test fun aFinishCanBeUndoneOnlyWithinTheHorizon() {
        val bought = shop(add(add(Projection(), "milk", "Milk"), "tea", "Tea"), "milk", at = DAY)
        val finish = bought.closes.keys.single()
        val trip = bought.closes.getValue(finish).tripId

        val recent = undo(toggle(bought, "tea", horizon - 2), finish, trip)
        assertThat(recent.outcome).isEqualTo(Outcome.APPLIED)
        assertThat(recent.projection.rows.getValue("milk").archived).isFalse()
        assertThat(recent.projection.purchases).isEmpty()

        val old = toggle(bought, "tea", horizon)
        val late = undo(old, finish, trip)
        assertThat(late.outcome).isEqualTo(Outcome.NO_OP)
        assertThat(late.projection.purchases.keys).containsExactly("milk")
        assertThat(late.projection.rows.keys).containsExactly("tea")
    }

    @Test fun aDeletedItemsRowLeavesWithItsDeletionButItsTombstoneStays() {
        val bread = capture("bread", "Bread")
        var state = add(reducer.apply(Projection(), bread).projection, "tea", "Tea")
        state = apply(state, "delete", buildJsonObject {
            put("target", "bread"); put("expected", buildJsonObject { put("lifecycle", state.rows.getValue("bread").lifecycle) })
        })
        state = toggle(state, "tea", horizon)
        assertThat(state.rows.keys).containsExactly("tea")
        assertThat(state.tombstones.keys).containsExactly("bread")
        assertThat(reducer.apply(state, bread).outcome).isEqualTo(Outcome.REJECTED)
    }

    @Test fun conflictsAndRejectionsAreForgottenWithTheirIntent() {
        var state = add(Projection(), "tea", "Tea")
        state = reducer.apply(state, Intent(id(), LIST, "check", buildJsonObject {
            put("target", "tea"); put("expected", buildJsonObject { put("check", "stale") }); put("checked", true); put("checkedAt", 1)
        })).also { assertThat(it.outcome).isEqualTo(Outcome.CONFLICT) }.projection
        state = reducer.apply(state, Intent(id(), LIST, "unknown", JsonObject(emptyMap())))
            .also { assertThat(it.outcome).isEqualTo(Outcome.REJECTED) }.projection
        assertThat(state.conflicts).hasSize(1)
        assertThat(state.rejected).hasSize(1)

        state = toggle(state, "tea", horizon)
        assertThat(state.conflicts).isEmpty()
        assertThat(state.rejected).isEmpty()
    }

    @Test fun aRepeatResentAfterItsSuggestionWasBoughtAndDroppedChangesNothing() {
        var state = add(Projection(), "tea", "Tea")
        state = apply(state, "preference", buildJsonObject { put("key", "milk"); put("expected", JsonNull); put("changes", buildJsonObject { put("repeatEveryDays", 1) }) })
        state = shop(add(state, "milk", "Milk"), "milk", at = DAY)
        val rule = state.rules.getValue("milk").getValue("revision").jsonPrimitive.content
        val (occurrence, cycle) = requireNotNull(nextRepeatCycle(state, LIST, "milk", rule, "milk"))
        val suggestion = Intent(id(), LIST, "repeat", buildJsonObject {
            put("proposedId", "suggested"); put("key", "milk"); put("ruleRevision", rule); put("enablingPurchase", "milk")
            put("asOf", 3 * DAY); put("occurrence", occurrence)
            if (cycle != null) put("cycle", Wire.json.encodeToJsonElement(RepeatCycle.serializer(), cycle))
        })
        state = reducer.apply(state, suggestion).also { assertThat(it.outcome).isEqualTo(Outcome.APPLIED) }.projection
        state = toggle(shop(state, "suggested", at = 4 * DAY), "tea", horizon)
        assertThat(state.rows.keys).containsExactly("tea")

        val resent = reducer.apply(state, suggestion)
        assertThat(resent.outcome).isEqualTo(Outcome.NO_OP)
        assertThat(resent.projection.redirects).isEmpty()
        SnapshotCodec.encode(resent.projection)
    }

    @Test fun aPurchaseRecordsOnlyTheFactsPhonesKeep() {
        val state = shop(apply(Projection(), "capture", buildJsonObject {
            put("proposedId", "milk"); put("key", "milk"); put("name", "Milk"); put("rawText", "2 l milk"); put("quantity", "2.0"); put("unit", "LITRE")
            put("note", "semi-skimmed"); put("store", "Market"); put("position", 3.0); put("createdAt", 1); put("updatedAt", 1)
            put("gtin", JsonNull); put("fromSuggestion", false); put("normalizerVersion", 1); put("addedBy", "someone")
        }), "milk", at = DAY)
        val purchase = state.purchases.getValue("milk")
        assertThat(purchase.keys).containsExactly("id", "tripId", "boughtAt", "key", "name", "quantity", "unit", "gtin", "fromSuggestion", "normalizerVersion")
        assertThat(purchase.getValue("quantity").jsonPrimitive.content).isEqualTo("2.0")
        assertThat(state.rows.getValue("milk").facts.getValue("note").jsonPrimitive.content).isEqualTo("semi-skimmed")
    }

    @Test fun aListKeepsItsLatestFiveThousandPurchasesAndEveryTrip() {
        var state = Projection()
        for (trip in 0 until 52) state = apply(state, "import", buildJsonObject {
            put("trips", buildJsonArray { add(buildJsonObject { put("id", "trip-$trip"); put("closedAt", trip * DAY + 99) }) })
            put("purchases", buildJsonArray { repeat(99) { item -> add(buildJsonObject {
                put("id", "bought-$trip-$item"); put("tripId", "trip-$trip"); put("key", "item $item"); put("name", "Item $item")
                put("quantity", JsonNull); put("unit", JsonNull); put("boughtAt", trip * DAY + item)
            }) } })
        })
        assertThat(state.purchases).hasSize(5000)
        assertThat(state.trips).hasSize(52)
        // 52 x 99 = 5,148: the oldest trip goes whole and the next one loses its 49 earliest purchases.
        assertThat(state.purchases.keys.none { it.startsWith("bought-0-") }).isTrue()
        assertThat(state.purchases.keys.filter { it.startsWith("bought-1-") }).hasSize(50)
        assertThat(state.purchases).containsKey("bought-1-49")
        assertThat(state.purchases).doesNotContainKey("bought-1-48")
    }

    @Test fun aBusyWeeklyShopStaysWellUnderTheCapForTenYears() {
        val shop = WeeklyShop()
        var state = Projection(name = "Groceries")
        repeat(52 * 10) { week -> state = shop.week(state, week) }
        assertThat(state.purchases).hasSize(5000)
        assertThat(state.rows.size).isLessThan(horizon)
        val stored = SnapshotCodec.encode(state)
        assertThat(stored.size).isLessThan(3 * 1024 * 1024)
        // As a phone reads a list from the server, with the strict decoder's limits on keys and nodes.
        val body = Wire.json.encodeToString(Projection.serializer(), state).toByteArray()
        assertThat(Wire.json.decodeFromJsonElement(Projection.serializer(), Wire.strictJson(body, 10 * 1024 * 1024, payload = true))).isEqualTo(state)
    }

    /** A household's weekly shop as the phone sends it: 25 items captured, two mistakes deleted, the rest ticked and finished. */
    private inner class WeeklyShop {
        private val member = uuid()
        private val names = listOf("Semi-skimmed milk", "Wholemeal bread", "Free range eggs", "Bananas", "Cheddar cheese", "Greek yoghurt",
            "Chicken thighs", "Basmati rice", "Penne pasta", "Chopped tomatoes", "Olive oil", "Red onions", "Garlic", "Carrots", "Broccoli",
            "Apples", "Oranges", "Butter", "Coffee beans", "Breakfast tea", "Orange juice", "Toilet paper", "Washing up liquid",
            "Dishwasher tablets", "Frozen peas", "Potatoes", "Spinach", "Cherry tomatoes", "Cucumber", "Lettuce", "Mince beef",
            "Salmon fillets", "Tuna tins", "Peanut butter", "Strawberry jam", "Oats", "Cornflakes", "Sparkling water", "Crisps",
            "Dark chocolate", "Ham slices", "Mozzarella", "Parmesan", "Lemons", "Avocados", "Mushrooms", "Peppers", "Courgettes",
            "Kitchen roll", "Bin bags")

        fun week(initial: Projection, week: Int): Projection {
            var state = initial
            val now = 1_700_000_000_000L + week * 7 * DAY
            val items = (0 until 25).map { names[(week * 7 + it * 3) % names.size] }.distinct().mapIndexed { index, name ->
                uuid().also { row -> state = step(state, "capture", JsonObject(facts(name, now + index, index.toDouble()) + ("proposedId" to JsonPrimitive(row)))) }
            }
            repeat(2) { index ->
                val row = uuid()
                state = step(state, "capture", JsonObject(facts("Mistake $week $index", now, 100.0 + index) + ("proposedId" to JsonPrimitive(row))))
                state = step(state, "delete", buildJsonObject {
                    put("target", row); put("expected", buildJsonObject { put("lifecycle", state.rows.getValue(row).lifecycle) })
                    put("updatedAt", now); put("deletedAt", now)
                })
            }
            val at = now + 3_600_000
            for (row in items) state = step(state, "check", buildJsonObject {
                put("target", row); put("expected", buildJsonObject { put("check", state.rows.getValue(row).revisions["check"]); put("lifecycle", state.rows.getValue(row).lifecycle) })
                put("checked", true); put("checkedAt", at); put("updatedAt", at)
            })
            return step(state, "finish", buildJsonObject {
                put("tripId", uuid()); put("closedAt", at + 1)
                put("rows", buildJsonArray { for (id in items) { val row = state.rows.getValue(id); add(buildJsonObject {
                    put("id", id); put("facts", row.facts); put("expectedLifecycle", row.lifecycle)
                    put("expectedFields", JsonObject(row.revisions.mapValues { JsonPrimitive(it.value) }))
                }) } })
            })
        }
        private fun step(state: Projection, kind: String, payload: JsonObject): Projection {
            val result = reducer.apply(state, Intent(uuid(), LIST, kind, payload))
            assertThat(result.outcome).isEqualTo(Outcome.APPLIED)
            return result.projection
        }
        private fun facts(name: String, now: Long, position: Double) = buildJsonObject {
            put("key", name.lowercase()); put("name", name); put("rawText", "2 $name"); put("quantity", "2.0"); put("unit", "PIECE")
            put("note", JsonNull); put("gtin", JsonNull); put("checked", false); put("checkedAt", JsonNull); put("fromSuggestion", false)
            put("store", JsonNull); put("position", position); put("addedBy", member); put("createdAt", now); put("updatedAt", now)
            put("deletedAt", JsonNull); put("normalizerVersion", 1)
        }
    }

    private fun uuid() = UUID(0, next++).toString()
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
    private fun undo(state: Projection, finish: String, trip: String) =
        reducer.apply(state, Intent(id(), LIST, "undo", buildJsonObject { put("finishId", finish); put("tripId", trip) }))
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
    private companion object {
        const val LIST = "home"
        const val DAY = 86_400_000L
    }
}

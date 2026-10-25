package io.theficos.usuals.smart

import io.theficos.usuals.model.*
import kotlinx.serialization.json.*
import java.io.File

/** Developer harness for a local Usuals export. Output contains aggregate counts only. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: replay <shopping-archive.json>" }
    val root = Json.parseToJsonElement(File(args.single()).readText()).jsonObject
    val history = historyFromArchive(root)
    val ranker = HistoryRanker()
    val report = ReplayHarness(SuggestionEngine(RepeatPlanner(), IntervalModel(), ranker), ranker).evaluate(history)
    println("trips=${report.trips} slots=${report.candidate.slots} candidate=${report.candidate.hits} lastTrip=${report.lastTrip.hits} staples=${report.staples.hits} beatsBoth=${report.beatsBoth}")
}

/** Photos and personal settings are outside the ranking input; lists never mix. */
internal fun historyFromArchive(root: JsonObject): HistorySnapshot {
    val version = root.getValue("formatVersion").jsonPrimitive.int
    require(version in 1..4)
    val selected = if (version == 1) null else root["selectedListId"]?.jsonPrimitive?.contentOrNull
        ?: root.getValue("lists").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content
    if (version > 1) require(root.getValue("lists").jsonArray.any { it.jsonObject.getValue("id").jsonPrimitive.content == selected })
    fun rows(key: String) = root[key]?.jsonArray.orEmpty().map { it.jsonObject }
        .filter { version == 1 || it["listId"]?.jsonPrimitive?.contentOrNull == selected }
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    return HistorySnapshot(
        trips = rows("trips").map { row -> Trip(row.text("id"), row.getValue("closedAt").jsonPrimitive.long) },
        purchases = rows("purchases").map { row ->
            Purchase(ItemId(row.text("id")), row.text("tripId"), row.text("key"), row.text("name"),
                gtin = row["gtin"]?.jsonPrimitive?.contentOrNull?.let(Gtin::parse),
                boughtAt = row.getValue("boughtAt").jsonPrimitive.long,
                normalizerVersion = row["normalizerVersion"]?.jsonPrimitive?.int ?: 1)
        },
        aliases = rows("aliases").map { row -> ItemAlias(row.text("fromKey"), row.text("toKey")) },
    )
}

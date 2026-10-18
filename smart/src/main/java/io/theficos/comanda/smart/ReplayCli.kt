package io.theficos.comanda.smart

import io.theficos.comanda.model.*
import kotlinx.serialization.json.*
import java.io.File

/** Developer harness for a local Comanda export. Output contains aggregate counts only. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: replay <shopping-archive.json>" }
    val root = Json.parseToJsonElement(File(args.single()).readText()).jsonObject
    require(root.getValue("formatVersion").jsonPrimitive.int == 1)
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    val history = HistorySnapshot(
        trips = root.getValue("trips").jsonArray.map { it.jsonObject.let { row -> Trip(row.text("id"), row.getValue("closedAt").jsonPrimitive.long) } },
        purchases = root.getValue("purchases").jsonArray.map { it.jsonObject.let { row ->
            Purchase(ItemId(row.text("id")), row.text("tripId"), row.text("key"), row.text("name"),
                gtin = row["gtin"]?.jsonPrimitive?.contentOrNull?.let(Gtin::parse),
                boughtAt = row.getValue("boughtAt").jsonPrimitive.long,
                normalizerVersion = row["normalizerVersion"]?.jsonPrimitive?.int ?: 1)
        } },
        aliases = root["aliases"]?.jsonArray.orEmpty().map { it.jsonObject.let { row -> ItemAlias(row.text("fromKey"), row.text("toKey")) } },
    )
    val ranker = HistoryRanker()
    val report = ReplayHarness(SuggestionEngine(RepeatPlanner(), IntervalModel(), ranker), ranker).evaluate(history)
    println("trips=${report.trips} slots=${report.candidate.slots} candidate=${report.candidate.hits} lastTrip=${report.lastTrip.hits} staples=${report.staples.hits} beatsBoth=${report.beatsBoth}")
}

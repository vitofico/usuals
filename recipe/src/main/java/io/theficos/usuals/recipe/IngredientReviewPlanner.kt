package io.theficos.usuals.recipe

import io.theficos.usuals.model.*
import io.theficos.usuals.parse.*

enum class IngredientReason { NONE, ON_LIST, PANTRY, RECENT }
data class IngredientReview(val key: String, val original: String, val item: ParsedItem, val reason: IngredientReason, val checked: Boolean = false)

class IngredientReviewPlanner(private val parser: IngredientLineParser, pantry: List<String>) {
    private val pantryKeys = pantry.map(Normalizer::normalize).toSet()
    /** Lines at [checked] indexes were ticked in the source; a merged row is ticked only when all its lines were. */
    fun plan(lines: List<String>, history: HistorySnapshot, active: List<Item>, now: Long, checked: Set<Int> = emptySet()): List<IngredientReview> =
        planned(lines.mapIndexedNotNull { index, line -> parser.parse(line)?.let { Line(line, it, index in checked) } }, history, active, now)

    fun planParsed(lines: List<Pair<String, ParsedItem>>, history: HistorySnapshot, active: List<Item>, now: Long): List<IngredientReview> =
        planned(lines.map { Line(it.first, it.second, false) }, history, active, now)

    private data class Line(val source: String, val item: ParsedItem, val checked: Boolean)

    private fun planned(lines: List<Line>, history: HistorySnapshot, active: List<Item>, now: Long): List<IngredientReview> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val present = active.map { ItemIdentity.key(it, aliases) }.toSet()
        val recent = history.purchases.filter { it.boughtAt in (now - 7 * 86_400_000L)..now }
            .map { ItemIdentity.purchaseKey(it, aliases) }.toSet()
        return lines.groupBy { ItemIdentity.resolve(Normalizer.normalize(it.item.name), aliases) }.mapNotNull { (key, group) ->
            if (key.isBlank()) return@mapNotNull null
            val first = group.first().item
            val quantities = group.map { it.item.quantity }
            val combined = if (quantities.all { it != null } && quantities.map { it!!.unit }.distinct().size == 1) {
                first.quantity?.copy(amount = quantities.sumOf { it!!.amount })
            } else if (group.size == 1) first.quantity else null
            IngredientReview(key, group.map { it.source }.distinct().joinToString("; "),
                first.copy(quantity = combined, note = (group.mapNotNull { it.item.note } +
                    if (group.size > 1 && combined == null) group.map { it.source } else emptyList())
                    .distinct().joinToString("; ").ifBlank { null }),
                when { key in present -> IngredientReason.ON_LIST; pantryKeys.any { " $key ".contains(" $it ") } -> IngredientReason.PANTRY
                    key in recent -> IngredientReason.RECENT; else -> IngredientReason.NONE }, group.all { it.checked })
        }
    }
}

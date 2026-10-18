package io.theficos.usuals.recipe

import io.theficos.usuals.model.*
import io.theficos.usuals.parse.*

enum class IngredientReason { NONE, ON_LIST, PANTRY, RECENT }
data class IngredientReview(val key: String, val original: String, val item: ParsedItem, val reason: IngredientReason)

class IngredientReviewPlanner(private val parser: IngredientLineParser, pantry: List<String>) {
    private val pantryKeys = pantry.map(Normalizer::normalize).toSet()
    fun plan(lines: List<String>, history: HistorySnapshot, active: List<Item>, now: Long): List<IngredientReview> =
        planParsed(lines.mapNotNull { line -> parser.parse(line)?.let { line to it } }, history, active, now)

    fun planParsed(lines: List<Pair<String, ParsedItem>>, history: HistorySnapshot, active: List<Item>, now: Long): List<IngredientReview> {
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val present = active.map { ItemIdentity.key(it, aliases) }.toSet()
        val recent = history.purchases.filter { it.boughtAt in (now - 7 * 86_400_000L)..now }
            .map { ItemIdentity.purchaseKey(it, aliases) }.toSet()
        return lines.groupBy { ItemIdentity.resolve(Normalizer.normalize(it.second.name), aliases) }.mapNotNull { (key, group) ->
            if (key.isBlank()) return@mapNotNull null
            val first = group.first().second
            val quantities = group.map { it.second.quantity }
            val combined = if (quantities.all { it != null } && quantities.map { it!!.unit }.distinct().size == 1) {
                first.quantity?.copy(amount = quantities.sumOf { it!!.amount })
            } else if (group.size == 1) first.quantity else null
            IngredientReview(key, group.map { it.first }.distinct().joinToString("; "),
                first.copy(quantity = combined, note = (group.mapNotNull { it.second.note } +
                    if (group.size > 1 && combined == null) group.map { it.first } else emptyList())
                    .distinct().joinToString("; ").ifBlank { null }),
                when { key in present -> IngredientReason.ON_LIST; pantryKeys.any { " $key ".contains(" $it ") } -> IngredientReason.PANTRY
                    key in recent -> IngredientReason.RECENT; else -> IngredientReason.NONE })
        }
    }
}

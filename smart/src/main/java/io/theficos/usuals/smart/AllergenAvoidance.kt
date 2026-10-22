package io.theficos.usuals.smart

import java.util.Locale

/**
 * Which of the allergens a person avoids a product's Open Food Facts record
 * mentions. [contains] comes from the allergen list, [mayContain] from traces,
 * both as tags in the product's own order.
 *
 * There is deliberately no "safe" answer here. An empty result says only that
 * the record does not list them, and a record that never stated its allergens
 * gives [allergensStated] false so the screen can say so instead of staying
 * quiet in a way that reads as reassurance.
 */
data class AllergenMatch(
    val contains: List<String> = emptyList(),
    val mayContain: List<String> = emptyList(),
    val allergensStated: Boolean = false,
) {
    /** True when there is something to warn about. */
    val flagged: Boolean get() = contains.isNotEmpty() || mayContain.isNotEmpty()
}

object AllergenAvoidance {

    /**
     * Matches by tag (`en:milk`), never by name, so the reader's language
     * cannot change the answer.
     *
     * Traces are matched even when the allergen list was never filled in: a
     * stated "may contain" is a fact on its own, and dropping it because a
     * different field is empty would hide the one warning the record does give.
     * A tag already listed as contained is not repeated as a trace.
     */
    fun match(allergens: List<String>?, traces: List<String>?, avoid: Set<String>): AllergenMatch {
        if (avoid.isEmpty()) return AllergenMatch(allergensStated = allergens != null)
        val wanted = avoid.map(::normal).toSet()
        val contains = allergens.orEmpty().map(::normal).filter { it in wanted }.distinct()
        val traced = traces.orEmpty().map(::normal).filter { it in wanted && it !in contains }.distinct()
        return AllergenMatch(contains, traced, allergensStated = allergens != null)
    }

    private fun normal(tag: String): String = tag.trim().lowercase(Locale.ROOT)
}

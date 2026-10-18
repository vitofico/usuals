package io.theficos.comanda.parse

import io.theficos.comanda.model.Quantity

/**
 * One item pulled out of something a person said or typed.
 *
 * [name] is display text, kept in the person's own words and casing, accents
 * and all. The cache key is derived from it later through [Normalizer], never
 * stored here.
 */
data class ParsedItem(
    val name: String,
    val quantity: Quantity? = null,
    val note: String? = null,
)

/**
 * Turns an utterance into items.
 *
 * Two implementations are foreseen and they sit at different costs: the local
 * rule-based one that handles the common shapes for free, and an LLM-backed one
 * for a dictated paragraph or a pasted recipe. The capture path tries the cheap
 * one first and only escalates when it comes back with nothing useful.
 */
interface UtteranceParser {
    fun parse(utterance: String): List<ParsedItem>
}

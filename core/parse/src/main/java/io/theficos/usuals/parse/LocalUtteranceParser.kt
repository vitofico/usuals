package io.theficos.usuals.parse

import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity

/**
 * Rules, no network, no model, no tokens.
 *
 * This is the parser that runs on every capture, standing at an open fridge
 * with wet hands, and the reason it exists is that the common shapes of a
 * grocery utterance are boringly regular: an optional verb, an optional
 * quantity, a name, and sometimes a note. "Añade dos litros de leche" does not
 * need a language model, and paying for one would make the fast path slow.
 *
 * What it handles, deliberately and no more:
 *
 * - Leading verbs and politeness: "añade", "apunta", "hay que comprar", "add".
 * - Quantities with units, written or spoken: "dos litros de", "medio kilo de",
 *   "500 g", "1,5 l", "500g", "half a litre of".
 * - Counts, in digits or words up to twenty, plus "medio", "docena" and "par".
 * - Several items in one breath, separated by a comma, "y" or "and".
 * - A note after "para", "for", or in parentheses.
 *
 * Anything messier is somebody else's job. A dictated paragraph or a pasted
 * recipe goes to the LLM parser instead, and this one is allowed to return
 * fewer items rather than wrong ones.
 *
 * Two contracts it will not break: it never throws, whatever arrives, and it
 * never emits an item with a blank name. A capture path that crashes on a
 * strange speech-recogniser result loses the thing the person just said.
 */
class LocalUtteranceParser(
    packs: List<InputLanguage> = InputLanguages.available,
) : UtteranceParser {
    private val grammar = InputGrammar(packs)

    override fun parse(utterance: String): List<ParsedItem> {
        if (utterance.isBlank()) return emptyList()
        return grammar.segmentSeparator.split(utterance).mapNotNull { parseSegment(it) }
    }

    internal fun parseIngredientLine(line: String): ParsedItem? = parseSegment(line)

    private fun parseSegment(segment: String): ParsedItem? {
        val notes = mutableListOf<String>()
        var text = takeParentheticals(segment.trim(), notes)
        text = takeTrailingNote(text, notes)

        var words = text.split(WHITESPACE).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        words = words.drop(leadingNoise(words.map(::foldToken)))
        if (words.isEmpty()) return null

        var quantity: Quantity? = null
        val leading = leadingQuantity(words.map(::foldToken))
        if (leading != null) {
            quantity = leading.quantity
            words = words.drop(leading.consumed)
            // "dos litros DE leche": the connector belongs to the quantity
            // phrase, not to the name.
            if (words.isNotEmpty() && foldToken(words.first()) in grammar.connectors) {
                words = words.drop(1)
            }
        } else {
            val trailing = trailingQuantity(words.map(::foldToken))
            if (trailing != null && words.size > trailing.consumed) {
                quantity = trailing.quantity
                words = words.dropLast(trailing.consumed)
            }
        }

        val name = cleanName(words)
        if (!isUsableName(name)) return null

        return ParsedItem(
            name = name,
            quantity = quantity,
            note = notes.filter { it.isNotBlank() }.joinToString(", ").ifBlank { null },
        )
    }

    /** A quantity and how many tokens of the segment it accounted for. */
    private class Taken(val quantity: Quantity, val consumed: Int)

    /**
     * Number of leading tokens that are verb or politeness noise. Stops at the
     * first token that could be part of a name, so "compra el pan" loses two
     * tokens and "un pan" loses none: "un" is a count, not an article.
     */
    private fun leadingNoise(folded: List<String>): Int {
        var i = 0
        while (i < folded.size && folded[i] in grammar.noise) i++
        return i
    }

    /**
     * Reads a quantity off the front of the segment.
     *
     * The shapes, in the order they are tried: a number glued to its unit
     * ("500g"), a number followed by a unit or a multiplier ("dos litros", "una
     * docena"), a bare number ("tres manzanas"), and a bare unit standing in
     * for one of it ("pack de cervezas"). A bare unit only counts when a
     * connector follows, otherwise an item called "bolsas" would parse as a
     * quantity with nothing left to buy.
     */
    private fun leadingQuantity(folded: List<String>): Taken? {
        if (folded.isEmpty()) return null

        gluedQuantity(folded.first())?.let { return Taken(it, 1) }

        val amount = numberValue(folded.first())
        var cursor = if (amount != null) 1 else 0
        if (amount == null && folded.first() !in grammar.multipliers && folded.first() !in grammar.units) return null

        // "half a litre": English slips an article between the number and the
        // unit, and Spanish does not.
        if (amount != null &&
            cursor < folded.size &&
            folded[cursor] in grammar.articles &&
            cursor + 1 < folded.size &&
            (folded[cursor + 1] in grammar.units || folded[cursor + 1] in grammar.multipliers)
        ) {
            cursor++
        }

        if (cursor < folded.size) {
            val multiplier = grammar.multipliers[folded[cursor]]
            if (multiplier != null) {
                return Taken(Quantity((amount ?: 1.0) * multiplier, MeasureUnit.PIECE), cursor + 1)
            }
            val unit = grammar.units[folded[cursor]]
            if (unit != null) {
                val followedByConnector = cursor + 1 < folded.size && folded[cursor + 1] in grammar.connectors
                if (amount == null && !followedByConnector) return null
                return Taken(Quantity(amount ?: 1.0, unit), cursor + 1)
            }
        }

        return if (amount == null) null else Taken(Quantity(amount, MeasureUnit.PIECE), cursor)
    }

    /**
     * Reads a quantity off the end instead, for the shapes people type rather
     * than speak: "leche x2", "coca cola 2l", "leche 2 l". Only consulted when
     * nothing was found at the front, and only when a name would survive it.
     */
    private fun trailingQuantity(folded: List<String>): Taken? {
        if (folded.isEmpty()) return null
        val last = folded.last()

        val times = TIMES_COUNT.matchEntire(last)
        if (times != null) {
            val count = times.groupValues[1].toIntOrNull()
            if (count != null && count > 0) return Taken(Quantity.pieces(count), 1)
        }

        gluedQuantity(last)?.let { return Taken(it, 1) }

        if (folded.size >= 2) {
            val unit = grammar.units[last]
            val amount = numberValue(folded[folded.size - 2])
            if (unit != null && amount != null) return Taken(Quantity(amount, unit), 2)
        }
        return null
    }

    /** "500g", "1,5l", "750ml": a number with its unit written against it. */
    private fun gluedQuantity(token: String): Quantity? {
        val match = GLUED_QUANTITY.matchEntire(token) ?: return null
        val amount = parseDecimal(match.groupValues[1]) ?: return null
        val unit = grammar.units[match.groupValues[2]] ?: return null
        return Quantity(amount, unit)
    }

    private fun numberValue(token: String): Double? = grammar.numbers[token] ?: parseDecimal(token)

    /** Digits with either separator, since a phone keyboard and a dictation disagree. */
    private fun parseDecimal(token: String): Double? {
        if (!DECIMAL.matches(token)) return null
        return token.replace(',', '.').toDoubleOrNull()
    }

    private fun takeParentheticals(text: String, notes: MutableList<String>): String {
        if ('(' !in text) return text
        return PARENTHETICAL.replace(text) { match ->
            val inner = match.groupValues[1].trim()
            if (inner.isNotEmpty()) notes += inner
            " "
        }.trim()
    }

    /**
     * Splits a trailing "para ..." or "for ..." off as a note, keeping the
     * marker word: "leche para el café" is an item called leche with the note
     * "para el café", because the note is read back as a sentence.
     */
    private fun takeTrailingNote(text: String, notes: MutableList<String>): String {
        for (marker in grammar.noteMarkers) {
            val index = text.indexOf(marker, ignoreCase = true)
            if (index <= 0) continue
            val head = text.substring(0, index).trim()
            if (head.isBlank()) continue
            notes += text.substring(index).trim()
            return head
        }
        return text
    }

    private fun cleanName(words: List<String>): String =
        words.joinToString(" ").trim().trim { it in EDGE_PUNCTUATION }.trim()

    /**
     * A name has to be something a person could buy. A segment that reduces to
     * punctuation, a stray digit, or nothing but connectors and conjunctions is
     * a hesitation rather than an item, and it gets dropped instead of landing
     * on the list as a row called "de".
     */
    private fun isUsableName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.none { it.isLetter() }) return false
        return name.split(WHITESPACE).any { foldToken(it) !in grammar.nonNames }
    }
}

private val WHITESPACE = Regex("\\s+")
private val PARENTHETICAL = Regex("\\(([^)]*)\\)")
private val DECIMAL = Regex("\\d+(?:[.,]\\d+)?")
private val GLUED_QUANTITY = Regex("(\\d+(?:[.,]\\d+)?)([a-zñ]+)")
private val TIMES_COUNT = Regex("[x×](\\d+)")
private const val EDGE_PUNCTUATION = ".,;:!?¡¿\"'`()[]{}*·•_/\\|-"

package io.theficos.comanda.parse

import java.text.Normalizer as UnicodeNormalizer

/** Stable item identity, with vocabulary and existing plural rules supplied as data. */
object Normalizer {

    private val grammar by lazy { InputGrammar(InputLanguages.available) }

    fun normalize(raw: String): String {
        val folded = foldCase(raw)
        if (folded.isEmpty()) return ""

        val words = folded.split(' ').filter { it.isNotEmpty() }
        val content = words.filterNot { it in grammar.filler }

        // "la" and "of" normalize to themselves rather than to nothing. An
        // empty key would collapse every filler-only item onto one cache row,
        // and a person who wrote a one-word item deserves to see it survive.
        val kept = if (content.isEmpty()) words else content

        return kept.joinToString(" ") { singularize(it) }
    }

    fun tokens(raw: String): List<String> =
        normalize(raw).split(' ').filter { it.isNotEmpty() }

    private fun singularize(word: String): String {
        if (word.length < 4 || word in grammar.pluralExceptions) return word
        for ((pattern, replacement) in grammar.pluralRules) {
            if (pattern.matches(word)) return pattern.replace(word, replacement)
        }
        return word
    }

}

/**
 * Lowercase, unaccented, punctuation-free, single-spaced. The part of
 * normalization that is about characters rather than about words, split out so
 * the word rules above read as word rules.
 */
internal fun foldCase(raw: String): String {
    if (raw.isEmpty()) return ""
    val lowered = raw.lowercase()
    val unaccented = stripAccents(lowered)
    val builder = StringBuilder(unaccented.length)
    var lastWasSpace = true
    for (ch in unaccented) {
        val keep = ch.isLetterOrDigit()
        if (keep) {
            builder.append(ch)
            lastWasSpace = false
        } else if (!lastWasSpace) {
            builder.append(' ')
            lastWasSpace = true
        }
    }
    return builder.toString().trim()
}

/**
 * The parser's fold: lowercase and unaccented like [foldCase], but punctuation
 * inside a token survives, because "1,5" is one number and not two. Only the
 * edges are trimmed, which is where a full stop or an exclamation mark lands.
 */
internal fun foldToken(raw: String): String =
    stripAccents(raw.lowercase()).trim { it in TOKEN_EDGE_PUNCTUATION }

private const val TOKEN_EDGE_PUNCTUATION = ".,;:!?\u00A1\u00BF\"'`()[]{}"

private const val COMBINING_TILDE = '\u0303'

/**
 * Removes accents by decomposing to NFD and dropping the combining marks, with
 * one exception: the tilde over an n.
 *
 * Spanish ñ is a letter, not an accented n. "año" and "ano" are different
 * words, "pequeño" is not "pequeno", and a shop's product titles spell it with
 * the tilde. Folding it away would merge distinct names onto one cache key, so
 * the tilde survives when it sits on an n and is dropped everywhere else. Every
 * other diacritic is decoration for matching purposes: "payés" and "payes" are
 * the same bread, and half the people typing it will skip the accent.
 */
private fun stripAccents(text: String): String {
    val decomposed = UnicodeNormalizer.normalize(text, UnicodeNormalizer.Form.NFD)
    val builder = StringBuilder(decomposed.length)
    for (ch in decomposed) {
        if (ch == COMBINING_TILDE && builder.isNotEmpty() && builder.last() == 'n') {
            builder.append(ch)
        } else if (!isCombiningMark(ch)) {
            builder.append(ch)
        }
    }
    return UnicodeNormalizer.normalize(builder, UnicodeNormalizer.Form.NFC)
}

private fun isCombiningMark(ch: Char): Boolean = when (ch.category) {
    CharCategory.NON_SPACING_MARK,
    CharCategory.COMBINING_SPACING_MARK,
    CharCategory.ENCLOSING_MARK,
    -> true
    else -> false
}

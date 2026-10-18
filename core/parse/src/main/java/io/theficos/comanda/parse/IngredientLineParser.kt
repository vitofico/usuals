package io.theficos.comanda.parse

/** One ingredient line is one item. Commas describe preparation, never another item. */
class IngredientLineParser(
    private val parser: LocalUtteranceParser = LocalUtteranceParser(),
    packs: List<InputLanguage> = InputLanguages.available,
) {
    private val measures = packs.flatMap { it.words("recipe-measures") }.map(::foldToken).toSet()
    private val measureWords = measures.maxOfOrNull { it.split(' ').size } ?: 0
    private val numberWords = packs.flatMap { it.pairs("numbers").keys }.toSet()
    private val connectors = packs.flatMap { it.words("connectors") }.toSet()

    fun parse(line: String): ParsedItem? {
        val notes = mutableListOf<String>()
        val main = StringBuilder()
        val parenthesis = StringBuilder()
        var depth = 0
        val raw = line.trim().trimStart('*', '•', '-', ' ')
        for ((index, ch) in raw.withIndex()) {
            when {
                ch == '(' -> { if (depth > 0) parenthesis.append(ch); depth++ }
                ch == ')' && depth > 0 -> {
                    depth--
                    if (depth == 0) { notes += parenthesis.toString().trim().trimStart(',', ' '); parenthesis.clear(); main.append(' ') }
                    else parenthesis.append(ch)
                }
                depth > 0 -> parenthesis.append(ch)
                (ch == ',' || ch == ';') && !(raw.getOrNull(index - 1)?.isDigit() == true && raw.getOrNull(index + 1)?.isDigit() == true) -> {
                    notes += raw.substring(index + 1).trim()
                    break
                }
                else -> main.append(ch)
            }
        }
        if (parenthesis.isNotEmpty()) notes += parenthesis.toString()
        var text = fractions(main.toString().replace(Regex("\\s+"), " ").trim())
        var measure: String? = null
        val words = text.split(' ')
        val amount = words.firstOrNull().orEmpty()
        val hasAmount = foldToken(amount) in numberWords || Regex("^[0-9]+(?:[.,][0-9]+)?$").matches(amount)
        val count = if (hasAmount) (minOf(measureWords, words.size - 2) downTo 1).firstOrNull {
            foldToken(words.drop(1).take(it).joinToString(" ")) in measures
        } else null
        if (count != null) {
            measure = words.take(count + 1).joinToString(" ")
            text = words.drop(count + 1).joinToString(" ").trimStart('/', ' ')
            if (foldToken(text.substringBefore(' ')) in connectors) text = text.substringAfter(' ', "")
        }
        var parsed = parser.parseIngredientLine(text) ?: return null
        // A metric equivalent explicitly printed by the author can be used without conversion.
        if (measure != null && parsed.quantity == null) {
            val metric = notes.firstOrNull { Regex("^[0-9]+(?:[.,][0-9]+)?\\s*(g|kg|ml|l)$", RegexOption.IGNORE_CASE).matches(it) }
            if (metric != null) parsed = parser.parseIngredientLine("$metric ${parsed.name}") ?: parsed
        }
        return parsed.copy(note = (listOfNotNull(measure, parsed.note) + notes).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null })
    }

    private fun fractions(raw: String): String {
        val unicode = Regex("^(\\d*)\\s*([½¼¾⅓⅔⅛⅜⅝⅞])").find(raw)
        if (unicode != null) {
            val part = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3,
                '⅛' to 0.125, '⅜' to 0.375, '⅝' to 0.625, '⅞' to 0.875).getValue(unicode.groupValues[2].single())
            return ((unicode.groupValues[1].toDoubleOrNull() ?: 0.0) + part).toString() + " " + raw.substring(unicode.range.last + 1).trimStart()
        }
        val fraction = Regex("^(?:(\\d+)\\s+)?(\\d+)/(\\d+)\\b").find(raw) ?: return raw
        val divisor = fraction.groupValues[3].toDouble()
        if (divisor == 0.0) return raw
        val amount = (fraction.groupValues[1].toDoubleOrNull() ?: 0.0) + fraction.groupValues[2].toDouble() / divisor
        return amount.toString() + " " + raw.substring(fraction.range.last + 1).trimStart()
    }
}

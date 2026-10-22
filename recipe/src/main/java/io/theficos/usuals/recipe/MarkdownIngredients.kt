package io.theficos.usuals.recipe

import io.theficos.usuals.parse.InputLanguages
import java.text.Normalizer
import java.util.Locale

/** Reads note lines and ingredient sections. Markdown is never rendered or fetched. */
class MarkdownIngredients {
    private val ingredients = InputLanguages.available.flatMap { it.ingredientHeadings }.map(::headingKey).toSet()
    private val ignored = InputLanguages.available.flatMap { it.ignoredRecipeHeadings }.map(::headingKey).toSet()

    fun read(text: String, emojiQuantities: Boolean = false): RecipeDocument {
        require(text.length <= MAX_TEXT)
        val source = visibleLines(text.removePrefix("\uFEFF").replace('\u2028', '\n').replace('\u2029', '\n'))
        val recipe = source.any { heading(it)?.second?.let(::headingKey) in ingredients }
        var title: String? = null
        var ingredientDepth: Int? = null
        var ignoredDepth: Int? = null
        var accept = !recipe
        val lines = mutableListOf<String>()
        val checked = mutableSetOf<Int>()
        source.forEach { raw ->
            val line = raw.trim()
            val heading = heading(line)
            if (heading != null) {
                val (depth, name) = heading
                val key = headingKey(name)
                if (title == null && depth == 1 && key !in ingredients && key !in ignored) title = name
                when {
                    key in ingredients -> { ingredientDepth = depth; ignoredDepth = null; accept = true }
                    key in ignored -> { ingredientDepth = null; ignoredDepth = depth; accept = false }
                    ingredientDepth != null && depth > ingredientDepth!! -> accept = true
                    ignoredDepth != null && depth > ignoredDepth!! -> accept = false
                    else -> { ingredientDepth = null; ignoredDepth = null; accept = !recipe }
                }
                return@forEach
            }
            if (!accept || line.isBlank() || line.startsWith('>') || RULE.matches(line)) return@forEach
            val entry = entry(line) ?: line
            val content = clean(CHECKBOX.replaceFirst(entry, "")).let { if (emojiQuantities) counted(it) else it }
            if (content.isNotBlank() && !URL.matches(content)) {
                if (CHECKED.containsMatchIn(entry)) checked += lines.size
                lines += content
            }
            require(lines.size <= MAX_ITEMS)
        }
        return RecipeDocument(title, lines, null, emptyList(), checked)
    }

    private fun visibleLines(text: String): List<String> {
        val result = mutableListOf<String>()
        var fence: String? = null
        var frontMatter = text.lineSequence().firstOrNull()?.trim() == "---"
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (frontMatter) {
                if (index > 0 && line == "---") frontMatter = false
                return@forEachIndexed
            }
            val marker = FENCE.find(line)?.value
            if (fence != null) {
                if (marker != null && marker.first() == fence!!.first() && marker.length >= fence!!.length && line.drop(marker.length).isBlank()) fence = null
                return@forEachIndexed
            }
            if (marker != null) { fence = marker; return@forEachIndexed }
            if (Regex("^(?:={3,}|-{3,})$").matches(line) && result.lastOrNull()?.let { it.isNotBlank() && entry(it.trim()) == null && HEADING.matchEntire(it.trim()) == null } == true) {
                result[result.lastIndex] = (if (line.startsWith('=')) "# " else "## ") + result.last().trim()
            } else result += raw
        }
        return result
    }

    private fun heading(raw: String): Pair<Int, String>? = HEADING.matchEntire(raw.trim())?.let {
        it.groupValues[1].length to clean(it.groupValues[2].trimEnd('#', ' '))
    } ?: clean(raw).takeIf { headingKey(it) in ingredients || headingKey(it) in ignored }?.let { 2 to it }
    private fun entry(line: String): String? = BULLET.matchEntire(line)?.groupValues?.get(1)
        ?: line.takeIf { CHECKBOX.containsMatchIn(it) }
    private fun clean(raw: String): String = HtmlText.clean(LINK.replace(IMAGE.replace(raw, ""), "$1")
        .replace("**", "").replace("__", "").replace("`", "").replace("*", "").replace("_", ""))
    /** Notes mark quantities with repeated emoji, "Cerveza 🍺🍺🍺"; the item name keeps only the words. */
    private fun counted(content: String): String {
        val trailing = TRAILING_EMOJI.find(content)
        val name = (trailing?.let { content.substring(0, it.range.first) } ?: content).replaceFirst(LEADING_EMOJI, "").trim()
        if (name.isBlank()) return content
        val marks = trailing?.let { EMOJI.findAll(it.value).map { mark -> mark.value }.toList() }.orEmpty()
        return if (marks.size > 1 && marks.distinct().size == 1 && !name.first().isDigit()) "${marks.size} $name" else name
    }
    private fun headingKey(raw: String): String = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim().trimEnd(':')

    companion object {
        const val MAX_TEXT = 30_000
        const val MAX_ITEMS = 500
        private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
        private val FENCE = Regex("^(?:`{3,}|~{3,})")
        private val RULE = Regex("^(?:-{3,}|\\*{3,}|_{3,})$")
        private val BULLET = Regex("^(?:[-+*•◦]|[0-9]+[.)])\\s+(.+)$")
        private val CHECKBOX = Regex("^(?:\\[[ xX]]|[☐☑☒])\\s*")
        private val CHECKED = Regex("^(?:\\[[xX]]|[☑☒])\\s*")
        private val IMAGE = Regex("!\\[[^]]*]\\([^)]*\\)")
        private val LINK = Regex("\\[([^]]+)]\\([^)]*\\)")
        private const val PICTOGRAPH = "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}]"
        private val EMOJI = Regex("$PICTOGRAPH[\\x{FE0F}\\x{20E3}]*(?:\\x{200D}$PICTOGRAPH\\x{FE0F}?)*")
        private val TRAILING_EMOJI = Regex("(?:\\s*${EMOJI.pattern})+\\s*$")
        private val LEADING_EMOJI = Regex("^(?:${EMOJI.pattern}\\s*)+")
        private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    }
}

package io.theficos.usuals.recipe

import io.theficos.usuals.parse.InputLanguages
import java.text.Normalizer
import java.util.Locale

/** Extracts explicit list entries. Markdown is source text, never rendered or fetched. */
class MarkdownIngredients {
    private val ingredients = InputLanguages.available.flatMap { it.ingredientHeadings }.map(::headingKey).toSet()
    private val ignored = InputLanguages.available.flatMap { it.ignoredRecipeHeadings }.map(::headingKey).toSet()

    fun read(text: String): RecipeDocument {
        require(text.length <= MAX_TEXT)
        val source = visibleLines(text.removePrefix("\uFEFF"))
        val recipe = source.any { heading(it)?.second?.let(::headingKey) in ingredients }
        var title: String? = null
        var ingredientDepth: Int? = null
        var ignoredDepth: Int? = null
        var accept = !recipe
        val lines = mutableListOf<String>()
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
            if (!accept || line.isBlank() || line.startsWith('>') || line == "---") return@forEach
            val entry = BULLET.matchEntire(line)?.groupValues?.get(1) ?: if (ingredientDepth != null) line else return@forEach
            if (CHECKED.containsMatchIn(entry)) return@forEach
            val content = clean(UNCHECKED.replaceFirst(entry, ""))
            if (content.isNotBlank() && !URL.matches(content)) lines += content
            require(lines.size <= MAX_ITEMS)
        }
        return RecipeDocument(title, lines, null, emptyList())
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
            if (Regex("^(?:={3,}|-{3,})$").matches(line) && result.lastOrNull()?.let { it.isNotBlank() && BULLET.matchEntire(it.trim()) == null && heading(it) == null } == true) {
                result[result.lastIndex] = (if (line.startsWith('=')) "# " else "## ") + result.last().trim()
            } else result += raw
        }
        return result
    }

    private fun heading(raw: String): Pair<Int, String>? = HEADING.matchEntire(raw.trim())?.let {
        it.groupValues[1].length to clean(it.groupValues[2].trimEnd('#', ' '))
    }
    private fun clean(raw: String): String = HtmlText.clean(LINK.replace(IMAGE.replace(raw, ""), "$1")
        .replace("**", "").replace("__", "").replace("`", "").replace("*", "").replace("_", ""))
    private fun headingKey(raw: String): String = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim().trimEnd(':')

    companion object {
        const val MAX_TEXT = 30_000
        const val MAX_ITEMS = 150
        private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
        private val FENCE = Regex("^(?:`{3,}|~{3,})")
        private val BULLET = Regex("^(?:[-+*]|[0-9]+[.)])\\s+(.+)$")
        private val CHECKED = Regex("^\\[[xX]]\\s*")
        private val UNCHECKED = Regex("^\\[ ]\\s*")
        private val IMAGE = Regex("!\\[[^]]*]\\([^)]*\\)")
        private val LINK = Regex("\\[([^]]+)]\\([^)]*\\)")
        private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    }
}

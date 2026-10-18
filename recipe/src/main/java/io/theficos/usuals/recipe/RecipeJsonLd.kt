package io.theficos.usuals.recipe

import kotlinx.serialization.json.*

data class RecipeDocument(val title: String?, val ingredients: List<String>, val servings: String?, val instructions: List<String>)

/** Reads embedded data only. No scripts execute and no linked context is fetched. */
class RecipeJsonLd {
    fun extract(html: String): RecipeDocument? {
        for (script in SCRIPT.findAll(html)) {
            if (!JSON_TYPE.containsMatchIn(script.groupValues[1])) continue
            val data = runCatching { Json.parseToJsonElement(script.groupValues[2].trim().removePrefix("<!--").removeSuffix("-->")) }.getOrNull() ?: continue
            for (node in objects(data)) {
                val types = strings(node["@type"])
                if (types.none { it.substringAfterLast('/').substringAfterLast('#') == "Recipe" }) continue
                val ingredients = ingredients(node["recipeIngredient"] ?: node["ingredients"]).map(HtmlText::clean)
                    .filter { it.isNotBlank() }.take(150)
                if (ingredients.isEmpty()) continue
                return RecipeDocument(strings(node["name"]).firstOrNull()?.let(HtmlText::clean), ingredients,
                    strings(node["recipeYield"]).distinct().joinToString(", ").ifBlank { null },
                    instructions(node["recipeInstructions"]).map(HtmlText::clean).filter { it.isNotBlank() }.take(150))
            }
        }
        return null
    }

    private fun objects(value: JsonElement, depth: Int = 0): Sequence<JsonObject> = sequence {
        if (depth > 32) return@sequence
        when (value) {
            is JsonObject -> { yield(value); for (child in value.values) yieldAll(objects(child, depth + 1)) }
            is JsonArray -> for (child in value) yieldAll(objects(child, depth + 1))
            else -> Unit
        }
    }

    private fun strings(value: JsonElement?): List<String> = when (value) {
        is JsonPrimitive -> listOfNotNull(value.contentOrNull)
        is JsonArray -> value.flatMap(::strings)
        is JsonObject -> strings(value["text"] ?: value["value"] ?: value["name"])
        else -> emptyList()
    }

    private fun ingredients(value: JsonElement?): List<String> = when (value) {
        is JsonArray -> value.flatMap(::ingredients)
        is JsonObject -> if (value.containsKey("itemListElement")) ingredients(value["itemListElement"]) else {
            val line = listOf("value", "unitText", "name").flatMap { strings(value[it]) }.joinToString(" ")
            listOf(line.ifBlank { strings(value["text"]).joinToString(" ") })
        }
        else -> strings(value)
    }

    private fun instructions(value: JsonElement?): List<String> = when (value) {
        is JsonArray -> value.flatMap(::instructions)
        is JsonObject -> instructions(value["itemListElement"] ?: value["steps"] ?: value["text"] ?: value["name"])
        else -> strings(value)
    }

    companion object {
        private val SCRIPT = Regex("<script\\b([^>]*)>([\\s\\S]*?)</script\\s*>", RegexOption.IGNORE_CASE)
        private val JSON_TYPE = Regex("\\btype\\s*=\\s*['\"]application/ld\\+json(?:\\s*;[^'\"]*)?['\"]", RegexOption.IGNORE_CASE)
    }
}

internal object HtmlText {
    private val script = Regex("<(script|style|noscript)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val tags = Regex("<[^>]+>")
    private val entities = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[A-Za-z]+);")
    fun clean(raw: String): String = entities.replace(tags.replace(raw, " ")) { match ->
        val key = match.groupValues[1]
        val numeric = when { key.startsWith("#x") -> key.drop(2).toIntOrNull(16); key.startsWith('#') -> key.drop(1).toIntOrNull(); else -> null }
        if (numeric != null && Character.isValidCodePoint(numeric)) String(Character.toChars(numeric))
        else mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "nbsp" to " ", "lt" to "<", "gt" to ">").getOrDefault(key, match.value)
    }.replace(Regex("\\s+"), " ").trim().take(2000)

    fun prose(html: String): String {
        val body = script.replace(html, " ").replace(Regex("<[^>]+>"), "\n")
        return body.lineSequence().map(::clean).filter { it.isNotBlank() }.joinToString("\n").take(30_000)
    }
}

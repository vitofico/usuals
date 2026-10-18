package io.theficos.usuals.parse

import io.theficos.usuals.model.MeasureUnit
import java.io.Reader
import java.util.Locale
import java.util.Properties

/** One data file supplies an input language, independent of the UI locale. */
class InputLanguage private constructor(private val data: Properties) {
    internal fun words(key: String): List<String> = data.getProperty(key, "")
        .split(',').map(String::trim).filter(String::isNotEmpty)
    internal fun pairs(key: String): Map<String, String> = words(key).associate {
        val (word, value) = it.split(':', limit = 2)
        word to value
    }
    val pantryStaples: List<String> get() = words("pantry-staples")

    val categorySeeds: Map<String, List<String>>
        get() = data.stringPropertyNames().filter { it.startsWith("category.") }.sorted()
            .associate { it.removePrefix("category.") to words(it) }

    internal val pluralRules: List<Pair<Regex, String>> = data.getProperty("plural-rules", "")
        .split(';').filter(String::isNotBlank).map {
            val (pattern, replacement) = it.split("=>", limit = 2)
            Regex(pattern) to replacement
        }

    companion object {
        fun read(reader: Reader): InputLanguage = InputLanguage(Properties().apply { load(reader) })
    }
}

object InputLanguages {
    // Probe standard language codes so a new resource file needs no registry edit.
    val available: List<InputLanguage> by lazy {
        Locale.getISOLanguages().sorted().mapNotNull { code ->
            InputLanguage::class.java.getResourceAsStream("/input-language/$code.properties")
                ?.bufferedReader(Charsets.UTF_8)?.use { InputLanguage.read(it) }
        }.also { require(it.isNotEmpty()) { "No input language packs found" } }
    }
}

internal class InputGrammar(private val packs: List<InputLanguage>) {
    private fun words(key: String) = packs.flatMap { it.words(key) }.toSet()
    private fun pairs(key: String) = packs.flatMap { it.pairs(key).entries }.associate { it.toPair() }
    val noise = words("noise")
    val connectors = words("connectors")
    val articles = words("articles")
    val conjunctions = words("conjunctions")
    val nonNames = connectors + articles + conjunctions + words("non-names")
    val noteMarkers = words("note-markers").map { " $it " }
    val numbers = pairs("numbers").mapValues { it.value.toDouble() }
    val multipliers = pairs("multipliers").mapValues { it.value.toDouble() }
    val units = pairs("units").mapValues { MeasureUnit.valueOf(it.value) }
    val filler = words("filler")
    val pluralExceptions = words("plural-exceptions")
    val pluralRules = packs.flatMap { it.pluralRules }
    val segmentSeparator = Regex(
        "\\s*[\\r\\n]+\\s*|\\s*(?<![0-9])[,;](?![0-9])\\s*|\\s+(?:" +
            conjunctions.sorted().joinToString("|", transform = Regex::escape) + ")\\s+",
        RegexOption.IGNORE_CASE,
    )
}

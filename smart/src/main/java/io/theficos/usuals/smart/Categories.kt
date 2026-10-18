package io.theficos.usuals.smart

import io.theficos.usuals.parse.InputLanguage
import io.theficos.usuals.parse.Normalizer

enum class Category {
    PRODUCE, DAIRY, BAKERY, MEAT, FISH, FROZEN, PANTRY, DRINKS, SNACKS, HOUSEHOLD, PERSONAL_CARE, OTHER,
}

class CategoryClassifier(packs: List<InputLanguage>) {
    private data class Seed(val key: String, val category: Category)
    private val seeds = packs.flatMap { pack ->
        pack.categorySeeds.flatMap { (code, words) ->
            val category = Category.entries.firstOrNull { it.name == code } ?: return@flatMap emptyList()
            words.map { Seed(Normalizer.normalize(it), category) }
        }
    }.distinct().filter { it.key.isNotBlank() }.sortedWith(
        compareByDescending<Seed> { it.key.split(' ').size }.thenByDescending { it.key.length }
            .thenBy { it.category.ordinal }.thenBy { it.key },
    )

    fun classify(name: String, override: String? = null): Category {
        Category.entries.firstOrNull { it.name == override }?.let { return it }
        val key = " ${Normalizer.normalize(name)} "
        return seeds.firstOrNull { key.contains(" ${it.key} ") }?.category ?: Category.OTHER
    }
}

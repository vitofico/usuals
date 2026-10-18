package io.theficos.comanda.smart

/** OFF taxonomy identifiers, independent of the person's input or UI language. */
class BarcodeCategories(private val fallback: CategoryClassifier) {
    fun category(tags: List<String>?, name: String): Category? {
        val set = tags.orEmpty().toSet()
        return mappings.firstOrNull { (_, keys) -> keys.any { "en:$it" in set } }?.first
            ?: fallback.classify(name).takeUnless { it == Category.OTHER }
    }

    private val mappings = listOf(
        Category.FROZEN to listOf("frozen-foods", "ice-creams-and-sorbets", "ice-creams"),
        Category.PANTRY to listOf("sauces", "canned-foods", "pasta", "rices", "cooking-helpers", "condiments", "oils", "spreads"),
        Category.DAIRY to listOf("dairies", "milks", "yogurts", "cheeses", "eggs"),
        Category.BAKERY to listOf("breads", "pastries", "bakery-products"),
        Category.MEAT to listOf("meats", "poultry", "beef", "pork"),
        Category.FISH to listOf("seafood", "fishes", "fish"),
        Category.PRODUCE to listOf("fruits", "vegetables", "fresh-fruits", "fresh-vegetables"),
        Category.DRINKS to listOf("beverages", "waters", "fruit-juices", "plant-based-beverages"),
        Category.SNACKS to listOf("snacks", "sweet-snacks", "salty-snacks", "chocolates", "biscuits"),
    )
}

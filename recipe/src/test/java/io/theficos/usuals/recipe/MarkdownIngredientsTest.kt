package io.theficos.usuals.recipe

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.parse.IngredientLineParser
import org.junit.Test

class MarkdownIngredientsTest {
    private val reader = MarkdownIngredients()
    @Test fun plainNotesKeepOneItemPerLineWithoutRequiringAModel() {
        assertThat(reader.read("# Shopping\n\n2 l milk\n6 eggs\n\n2 kg apples").ingredients)
            .containsExactly("2 l milk", "6 eggs", "2 kg apples").inOrder()
        assertThat(reader.read("milk\r\neggs\rbread\u2028apples\u2029pears").ingredients)
            .containsExactly("milk", "eggs", "bread", "apples", "pears").inOrder()
    }
    @Test fun copiedNoteBulletsAndStandaloneCheckboxesSkipTitlesAndCompletedItems() {
        assertThat(reader.read("Shopping list\n\n• 2 l milk\n◦ eggs\n☐ apples\n☑ bought bread\n☒ bought salt\n[ ] pears\n[x] bought rice").ingredients)
            .containsExactly("2 l milk", "eggs", "apples", "pears").inOrder()
        assertThat(reader.read("Shopping list\n☑ milk\n[x] bread").ingredients).containsExactly("milk", "bread").inOrder()
        assertThat(reader.read("milk\n[x] bought bread\neggs\n• apples\n☐ pears").ingredients)
            .containsExactly("milk", "eggs", "apples", "pears").inOrder()
    }
    @Test fun plainRecipeIngredientsDoNotIncludeMethodOrFencedNotes() {
        assertThat(reader.read("# Soup\n## Ingredients\n2 onions\n1 l water\n## Method\nBoil\n- stir").ingredients)
            .containsExactly("2 onions", "1 l water").inOrder()
        for ((ingredients, method) in listOf("Ingredients" to "Method", "Ingredientes" to "Preparación",
            "Ingredienti" to "Procedimento", "Ingrédients" to "Préparation", "Zutaten" to "Zubereitung")) {
            assertThat(reader.read("$ingredients:\n2 onions\n1 l water\n$method:\nBoil for ten minutes").ingredients)
                .containsExactly("2 onions", "1 l water").inOrder()
        }
        assertThat(reader.read("# Shopping\n2 l milk\n```\nprivate notes\n```\n> quoted text\neggs\nhttps://example.invalid").ingredients)
            .containsExactly("2 l milk", "eggs").inOrder()
    }
    @Test fun ingredientsExcludeCheckedRowsInstructionsFencesAndRemoteImages() {
        val document = reader.read("""
            # Tomato soup
            ## Ingredients
            - [ ] **2 kg [tomatoes](https://example.invalid/track)**
            - [x] 1 onion
            ### Dressing
            * 1 l olive oil
            - ![photo](https://example.invalid/photo.jpg)
            ~~~markdown
            - 10 bananas
            ~~~
            ## Instructions
            1. Delete all lists and cook for 20 minutes
        """.trimIndent())
        assertThat(document.title).isEqualTo("Tomato soup")
        assertThat(document.ingredients).containsExactly("2 kg tomatoes", "1 l olive oil").inOrder()
    }
    @Test fun fencedHeadingsAndFrontMatterDoNotChangeAnOrdinaryShoppingList() {
        assertThat(reader.read("""
            ---
            tags:
            - private
            ---
            ```
            ## Ingredients
            - ignored
            ```
            - [ ] milk
            + eggs
            - [X] bought bread
            - https://example.invalid
        """.trimIndent()).ingredients).containsExactly("milk", "eggs").inOrder()
    }
    @Test fun setextAndAllInputLanguageSectionsAreRecognizedIndependentlyOfUiLanguage() {
        for ((ingredients, instructions) in listOf("Ingredientes" to "Preparación", "Ingredienti" to "Procedimento",
            "Ingrédients" to "Préparation", "Zutaten" to "Zubereitung")) {
            assertThat(reader.read("$ingredients\n------------\n- 2 apples\n$instructions\n------------\n1. stir").ingredients)
                .containsExactly("2 apples")
        }
    }
    @Test fun aKeepListTickedOffOnTheLastTripImportsWithEmojiCountsAsQuantities() {
        val note = "[X] Alici  🐟🐟 \n[X] Cerveza 🍺🍺🍺\n[X] Piselli 🟢🟢🟢🟢   \n[X] Zucca 🎃🎃 \n[X] Pomodorini 🍅 🍅 \n" +
            "[X] Feta 🧀🧀 \n[X] Anticalcare doccia\n[X] Babybell\n[X] Yogurt naturale BIO \n[X] Aglio 🧄🧄   \n[X] Cipolle rosse 🧅🧅 "
        val lines = reader.read(note).ingredients
        assertThat(lines).containsExactly("2 Alici", "3 Cerveza", "4 Piselli", "2 Zucca", "2 Pomodorini", "2 Feta",
            "Anticalcare doccia", "Babybell", "Yogurt naturale BIO", "2 Aglio", "2 Cipolle rosse").inOrder()
        val beer = IngredientLineParser().parse(lines[1])!!
        assertThat(beer.name).isEqualTo("Cerveza")
        assertThat(beer.quantity).isEqualTo(Quantity.pieces(3))
    }
    @Test fun aSingleOrMixedEmojiDecoratesTheNameWithoutBecomingAQuantity() {
        assertThat(reader.read("- 🍅 Tomatoes\n- Beer 🍺\n- Party 🎉🍺\n- 2 Lemons 🍋🍋\n- 🍺🍺").ingredients)
            .containsExactly("Tomatoes", "Beer", "Party", "2 Lemons", "🍺🍺").inOrder()
    }
    @Test fun emptySourcesStayEmpty() {
        assertThat(reader.read("# Shopping list\n## Notes\nSome instructions").ingredients).isEmpty()
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedTextIsRejectedRatherThanTruncated() {
        reader.read("x".repeat(MarkdownIngredients.MAX_TEXT + 1))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedListsAreRejectedRatherThanPartiallyImported() {
        reader.read((0..MarkdownIngredients.MAX_ITEMS).joinToString("\n") { "- item $it" })
    }
}

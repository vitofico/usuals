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
    @Test fun copiedNoteBulletsAndStandaloneCheckboxesSkipTitlesAndKeepTickedItems() {
        assertThat(reader.read("Shopping list\n\n• 2 l milk\n◦ eggs\n☐ apples\n☑ bread\n☒ salt\n[ ] pears\n[x] rice").ingredients)
            .containsExactly("2 l milk", "eggs", "apples", "bread", "salt", "pears", "rice").inOrder()
        assertThat(reader.read("Shopping list\n☑ milk\n[x] bread").ingredients).containsExactly("milk", "bread").inOrder()
    }
    @Test fun aKeepListWithSomeRowsTickedImportsEveryRow() {
        assertThat(reader.read("Lista della spesa\n[ ] Latte\n[ ] Uova\n[X] Alici 🐟🐟\n[ ] Pane\n[X] Feta\n[ ] Mele\n[X] Babybell").ingredients)
            .containsExactly("Latte", "Uova", "2 Alici", "Pane", "Feta", "Mele", "Babybell").inOrder()
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
    @Test fun ingredientsExcludeInstructionsFencesAndRemoteImages() {
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
        assertThat(document.ingredients).containsExactly("2 kg tomatoes", "1 onion", "1 l olive oil").inOrder()
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
            - [X] bread
            - https://example.invalid
        """.trimIndent()).ingredients).containsExactly("milk", "eggs", "bread").inOrder()
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
    @Test fun aLongKeepListImportsWhole() {
        val note = (1..300).joinToString("\n") { if (it % 3 == 0) "[X] Item $it 🍅🍅" else "[ ] Item $it" }
        val lines = reader.read(note).ingredients
        assertThat(lines).hasSize(300)
        assertThat(lines.last()).isEqualTo("2 Item 300")
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedListsAreRejectedRatherThanPartiallyImported() {
        reader.read((0..MarkdownIngredients.MAX_ITEMS).joinToString("\n") { "- item $it" })
    }
}

package io.theficos.usuals.recipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownIngredientsTest {
    private val reader = MarkdownIngredients()
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
    @Test fun emptyAndCompletedSourcesStayEmpty() {
        assertThat(reader.read("# Shopping list\n- [x] milk\n## Notes\nSome instructions").ingredients).isEmpty()
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedTextIsRejectedRatherThanTruncated() {
        reader.read("x".repeat(MarkdownIngredients.MAX_TEXT + 1))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedListsAreRejectedRatherThanPartiallyImported() {
        reader.read((0..MarkdownIngredients.MAX_ITEMS).joinToString("\n") { "- item $it" })
    }
}

package io.theficos.usuals.parse

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import org.junit.Test

class IngredientLineParserTest {
    private val parser = IngredientLineParser()
    @Test fun `a preparation comma stays with one ingredient`() {
        val item = parser.parse("1 cebolla, picada")!!
        assertThat(item.name).isEqualTo("cebolla")
        assertThat(item.quantity).isEqualTo(Quantity.pieces(1))
        assertThat(item.note).isEqualTo("picada")
        assertThat(parser.parse("1,5 l milk")!!.quantity).isEqualTo(Quantity(1.5, MeasureUnit.LITRE))
    }
    @Test fun `fractions are exact and ambiguous cup measures stay in notes`() {
        assertThat(parser.parse("1 1/2 kg tomatoes")!!.quantity).isEqualTo(Quantity(1.5, MeasureUnit.KILOGRAM))
        assertThat(parser.parse("½ l milk")!!.quantity).isEqualTo(Quantity(0.5, MeasureUnit.LITRE))
        val cup = parser.parse("1/2 cup flour, sifted")!!
        assertThat(cup.name).isEqualTo("flour")
        assertThat(cup.quantity).isNull()
        assertThat(cup.note).isEqualTo("0.5 cup, sifted")
    }
    @Test fun `nested preparation parentheses and printed metric equivalents keep a usable name`() {
        val onion = parser.parse("1 onion (, chopped (white, yellow or red))")!!
        assertThat(onion.name).isEqualTo("onion")
        assertThat(onion.quantity).isEqualTo(Quantity.pieces(1))
        assertThat(parser.parse("1 lb / 500g beef (or pork, optional)")!!.name).isEqualTo("beef")
        assertThat(parser.parse("1 lb / 500g beef")!!.quantity).isEqualTo(Quantity(500.0, MeasureUnit.GRAM))
        assertThat(parser.parse("2 cups (250g) flour")!!.quantity).isEqualTo(Quantity(250.0, MeasureUnit.GRAM))
    }

}

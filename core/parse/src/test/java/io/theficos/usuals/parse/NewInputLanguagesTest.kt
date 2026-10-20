package io.theficos.usuals.parse

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import org.junit.Test

class NewInputLanguagesTest {
    private val parser = LocalUtteranceParser()
    @Test fun `Italian French and German quantities and multiple items work with all packs loaded`() {
        val examples = listOf(
            "aggiungi due litri di latte e pane" to listOf("latte", "pane"),
            "ajoute deux litres de lait et pain" to listOf("lait", "pain"),
            "kaufe zwei Liter Milch und Brot" to listOf("Milch", "Brot"))
        for ((text, names) in examples) {
            val rows = parser.parse(text)
            assertThat(rows.map { it.name }).containsExactlyElementsIn(names).inOrder()
            assertThat(rows.first().quantity).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))
        }
        for (text in listOf("1,5 litri di latte", "1,5 litres de lait", "1,5 Liter Milch"))
            assertThat(parser.parse(text).single().quantity).isEqualTo(Quantity(1.5, MeasureUnit.LITRE))
        assertThat(parser.parse("mezzo chilo di riso").single().quantity).isEqualTo(Quantity(0.5, MeasureUnit.KILOGRAM))
        assertThat(parser.parse("vingt pommes").single().quantity).isEqualTo(Quantity(20.0, MeasureUnit.PIECE))
        assertThat(parser.parse("fünf Äpfel").single().quantity).isEqualTo(Quantity(5.0, MeasureUnit.PIECE))
        assertThat(parser.parse("Milch für Kaffee").single().note).isEqualTo("für Kaffee")
    }
    @Test fun `recipe notes and pantry staples use the new vocabulary`() {
        val ingredient = IngredientLineParser()
        for ((line, name) in listOf("2 cucchiai di farina, setacciata" to "farina",
            "2 cuillères de farine, tamisée" to "farine", "2 Esslöffel Mehl, gesiebt" to "Mehl",
            "due cucchiai da tavola di farina" to "farina", "deux cuillères à soupe de farine" to "farine",
            "zwei Esslöffel Mehl" to "Mehl", "two cups flour" to "flour")) {
            val row = requireNotNull(ingredient.parse(line))
            assertThat(row.name).isEqualTo(name)
            assertThat(row.quantity).isNull()
            assertThat(row.note).isNotEmpty()
        }
        val staples = InputLanguages.available.flatMap { it.pantryStaples }.map(Normalizer::normalize)
        assertThat(staples).containsAtLeast("sale", "sel", "salz")
    }
    @Test fun `adding input packs preserves every old normalization word and rule`() {
        fun pack(code: String) = requireNotNull(javaClass.getResourceAsStream("/input-language/$code.properties"))
            .bufferedReader(Charsets.UTF_8).use(InputLanguage::read)
        val previous = InputGrammar(listOf(pack("en"), pack("es")))
        val current = InputGrammar(InputLanguages.available)
        assertThat(current.filler).containsExactlyElementsIn(previous.filler)
        assertThat(current.pluralExceptions).containsExactlyElementsIn(previous.pluralExceptions)
        assertThat(current.pluralRules.map { it.first.pattern to it.second })
            .containsExactlyElementsIn(previous.pluralRules.map { it.first.pattern to it.second }).inOrder()
        for ((key,value) in previous.numbers) assertThat(current.numbers[key]).isEqualTo(value)
        for ((key,value) in previous.units) assertThat(current.units[key]).isEqualTo(value)
        for ((key,value) in previous.multipliers) assertThat(current.multipliers[key]).isEqualTo(value)
    }
}

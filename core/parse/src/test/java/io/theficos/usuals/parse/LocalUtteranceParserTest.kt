package io.theficos.usuals.parse

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import org.junit.Test

class LocalUtteranceParserTest {

    private val parser = LocalUtteranceParser()

    private fun item(name: String, quantity: Quantity? = null, note: String? = null) =
        ParsedItem(name, quantity, note)

    private fun litres(amount: Double) = Quantity(amount, MeasureUnit.LITRE)
    private fun grams(amount: Double) = Quantity(amount, MeasureUnit.GRAM)

    /**
     * The whole contract in one table. Every row is a thing a person plausibly
     * says at an open fridge, or a thing a speech recogniser plausibly hands
     * over when they say nothing useful at all.
     */
    private val cases: List<Pair<String, List<ParsedItem>>> = listOf(
        // Bare items.
        "tomates" to listOf(item("tomates")),
        "pan de payés" to listOf(item("pan de payés")),
        "aceite de oliva virgen extra" to listOf(item("aceite de oliva virgen extra")),
        "Leche Entera" to listOf(item("Leche Entera")),

        // Leading verbs and politeness.
        "añade tomates" to listOf(item("tomates")),
        "apunta pan" to listOf(item("pan")),
        "add milk" to listOf(item("milk")),
        "necesitamos huevos" to listOf(item("huevos")),
        "por favor añade sal" to listOf(item("sal")),
        "hay que comprar papel de cocina" to listOf(item("papel de cocina")),
        "remember to buy bread" to listOf(item("bread")),
        "el pan" to listOf(item("pan")),

        // Quantities with units.
        "dos litros de leche" to listOf(item("leche", litres(2.0))),
        "2 litros de leche" to listOf(item("leche", litres(2.0))),
        "1,5 l de leche" to listOf(item("leche", litres(1.5))),
        "medio kilo de tomates" to listOf(item("tomates", Quantity(0.5, MeasureUnit.KILOGRAM))),
        "2kg de patatas" to listOf(item("patatas", Quantity(2.0, MeasureUnit.KILOGRAM))),
        "500 gramos de arroz" to listOf(item("arroz", grams(500.0))),
        "500g rice" to listOf(item("rice", grams(500.0))),
        "750 ml de aceite" to listOf(item("aceite", Quantity(750.0, MeasureUnit.MILLILITRE))),
        "half a litre of milk" to listOf(item("milk", litres(0.5))),
        "pack de cervezas" to listOf(item("cervezas", Quantity(1.0, MeasureUnit.PACK))),
        "dos botellas de vino" to listOf(item("vino", Quantity(2.0, MeasureUnit.PACK))),

        // Counts.
        "tres manzanas" to listOf(item("manzanas", Quantity.pieces(3))),
        "3 manzanas" to listOf(item("manzanas", Quantity.pieces(3))),
        "un pan" to listOf(item("pan", Quantity.pieces(1))),
        "veinte huevos" to listOf(item("huevos", Quantity.pieces(20))),
        "dieciséis cervezas" to listOf(item("cervezas", Quantity.pieces(16))),
        "una docena de huevos" to listOf(item("huevos", Quantity.pieces(12))),
        "media docena de huevos" to listOf(item("huevos", Quantity.pieces(6))),
        "un par de cervezas" to listOf(item("cervezas", Quantity.pieces(2))),
        "medio pan" to listOf(item("pan", Quantity(0.5, MeasureUnit.PIECE))),

        // Quantities written after the name, the way people type them.
        "leche x2" to listOf(item("leche", Quantity.pieces(2))),
        "coca cola 2l" to listOf(item("coca cola", litres(2.0))),
        "leche 2 l" to listOf(item("leche", litres(2.0))),

        // Several items in one breath.
        "leche, huevos y pan" to listOf(item("leche"), item("huevos"), item("pan")),
        "milk and bread" to listOf(item("milk"), item("bread")),
        "leche;huevos" to listOf(item("leche"), item("huevos")),
        "leche\nhuevos\npan" to listOf(item("leche"), item("huevos"), item("pan")),
        "compra dos kilos de patatas y un litro de leche" to listOf(
            item("patatas", Quantity(2.0, MeasureUnit.KILOGRAM)),
            item("leche", litres(1.0)),
        ),
        "necesito 6 huevos, medio kilo de tomates y pan" to listOf(
            item("huevos", Quantity.pieces(6)),
            item("tomates", Quantity(0.5, MeasureUnit.KILOGRAM)),
            item("pan"),
        ),

        // Notes.
        "leche para el café" to listOf(item("leche", note = "para el café")),
        "milk for the coffee" to listOf(item("milk", note = "for the coffee")),
        "leche (desnatada)" to listOf(item("leche", note = "desnatada")),
        "pan (integral) para el desayuno" to listOf(item("pan", note = "integral, para el desayuno")),
        "dos litros de leche para el café" to listOf(item("leche", litres(2.0), "para el café")),

        // Adversarial. None of these may throw, and none may produce a blank name.
        "" to emptyList(),
        "   " to emptyList(),
        "añade" to emptyList(),
        "por favor" to emptyList(),
        "3" to emptyList(),
        "dos litros de" to emptyList(),
        "!!! ,,, ???" to emptyList(),
        "¿?" to emptyList(),
        "de" to emptyList(),
        "y y y" to emptyList(),
        "\n\n" to emptyList(),
        "añade tomates." to listOf(item("tomates")),
        "¿huevos?" to listOf(item("huevos")),
        "  añade tomates  " to listOf(item("tomates")),
        "AÑADE TOMATES" to listOf(item("TOMATES")),
    )

    @Test
    fun `parses the shapes people actually say`() {
        for ((utterance, expected) in cases) {
            assertWithMessage("parsing %s", utterance).that(parser.parse(utterance)).isEqualTo(expected)
        }
    }

    @Test
    fun `the table covers every documented shape`() {
        // A guard against the table quietly shrinking.
        assertThat(cases.size).isAtLeast(25)
    }

    @Test
    fun `never throws and never returns a blank name`() {
        val hostile = listOf(
            "", " ", "\n", "\t\t", "((((", "))))", "()", "( )", "[]", "\\", "//",
            ",,,,", ";;;;", " y ", " and ", " e ", "x2", "×3", "2l", "500g",
            "1,5", "1.5.5", "0,,5 l", "leche (", "leche )", "leche ((doble))",
            "🍅", "🍅 tomates", "áéíóú", "ñ", "ÑÑÑ", "-", "----", "'", "\"",
            "un un un", "medio medio", "docena docena", "de de de",
            "a".repeat(500), "1".repeat(500), "leche ".repeat(200),
            "AÑADE, apunta; compra y trae", "para", "para el café",
        )
        for (utterance in hostile) {
            val parsed = parser.parse(utterance)
            for (parsedItem in parsed) {
                assertWithMessage("parsing %s produced a blank name", utterance)
                    .that(parsedItem.name.isNotBlank()).isTrue()
                assertWithMessage("parsing %s produced a name with no letters", utterance)
                    .that(parsedItem.name.any { it.isLetter() }).isTrue()
            }
        }
    }

    @Test
    fun `is deterministic`() {
        for ((utterance, _) in cases) {
            assertWithMessage("parsing %s twice", utterance)
                .that(parser.parse(utterance))
                .isEqualTo(parser.parse(utterance))
        }
    }

    @Test
    fun `keeps the words as the person wrote them`() {
        // The display name is not normalized: accents, casing and word order
        // survive so a row reads back the way it was said.
        val parsed = parser.parse("añade Pan de Payés").single()
        assertThat(parsed.name).isEqualTo("Pan de Payés")
        assertThat(Normalizer.normalize(parsed.name)).isEqualTo("pan payes")
    }

    @Test
    fun `a spoken quantity converts to base units for the scorer`() {
        val parsed = parser.parse("medio kilo de tomates").single()
        assertThat(parsed.quantity?.inBaseUnits()).isEqualTo(500.0)

        val spoken = parser.parse("half a litre of milk").single()
        assertThat(spoken.quantity?.inBaseUnits()).isEqualTo(500.0)
    }

    @Test
    fun `a decimal comma is a decimal and not a separator`() {
        val parsed = parser.parse("1,5 l de leche")
        assertThat(parsed).hasSize(1)
        assertThat(parsed.single().quantity).isEqualTo(litres(1.5))
    }
}

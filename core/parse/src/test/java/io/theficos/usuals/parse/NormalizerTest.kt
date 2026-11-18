package io.theficos.usuals.parse

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.usuals.model.NORMALIZER_VERSION
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import org.junit.Test

class NormalizerTest {

    @Test
    fun `shopping input normalizes to stable keys`() {
        val cases = listOf(
            "Leche entera" to "leche entera",
            "TOMATE" to "tomate",
            "  Leche   entera  " to "leche entera",
            "Leche\tentera\nfresca" to "leche entera fresca",
            "Pan de payés" to "pan payes",
            "Jamón" to "jamon",
            "café" to "cafe",
            "azúcar" to "azucar",
            "plátano" to "platano",
            "piña" to "piña",
            "Piñones" to "piñon",
            "año" to "año",
            "pañales" to "pañal",
            "PIÑA" to "piña",
            "tomates!!!" to "tomate",
            "¿huevos?" to "huevo",
            "Coca-Cola" to "coca cola",
            "leche (entera)" to "leche entera",
            "!!!" to "",
            "Aceite de oliva" to "aceite oliva",
            "papel de cocina" to "papel cocina",
            "los huevos" to "huevo",
            "el pan" to "pan",
            "a bag of rice" to "bag rice",
            "some milk" to "milk",
            "de" to "de",
            "la" to "la",
            "of a the" to "of a the",
            // Plain -s after a vowel.
            "tomates" to "tomate",
            "huevos" to "huevo",
            "manzanas" to "manzana",
            "galletas" to "galleta",
            "cervezas" to "cerveza",
            "lentejas" to "lenteja",
            "aguacates" to "aguacate",
            "detergentes" to "detergente",
            "postres" to "postre",
            "sobres" to "sobre",
            "dulces" to "dulce",
            "cafés" to "cafe",
            // -es after a consonant.
            "panes" to "pan",
            "papeles" to "papel",
            "limones" to "limon",
            "flores" to "flor",
            "yogures" to "yogur",
            "cereales" to "cereal",
            "azúcares" to "azucar",
            "meses" to "mes",
            // -ces collapses back to -z.
            "arroces" to "arroz",
            "nueces" to "nuez",
            "luces" to "luz",
            "lápices" to "lapiz",
            "tomate" to "tomate",
            "leche" to "leche",
            "pan" to "pan",
            "arroz" to "arroz",
            "agua" to "agua",
            "aceite" to "aceite",
            "yogur" to "yogur",
            "gas" to "gas",
            "paraguas" to "paraguas",
            "cumpleaños" to "cumpleaños",
            "país" to "pais",
            "atún" to "atun",
            "payés" to "payes",
            "" to "",
            "   " to "",
            "\n\t" to "",
        )
        for ((raw, expected) in cases) {
            assertWithMessage("normalize(%s)", raw).that(Normalizer.normalize(raw)).isEqualTo(expected)
        }
        assertThat(Normalizer.normalize("año")).isNotEqualTo(Normalizer.normalize("ano"))
    }

    @Test
    fun `spelling variants land on the same key`() {
        val variants = listOf("Pan de payés", "pan de payes", "PAN DE PAYÉS", "  pan  de  payés  ")
        val keys = variants.map { Normalizer.normalize(it) }.toSet()
        assertThat(keys).containsExactly("pan payes")

        assertThat(Normalizer.normalize("Tomates")).isEqualTo(Normalizer.normalize("tomate"))
        assertThat(Normalizer.normalize("Los huevos")).isEqualTo(Normalizer.normalize("huevo"))
    }

    @Test
    fun `is independent of the default locale`() {
        // A Turkish locale lowercases I to a dotless i, which would give that
        // phone a different cache key from every other phone.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertThat(Normalizer.normalize("LIMONES")).isEqualTo("limon")
            assertThat(Normalizer.normalize("Aceite DE Oliva")).isEqualTo("aceite oliva")
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `is idempotent, so a stored key normalizes to itself`() {
        val inputs = listOf(
            "Pan de payés", "TOMATES", "Aceite de oliva virgen extra", "piña",
            "los huevos", "arroces", "leche (entera)", "país",
        )
        for (input in inputs) {
            val once = Normalizer.normalize(input)
            assertWithMessage("re-normalizing %s", once).that(Normalizer.normalize(once)).isEqualTo(once)
        }
    }

    @Test
    fun `normalized keys match the corpus for the declared version`() {
        val utterances = listOf(
            "Leche entera", "TOMATE", "  Leche   entera  ", "Leche\tentera\nfresca",
            "Pan de payés", "Jamón", "café", "azúcar",
            "plátano", "piña", "Piñones", "año",
            "pañales", "PIÑA", "tomates!!!", "¿huevos?",
            "Coca-Cola", "leche (entera)", "!!!", "Aceite de oliva",
            "papel de cocina", "los huevos", "el pan", "a bag of rice",
            "some milk", "de", "la", "of a the",
            "tomates", "huevos", "manzanas", "galletas",
            "cervezas", "lentejas", "aguacates", "detergentes",
            "postres", "sobres", "dulces", "cafés",
            "panes", "papeles", "limones", "flores",
            "yogures", "cereales", "azúcares", "meses",
            "arroces", "nueces", "luces", "lápices",
            "tomate", "leche", "pan", "arroz",
            "agua", "aceite", "yogur", "gas",
            "paraguas", "cumpleaños", "país", "atún",
            "payés", "", "   ", "\n\t",
        )
        val hashes = mapOf(
            1 to "d867e0a975b1cdffa966186ed62fa552d322fa2437e11741ef693916e68f3e6c",
        )
        val keys = utterances.joinToString("\n", transform = Normalizer::normalize)
        val digest = MessageDigest.getInstance("SHA-256").digest(keys.toByteArray(Charsets.UTF_8))
        assertWithMessage("normalized corpus for version %s", NORMALIZER_VERSION)
            .that(HexFormat.of().formatHex(digest)).isEqualTo(hashes.getValue(NORMALIZER_VERSION))
    }
}

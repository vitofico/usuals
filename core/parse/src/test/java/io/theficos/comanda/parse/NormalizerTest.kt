package io.theficos.comanda.parse

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Locale
import org.junit.Test

class NormalizerTest {

    private fun check(cases: List<Pair<String, String>>) {
        for ((raw, expected) in cases) {
            assertWithMessage("normalize(%s)", raw).that(Normalizer.normalize(raw)).isEqualTo(expected)
        }
    }

    @Test
    fun `lowercases and collapses whitespace`() {
        check(
            listOf(
                "Leche entera" to "leche entera",
                "TOMATE" to "tomate",
                "  Leche   entera  " to "leche entera",
                "Leche\tentera\nfresca" to "leche entera fresca",
            ),
        )
    }

    @Test
    fun `strips accents`() {
        check(
            listOf(
                "Pan de payés" to "pan payes",
                "Jamón" to "jamon",
                "café" to "cafe",
                "azúcar" to "azucar",
                "plátano" to "platano",
            ),
        )
    }

    @Test
    fun `keeps enye, which is a letter and not an accented n`() {
        // "año" and "ano" are different words, and a shop's titles spell the
        // tilde. Folding it away would merge two products onto one cache key.
        check(
            listOf(
                "piña" to "piña",
                "Piñones" to "piñon",
                "año" to "año",
                "pañales" to "pañal",
                "PIÑA" to "piña",
            ),
        )
        assertThat(Normalizer.normalize("año")).isNotEqualTo(Normalizer.normalize("ano"))
    }

    @Test
    fun `drops punctuation`() {
        check(
            listOf(
                "tomates!!!" to "tomate",
                "¿huevos?" to "huevo",
                "Coca-Cola" to "coca cola",
                "leche (entera)" to "leche entera",
                "!!!" to "",
            ),
        )
    }

    @Test
    fun `drops filler words`() {
        check(
            listOf(
                "Aceite de oliva" to "aceite oliva",
                "papel de cocina" to "papel cocina",
                "los huevos" to "huevo",
                "el pan" to "pan",
                "a bag of rice" to "bag rice",
                "some milk" to "milk",
            ),
        )
    }

    @Test
    fun `a filler-only name keeps its words rather than becoming empty`() {
        // An empty key would collapse every filler-only item onto one cache row.
        check(
            listOf(
                "de" to "de",
                "la" to "la",
                "of a the" to "of a the",
            ),
        )
    }

    @Test
    fun `singularizes the plurals people actually write`() {
        check(
            listOf(
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
            ),
        )
    }

    @Test
    fun `leaves singular words alone`() {
        check(
            listOf(
                "tomate" to "tomate",
                "leche" to "leche",
                "pan" to "pan",
                "arroz" to "arroz",
                "agua" to "agua",
                "aceite" to "aceite",
                "yogur" to "yogur",
            ),
        )
    }

    @Test
    fun `leaves words whose final s belongs to the singular`() {
        check(
            listOf(
                "gas" to "gas",
                "paraguas" to "paraguas",
                "cumpleaños" to "cumpleaños",
                "país" to "pais",
                "atún" to "atun",
                "payés" to "payes",
            ),
        )
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
    fun `handles empty and blank input`() {
        check(
            listOf(
                "" to "",
                "   " to "",
                "\n\t" to "",
            ),
        )
        assertThat(Normalizer.tokens("")).isEmpty()
        assertThat(Normalizer.tokens("   ")).isEmpty()
    }

    @Test
    fun `tokens returns the normalized words`() {
        assertThat(Normalizer.tokens("Dos Litros de Leche Entera"))
            .containsExactly("dos", "litro", "leche", "entera")
            .inOrder()
        assertThat(Normalizer.tokens("Pan de payés")).containsExactly("pan", "payes").inOrder()
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
}

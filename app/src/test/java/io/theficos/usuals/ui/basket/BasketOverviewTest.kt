package io.theficos.usuals.ui.basket

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.NovaGroup
import io.theficos.usuals.enrich.NutriScore
import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.enrich.ProductType
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.smart.Category
import io.theficos.usuals.smart.CategoryClassifier
import org.junit.Test

class BasketOverviewTest {
    private val classifier = CategoryClassifier(InputLanguages.available)
    private fun overview(lines: List<BasketLine>) = basketOverview(lines, classifier::isProduceName)

    private var next = 0
    private fun scanned(nova: NovaGroup? = null, nutri: NutriScore? = null, category: Category = Category.PANTRY,
        notApplicable: Boolean = false, type: ProductType = ProductType.FOOD, key: String = "item-${next++}") =
        BasketLine(key, key, category, hasBarcode = true,
            quality = ProductQuality(nutriScore = nutri, novaGroup = nova, nutriScoreNotApplicable = notApplicable, productType = type))
    private fun typed(name: String, category: Category = classifier.classify(name), key: String = name) =
        BasketLine(key, name, category, hasBarcode = false, quality = null)

    @Test fun `the processing mix counts known items and phrases the ultra-processed share in tenths`() {
        val lines = List(5) { scanned(NovaGroup.UNPROCESSED) } + List(2) { scanned(NovaGroup.PROCESSED_CULINARY) } +
            List(1) { scanned(NovaGroup.PROCESSED) } + List(3) { scanned(NovaGroup.ULTRA_PROCESSED) } + List(3) { typed("thing $it") }
        val mix = overview(lines).processing
        assertThat(mix.unprocessed).isEqualTo(5)
        assertThat(mix.processed).isEqualTo(3)
        assertThat(mix.ultraProcessed).isEqualTo(3)
        assertThat(mix.known).isEqualTo(11)
        assertThat(mix.eligible).isEqualTo(14)
        assertThat(mix.enough).isTrue()
        // 3 of 11 is 0.27, so about 3 in 10.
        assertThat(mix.ultraShare).isEqualTo(UltraShare(UltraShare.Kind.ABOUT, 3))
    }

    @Test fun `the share keeps its edges apart from rounding`() {
        assertThat(UltraShare.of(0, 12)).isEqualTo(UltraShare(UltraShare.Kind.NONE, 0))
        assertThat(UltraShare.of(1, 30)).isEqualTo(UltraShare(UltraShare.Kind.UNDER_ONE, 0))
        assertThat(UltraShare.of(1, 20)).isEqualTo(UltraShare(UltraShare.Kind.ABOUT, 1))
        assertThat(UltraShare.of(29, 30)).isEqualTo(UltraShare(UltraShare.Kind.OVER_NINE, 10))
        assertThat(UltraShare.of(12, 12)).isEqualTo(UltraShare(UltraShare.Kind.ALL, 10))
        assertThat(UltraShare.of(0, 0)).isEqualTo(UltraShare(UltraShare.Kind.NONE, 0))
    }

    @Test fun `figures need at least eight known items and half the food items known`() {
        val sevenOfSeven = overview(List(7) { scanned(NovaGroup.PROCESSED, NutriScore.B) })
        assertThat(sevenOfSeven.processing.enough).isFalse()
        assertThat(sevenOfSeven.nutriScore.enough).isFalse()

        val eightOfSixteen = overview(List(8) { scanned(NovaGroup.PROCESSED, NutriScore.B) } + List(8) { typed("unknown $it") })
        assertThat(eightOfSixteen.processing.enough).isTrue()
        assertThat(eightOfSixteen.nutriScore.enough).isTrue()

        val eightOfSeventeen = overview(List(8) { scanned(NovaGroup.PROCESSED, NutriScore.B) } + List(9) { typed("unknown $it") })
        assertThat(eightOfSeventeen.processing.enough).isFalse()
        assertThat(eightOfSeventeen.nutriScore.enough).isFalse()
        // Coverage is still reported, so the screen can say how far off it is.
        assertThat(eightOfSeventeen.processing.known).isEqualTo(8)
        assertThat(eightOfSeventeen.processing.eligible).isEqualTo(17)
    }

    @Test fun `grades are counted per letter and never averaged into one`() {
        val lines = List(3) { scanned(nutri = NutriScore.A) } + List(4) { scanned(nutri = NutriScore.E) } + scanned(nutri = NutriScore.C)
        val grades = overview(lines).nutriScore
        assertThat(grades.counts).containsExactly(NutriScore.A, 3, NutriScore.B, 0, NutriScore.C, 1, NutriScore.D, 0, NutriScore.E, 4)
        assertThat(grades.known).isEqualTo(8)
        // The result type has no mean, no single letter: only the five counts and their coverage.
        assertThat(GradeCounts::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name })
            .containsExactly("counts", "eligible")
    }

    @Test fun `a missing grade is unknown, never counted as E or as ultra-processed`() {
        val result = overview(listOf(scanned(), scanned(nova = NovaGroup.ULTRA_PROCESSED)))
        assertThat(result.nutriScore.counts.getValue(NutriScore.E)).isEqualTo(0)
        assertThat(result.nutriScore.eligible).isEqualTo(2)
        assertThat(result.processing.ultraProcessed).isEqualTo(1)
        assertThat(result.processing.known).isEqualTo(1)
        assertThat(result.processing.eligible).isEqualTo(2)
    }

    @Test fun `household, personal care and non-food products leave every denominator`() {
        val result = overview(listOf(
            typed("lavavajillas"), typed("shampoo"),
            scanned(nova = NovaGroup.ULTRA_PROCESSED, category = Category.HOUSEHOLD),
            scanned(type = ProductType.BEAUTY, notApplicable = true, category = Category.OTHER),
            scanned(nova = NovaGroup.PROCESSED, nutri = NutriScore.C),
        ))
        assertThat(result.notFood).isEqualTo(4)
        assertThat(result.foodItems).isEqualTo(1)
        assertThat(result.processing.eligible).isEqualTo(1)
        assertThat(result.processing.ultraProcessed).isEqualTo(0)
        assertThat(result.nutriScore.eligible).isEqualTo(1)
    }

    @Test fun `does-not-apply products stay in the processing mix but leave the grade counts`() {
        val water = scanned(nova = NovaGroup.UNPROCESSED, notApplicable = true, category = Category.DRINKS)
        val result = overview(listOf(water, scanned(nutri = NutriScore.B)))
        assertThat(result.foodItems).isEqualTo(2)
        assertThat(result.processing.unprocessed).isEqualTo(1)
        assertThat(result.nutriScore.eligible).isEqualTo(1)
        assertThat(result.nutriScore.known).isEqualTo(1)
    }

    @Test fun `only an unbarcoded whole produce name counts as unprocessed, and never gets a grade`() {
        val result = overview(listOf(
            typed("tomates"), typed("Bananas"), typed("karotten"),
            // Files under Produce through "tomate", but it is a sauce.
            typed("salsa de tomate", category = Category.PRODUCE),
            // A produce word filed elsewhere by the person is not counted either.
            typed("manzanas", category = Category.SNACKS),
            // Scanned produce reads its own product data, unknown here.
            BasketLine("patatas", "patatas", Category.PRODUCE, hasBarcode = true, quality = null),
        ))
        assertThat(result.processing.freshProduce).isEqualTo(3)
        assertThat(result.processing.unprocessed).isEqualTo(3)
        assertThat(result.processing.known).isEqualTo(3)
        assertThat(result.processing.eligible).isEqualTo(6)
        assertThat(result.nutriScore.known).isEqualTo(0)
        assertThat(result.nutriScore.eligible).isEqualTo(3)
    }

    @Test fun `each item counts once by key, preferring the copy with product data`() {
        val result = overview(listOf(
            typed("milk", key = "milk"),
            BasketLine("milk", "milk", Category.DAIRY, true, ProductQuality(novaGroup = NovaGroup.UNPROCESSED, nutriScore = NutriScore.A)),
            typed("bread", key = "bread"), typed("Bread", key = "bread"),
        ))
        assertThat(result.foodItems).isEqualTo(2)
        assertThat(result.processing.unprocessed).isEqualTo(1)
        assertThat(result.nutriScore.counts.getValue(NutriScore.A)).isEqualTo(1)
    }

    @Test fun `an empty basket has nothing to show and nothing to divide by`() {
        val result = overview(emptyList())
        assertThat(result.foodItems).isEqualTo(0)
        assertThat(result.processing.enough).isFalse()
        assertThat(result.processing.ultraShare.kind).isEqualTo(UltraShare.Kind.NONE)
        assertThat(result.nutriScore.enough).isFalse()
    }
}

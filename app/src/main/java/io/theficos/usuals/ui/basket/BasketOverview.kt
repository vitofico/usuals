package io.theficos.usuals.ui.basket

import io.theficos.usuals.enrich.NovaGroup
import io.theficos.usuals.enrich.NutriScore
import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.enrich.ProductType
import io.theficos.usuals.smart.Category

/**
 * One thing on a list or trip, reduced to what the overview reads.
 *
 * [quality] is what this phone already holds for the row's own or remembered
 * barcode, and [hasBarcode] says whether there was a barcode to ask about at
 * all: only a row with none can be counted as fresh produce by its name.
 */
data class BasketLine(
    val key: String,
    val name: String,
    val category: Category,
    val hasBarcode: Boolean,
    val quality: ProductQuality?,
)

/**
 * How processed the food on a list or trip is, and how its Nutri-Scores spread.
 *
 * Counts only, never an average: Nutri-Score grades compare products of one kind
 * and its authors aggregate the continuous score over a whole diet, which a
 * shopping list cannot supply. Each section says whether it holds enough known
 * items to be shown at all.
 */
data class BasketOverview(
    /** Distinct food items: everything except household, personal care and non-food products. */
    val foodItems: Int,
    val notFood: Int,
    val processing: ProcessingMix,
    val nutriScore: GradeCounts,
)

data class ProcessingMix(
    /** NOVA 1, including [freshProduce]. */
    val unprocessed: Int = 0,
    /** Unbarcoded produce counted as NOVA 1 by its name. */
    val freshProduce: Int = 0,
    /** NOVA 2 and 3: culinary ingredients and processed foods read the same at basket level. */
    val processed: Int = 0,
    val ultraProcessed: Int = 0,
    /** Every food item, known or not. */
    val eligible: Int = 0,
) {
    val known: Int get() = unprocessed + processed + ultraProcessed
    val enough: Boolean get() = enoughKnown(known, eligible)
    val ultraShare: UltraShare get() = UltraShare.of(ultraProcessed, known)
}

data class GradeCounts(
    /** Every grade A to E, zero when none was seen. */
    val counts: Map<NutriScore, Int> = NutriScore.entries.associateWith { 0 },
    /** Food items Nutri-Score applies to: not fresh produce, not "does not apply". */
    val eligible: Int = 0,
) {
    val known: Int get() = counts.values.sum()
    val enough: Boolean get() = enoughKnown(known, eligible)
}

/**
 * The share of known items that are ultra-processed, in tenths, phrased by the
 * screen as "about 3 in 10". The edges are their own kinds so one ultra-processed
 * item in thirty is never rounded to "about 0 in 10" nor to "about 1 in 10".
 */
data class UltraShare(val kind: Kind, val inTen: Int) {
    enum class Kind { NONE, UNDER_ONE, ABOUT, OVER_NINE, ALL }

    companion object {
        fun of(ultra: Int, known: Int): UltraShare {
            if (known == 0 || ultra == 0) return UltraShare(Kind.NONE, 0)
            if (ultra == known) return UltraShare(Kind.ALL, 10)
            // Half up, in integers, so 0.25 and 0.35 round the same way on every device.
            val tenths = (ultra * 20 + known) / (known * 2)
            return when (tenths) {
                0 -> UltraShare(Kind.UNDER_ONE, 0)
                10 -> UltraShare(Kind.OVER_NINE, 10)
                else -> UltraShare(Kind.ABOUT, tenths)
            }
        }
    }
}

/** A share of a handful of items is noise, and a share of a minority of the basket describes the minority. */
const val MIN_KNOWN_ITEMS = 8

private fun enoughKnown(known: Int, eligible: Int) = known >= MIN_KNOWN_ITEMS && known * 2 >= eligible

private val NOT_FOOD = setOf(Category.HOUSEHOLD, Category.PERSONAL_CARE)

/**
 * Works out the overview. [isProduceName] says whether a whole name is a
 * produce word ("tomates", not "salsa de tomate"), which is the only way an
 * item without product data is ever classified.
 */
fun basketOverview(lines: List<BasketLine>, isProduceName: (String) -> Boolean): BasketOverview {
    // Each distinct item once, by identity key; a copy with product data beats one without.
    val distinct = lines.groupBy { it.key }.values.map { same -> same.firstOrNull { it.quality != null } ?: same.first() }
    val (food, notFood) = distinct.partition { line ->
        line.category !in NOT_FOOD && (line.quality?.productType ?: ProductType.FOOD) == ProductType.FOOD
    }
    var unprocessed = 0; var fresh = 0; var processed = 0; var ultra = 0
    val grades = NutriScore.entries.associateWith { 0 }.toMutableMap()
    var gradeEligible = 0
    for (line in food) {
        // NOVA defines fresh fruit and vegetables as group 1, so this applies the
        // classification to a food type. Nutri-Score has no grade for them, so
        // they never join the grade counts.
        val freshByName = !line.hasBarcode && line.category == Category.PRODUCE && isProduceName(line.name)
        when (line.quality?.novaGroup) {
            NovaGroup.UNPROCESSED -> unprocessed++
            NovaGroup.PROCESSED_CULINARY, NovaGroup.PROCESSED -> processed++
            NovaGroup.ULTRA_PROCESSED -> ultra++
            null -> if (freshByName) { unprocessed++; fresh++ }
        }
        if (freshByName || line.quality?.nutriScoreNotApplicable == true) continue
        gradeEligible++
        line.quality?.nutriScore?.let { grades[it] = grades.getValue(it) + 1 }
    }
    return BasketOverview(
        foodItems = food.size,
        notFood = notFood.size,
        processing = ProcessingMix(unprocessed, fresh, processed, ultra, food.size),
        nutriScore = GradeCounts(grades.toMap(), gradeEligible),
    )
}

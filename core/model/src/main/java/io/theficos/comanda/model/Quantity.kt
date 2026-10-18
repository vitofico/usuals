package io.theficos.comanda.model

import kotlinx.serialization.Serializable

/** What a [Quantity] is measured in. */
@Serializable
enum class MeasureUnit {
    PIECE,
    GRAM,
    KILOGRAM,
    MILLILITRE,
    LITRE,
    PACK,
    ;

    /**
     * Which physical kind of thing this unit measures. Two quantities are
     * only comparable inside one dimension.
     */
    val dimension: Dimension
        get() = when (this) {
            GRAM, KILOGRAM -> Dimension.MASS
            MILLILITRE, LITRE -> Dimension.VOLUME
            PIECE, PACK -> Dimension.COUNT
        }


}

@Serializable
enum class Dimension { MASS, VOLUME, COUNT }

/**
 * How much of something. Null on an [Item] means the person did not say, which
 * is the common case and is not the same as one of them.
 */
@Serializable
data class Quantity(val amount: Double, val unit: MeasureUnit) {

    /**
     * The amount converted to the dimension's base unit, grams or millilitres,
     * so two quantities written in different units can be compared with one
     * subtraction. Null for [MeasureUnit.PIECE] and [MeasureUnit.PACK], because
     * a count has no base unit and pretending otherwise would compare three eggs against 300 grams.
     */
    fun inBaseUnits(): Double? = when (unit) {
        MeasureUnit.GRAM, MeasureUnit.MILLILITRE -> amount
        MeasureUnit.KILOGRAM, MeasureUnit.LITRE -> amount * 1000
        MeasureUnit.PIECE, MeasureUnit.PACK -> null
    }

    /** True when both quantities measure the same kind of thing. */
    fun isComparableWith(other: Quantity): Boolean = unit.dimension == other.unit.dimension

    companion object {
        fun pieces(n: Int): Quantity = Quantity(n.toDouble(), MeasureUnit.PIECE)


    }
}

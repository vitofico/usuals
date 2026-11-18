package io.theficos.usuals.model

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class QuantityTest {
    @Test
    fun `quantities expose their dimension and base value`() {
        val cases = listOf(
            Triple(Quantity(500.0, MeasureUnit.GRAM), Dimension.MASS, 500.0),
            Triple(Quantity(0.5, MeasureUnit.KILOGRAM), Dimension.MASS, 500.0),
            Triple(Quantity(750.0, MeasureUnit.MILLILITRE), Dimension.VOLUME, 750.0),
            Triple(Quantity(1.5, MeasureUnit.LITRE), Dimension.VOLUME, 1500.0),
            Triple(Quantity(500.0, MeasureUnit.MILLILITRE), Dimension.VOLUME, 500.0),
            Triple(Quantity(0.5, MeasureUnit.LITRE), Dimension.VOLUME, 500.0),
            Triple(Quantity.pieces(3), Dimension.COUNT, null),
            Triple(Quantity(2.0, MeasureUnit.PACK), Dimension.COUNT, null),
        )
        for ((quantity, dimension, baseValue) in cases) {
            assertWithMessage("%s dimension", quantity).that(quantity.unit.dimension).isEqualTo(dimension)
            assertWithMessage("%s base value", quantity).that(quantity.inBaseUnits()).isEqualTo(baseValue)
        }
    }
}

package io.theficos.comanda.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QuantityTest {

    @Test
    fun `mass and volume convert to their base unit`() {
        assertThat(Quantity(500.0, MeasureUnit.GRAM).inBaseUnits()).isEqualTo(500.0)
        assertThat(Quantity(0.5, MeasureUnit.KILOGRAM).inBaseUnits()).isEqualTo(500.0)
        assertThat(Quantity(750.0, MeasureUnit.MILLILITRE).inBaseUnits()).isEqualTo(750.0)
        assertThat(Quantity(1.5, MeasureUnit.LITRE).inBaseUnits()).isEqualTo(1500.0)
    }

    @Test
    fun `half a litre and a 500ml bottle agree once converted`() {
        val spoken = Quantity(0.5, MeasureUnit.LITRE)
        val onThePack = Quantity(500.0, MeasureUnit.MILLILITRE)
        assertThat(spoken.inBaseUnits()).isEqualTo(onThePack.inBaseUnits())
    }

    @Test
    fun `counts have no base unit`() {
        assertThat(Quantity.pieces(3).inBaseUnits()).isNull()
        assertThat(Quantity(2.0, MeasureUnit.PACK).inBaseUnits()).isNull()
    }

    @Test
    fun `pieces builds a count`() {
        assertThat(Quantity.pieces(3)).isEqualTo(Quantity(3.0, MeasureUnit.PIECE))
        assertThat(Quantity.pieces(0)).isEqualTo(Quantity(0.0, MeasureUnit.PIECE))
    }

    @Test
    fun `dimensions separate what can be compared`() {
        assertThat(MeasureUnit.GRAM.dimension).isEqualTo(Dimension.MASS)
        assertThat(MeasureUnit.KILOGRAM.dimension).isEqualTo(Dimension.MASS)
        assertThat(MeasureUnit.MILLILITRE.dimension).isEqualTo(Dimension.VOLUME)
        assertThat(MeasureUnit.LITRE.dimension).isEqualTo(Dimension.VOLUME)
        assertThat(MeasureUnit.PIECE.dimension).isEqualTo(Dimension.COUNT)
        assertThat(MeasureUnit.PACK.dimension).isEqualTo(Dimension.COUNT)

        assertThat(Quantity(1.0, MeasureUnit.LITRE).isComparableWith(Quantity(500.0, MeasureUnit.MILLILITRE)))
            .isTrue()
        assertThat(Quantity(1.0, MeasureUnit.LITRE).isComparableWith(Quantity(1.0, MeasureUnit.KILOGRAM)))
            .isFalse()
        assertThat(Quantity.pieces(3).isComparableWith(Quantity(300.0, MeasureUnit.GRAM)))
            .isFalse()
    }

}

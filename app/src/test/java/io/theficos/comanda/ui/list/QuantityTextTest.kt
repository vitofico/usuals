package io.theficos.comanda.ui.list

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.comanda.model.MeasureUnit
import io.theficos.comanda.model.Quantity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class QuantityTextTest {
    @Test
    fun `quantities render with app resources and locale-aware numbers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cases = listOf(
            Quantity.pieces(2) to "×2",
            Quantity(0.5, MeasureUnit.KILOGRAM) to "0.5 kg",
            Quantity(1.5, MeasureUnit.LITRE) to "1.5 l",
            Quantity(2.0, MeasureUnit.LITRE) to "2 l",
            Quantity(500.0, MeasureUnit.GRAM) to "500 g",
            Quantity(750.0, MeasureUnit.MILLILITRE) to "750 ml",
            Quantity(2.0, MeasureUnit.PACK) to "2 pk",
        )
        for ((quantity, text) in cases) {
            assertWithMessage("formatting $quantity").that(formatQuantity(context, quantity)).isEqualTo(text)
        }
    }
}

package io.theficos.usuals.ui.product

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.*
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class ProductInfoSheetTest {
    @get:Rule val compose = createComposeRule()
    private val gtin = Gtin.parse("4006381333931")!!
    @Test fun `saved details remain visible with lookup off but refresh is disabled`() {
        var settings = false
        val quality = ProductQuality(productName = "Saved cocoa", brands = "Example", packageSize = "250 g")
        compose.setContent { UsualsTheme { ProductInfoSheet(
            ProductInfoState(gtin, "My cocoa", CachedQuality(gtin, quality, false, 123), loading = false, lookupEnabled = false),
            { error("Must not refresh with consent off") }, { settings = true }, {},
        ) } }
        compose.onNodeWithText("Saved cocoa").assertIsDisplayed()
        compose.onNodeWithText("Brand: Example").assertIsDisplayed()
        compose.onNodeWithText("Refresh").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Lookup settings").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertThat(settings).isTrue()
    }
    @Test fun `unknown codes offer contribution without unresolved name placeholders`() {
        compose.setContent { UsualsTheme { ProductInfoSheet(
            ProductInfoState(gtin, gtin.value, loading = false, lookupEnabled = true, result = QualityLookup.Missing(gtin)),
            {}, {}, {},
        ) } }
        compose.onNodeWithText("No product record was found", substring = true).assertIsDisplayed()
        compose.onAllNodes(hasText("%1\$s", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("View or contribute on Open Food Facts").performScrollTo().assertIsDisplayed()
    }
    @Test fun `a remembered barcode says it is the product scanned before`() {
        val provenance = androidx.compose.runtime.mutableStateOf<ProductProvenance>(ProductProvenance.Remembered(1_696_334_400_000L))
        compose.setContent { UsualsTheme { ProductInfoSheet(
            ProductInfoState(gtin, "Milk", loading = false, provenance = provenance.value), {}, {}, {},
        ) } }
        compose.onNodeWithText("Based on the product you scanned on October 3, 2023").assertIsDisplayed()
        provenance.value = ProductProvenance.Remembered(null)
        compose.onNodeWithText("Based on the product you scanned for this item").assertIsDisplayed()
        provenance.value = ProductProvenance.Scanned
        compose.onAllNodes(hasText("Based on the product", substring = true)).assertCountEquals(0)
    }
}

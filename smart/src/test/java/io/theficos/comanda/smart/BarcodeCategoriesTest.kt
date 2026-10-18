package io.theficos.comanda.smart

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.parse.InputLanguages
import org.junit.Test

class BarcodeCategoriesTest {
    @Test fun `taxonomy categories work for an unknown brand and specific frozen tags win`() {
        val mapper = BarcodeCategories(CategoryClassifier(InputLanguages.available))
        assertThat(mapper.category(listOf("en:milks"), "Blue label")).isEqualTo(Category.DAIRY)
        assertThat(mapper.category(listOf("en:spreads"), "Blue label")).isEqualTo(Category.PANTRY)
        assertThat(mapper.category(listOf("en:frozen-foods", "en:vegetables"), "Blue label")).isEqualTo(Category.FROZEN)
        assertThat(mapper.category(null, "milk")).isEqualTo(Category.DAIRY)
        assertThat(mapper.category(null, "Blue label")).isNull()
    }
}

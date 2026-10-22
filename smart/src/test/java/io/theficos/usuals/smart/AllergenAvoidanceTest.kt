package io.theficos.usuals.smart

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AllergenAvoidanceTest {
    private val avoid = setOf("en:milk", "en:nuts")

    @Test fun `an avoided allergen the record lists is contained`() {
        val match = AllergenAvoidance.match(listOf("en:gluten", "en:milk"), emptyList(), avoid)
        assertThat(match.contains).containsExactly("en:milk")
        assertThat(match.mayContain).isEmpty()
        assertThat(match.flagged).isTrue()
    }

    @Test fun `an avoided trace is may contain, and a contained one is not repeated as a trace`() {
        val match = AllergenAvoidance.match(listOf("en:milk"), listOf("en:nuts", "EN:Milk "), avoid)
        assertThat(match.contains).containsExactly("en:milk")
        assertThat(match.mayContain).containsExactly("en:nuts")
    }

    @Test fun `unstated allergens are unknown, never clear`() {
        val match = AllergenAvoidance.match(null, null, avoid)
        assertThat(match.allergensStated).isFalse()
        assertThat(match.flagged).isFalse()
    }

    @Test fun `a stated trace still warns when the allergen list was never filled in`() {
        val match = AllergenAvoidance.match(null, listOf("en:nuts"), avoid)
        assertThat(match.allergensStated).isFalse()
        assertThat(match.mayContain).containsExactly("en:nuts")
    }

    @Test fun `an empty allergen list is stated with nothing to flag`() {
        val match = AllergenAvoidance.match(emptyList(), emptyList(), avoid)
        assertThat(match.allergensStated).isTrue()
        assertThat(match.flagged).isFalse()
    }

    @Test fun `allergens the person does not avoid are not flagged`() {
        assertThat(AllergenAvoidance.match(listOf("en:gluten"), listOf("en:soybeans"), avoid).flagged).isFalse()
        assertThat(AllergenAvoidance.match(listOf("en:milk"), null, emptySet()).flagged).isFalse()
    }
}

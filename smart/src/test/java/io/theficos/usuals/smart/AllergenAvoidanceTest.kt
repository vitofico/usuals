package io.theficos.usuals.smart

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class AllergenAvoidanceTest {
    @Test fun `allergen and trace results retain the whole warning state`() {
        val avoid = setOf("en:milk", "en:nuts")
        data class Warning(
            val allergens: List<String>?,
            val traces: List<String>?,
            val avoided: Set<String>,
            val contains: List<String> = emptyList(),
            val mayContain: List<String> = emptyList(),
            val stated: Boolean,
            val flagged: Boolean,
        )
        val cases = listOf(
            Warning(listOf("en:gluten", "en:milk"), emptyList(), avoid, contains = listOf("en:milk"), stated = true, flagged = true),
            Warning(listOf("en:milk"), listOf("en:nuts", "EN:Milk "), avoid, listOf("en:milk"), listOf("en:nuts"), true, true),
            Warning(null, null, avoid, stated = false, flagged = false),
            Warning(null, listOf("en:nuts"), avoid, mayContain = listOf("en:nuts"), stated = false, flagged = true),
            Warning(emptyList(), emptyList(), avoid, stated = true, flagged = false),
            Warning(listOf("en:gluten"), listOf("en:soybeans"), avoid, stated = true, flagged = false),
            Warning(listOf("en:milk"), null, emptySet(), stated = true, flagged = false),
        )
        for (row in cases) {
            val match = AllergenAvoidance.match(row.allergens, row.traces, row.avoided)
            assertWithMessage("%s match", row).that(match)
                .isEqualTo(AllergenMatch(row.contains, row.mayContain, row.stated))
            assertWithMessage("%s flagged", row).that(match.flagged).isEqualTo(row.flagged)
        }
    }
}

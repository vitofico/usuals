package io.theficos.usuals.parse

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import org.junit.Test

class InputLanguageTest {
    @Test
    fun `a new input pack supplies words without changing parser code`() {
        val pack = InputLanguage.read("""
            numbers=due:2
            units=litri:LITRE
            noise=aggiungi
            connectors=di
            conjunctions=e
        """.trimIndent().reader())
        val parsed = LocalUtteranceParser(listOf(pack)).parse("aggiungi due litri di latte e pane")
        assertThat(parsed.map { it.name }).containsExactly("latte", "pane").inOrder()
        assertThat(parsed.first().quantity).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))
    }

}

package io.theficos.comanda.smart

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.model.*
import org.junit.Test

class AssistanceTest {
    private val day = RepeatPlanner.DAY_MILLIS
    private fun purchase(id: Int, quantity: Quantity?, suggestion: Boolean = false) =
        Purchase(ItemId("p$id"), "t$id", "milk", "milk", quantity, boughtAt = id * 21 * day, fromSuggestion = suggestion)
    private fun item(quantity: Quantity? = null, suggestion: Boolean = false) =
        Item(ItemId("active"), "milk", "milk", "milk", quantity, fromSuggestion = suggestion, createdAt = 63 * day, updatedAt = 63 * day)

    @Test fun `quantity agreement considers only the last five and never fills a missing value`() {
        val two = Quantity(2.0, MeasureUnit.LITRE)
        val one = Quantity(1.0, MeasureUnit.LITRE)
        val hints = QuantityHints()
        val history = HistorySnapshot(purchases = listOf(purchase(1, two), purchase(2, two), purchase(3, one)))
        assertThat(hints.hint("milk", history)).isEqualTo(two)
        assertThat(item().quantity).isNull()
        val newer = (4..8).map { purchase(it, Quantity.pieces(it)) }
        assertThat(hints.hint("milk", history.copy(purchases = history.purchases + newer))).isNull()
        assertThat(hints.hint("bread", history)).isNull()
    }

    @Test fun `a third accepted suggestion offers a rule but never creates one`() {
        val history = HistorySnapshot(purchases = listOf(purchase(1, null, true), purchase(2, null, true)))
        val offers = RepeatOffers()
        assertThat(offers.offers(listOf(item(suggestion = true)), history)).containsExactly(RepeatOffer("milk", "milk", 21))
        assertThat(history.preferences).isEmpty()
        assertThat(offers.offers(listOf(item()), history)).isEmpty()
        assertThat(offers.offers(listOf(item(suggestion = true)), history.copy(purchases = history.purchases.take(1)))).isEmpty()
        assertThat(offers.offers(listOf(item(suggestion = true)), history.copy(preferences = listOf(ItemPreference("milk", repeatEveryDays = 14))))).isEmpty()
    }

    @Test fun `similarity only proposes unknown names and honors confirmed aliases`() {
        val matcher = SimilarItems(HistoryRanker())
        val history = HistorySnapshot(purchases = listOf(purchase(1, null)))
        assertThat(matcher.find("whole milk", history, emptyList())!!.key).isEqualTo("milk")
        assertThat(matcher.find("milk", history, emptyList())).isNull()
        assertThat(matcher.find("bread", history, emptyList())).isNull()
        assertThat(matcher.find("whole milk", history.copy(aliases = listOf(ItemAlias("whole milk", "milk"))), emptyList())).isNull()
        val spanish = history.copy(purchases = history.purchases.map { it.copy(key = "leche", name = "leche") })
        assertThat(matcher.find("leche entera", spanish, emptyList())!!.key).isEqualTo("leche")
    }
}

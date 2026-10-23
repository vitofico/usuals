package io.theficos.usuals.smart

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
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
        assertThat(matcher.candidates("milkk", history, emptyList()).single().item.key).isEqualTo("milk")
        assertThat(matcher.candidates("milk", history, emptyList())).isEmpty()
        assertThat(matcher.candidates("bread", history, emptyList())).isEmpty()
        assertThat(matcher.candidates("milkk", history.copy(aliases = listOf(ItemAlias("milkk", "milk"))), emptyList())).isEmpty()
        val spanish = history.copy(purchases = history.purchases.map { it.copy(key = "leche", name = "leche") })
        assertThat(matcher.candidates("lechee", spanish, emptyList()).single().item.key).isEqualTo("leche")
    }

    @Test fun `hidden history stops similarity suggestions while active rows still ask about duplicates`() {
        val matcher = SimilarItems(HistoryRanker())
        val history = HistorySnapshot(purchases = listOf(purchase(1, null)), preferences = listOf(ItemPreference("milk", muted = true)))
        assertThat(matcher.candidates("milkk", history, emptyList())).isEmpty()
        assertThat(matcher.candidates("milkk", history, listOf(item())).single().item.key).isEqualTo("milk")
    }

    @Test fun `hints from history grouped once match a per item scan`() {
        // The scan every row used to run, kept here as the reference.
        fun scanned(key: String, history: HistorySnapshot): Quantity? {
            val aliases = history.aliases.associate { it.fromKey to it.toKey }
            val recent = history.purchases.filter { io.theficos.usuals.parse.ItemIdentity.purchaseKey(it, aliases) == key }
                .sortedWith(compareByDescending<Purchase> { it.boughtAt }.thenBy { it.id.value }).take(5)
            val counts = recent.mapNotNull { it.quantity }.groupingBy { it }.eachCount()
            return recent.mapNotNull { it.quantity }.distinct().filter { (counts[it] ?: 0) >= 2 }
                .sortedByDescending { counts[it] }.firstOrNull()
        }
        val gtin = requireNotNull(Gtin.parse("4006381333931"))
        val names = listOf("milk", "oat milk", "bread", "eggs", "4006381333931")
        val quantities = listOf(null, Quantity.pieces(1), Quantity.pieces(2), Quantity(1.0, MeasureUnit.LITRE))
        val purchases = (0 until 200).map { i ->
            val name = names[i % names.size]
            Purchase(ItemId("p$i"), "t${i / 7}", name, name, quantities[(i * 7 + i / 3) % quantities.size],
                gtin = gtin.takeIf { name == gtin.value }, boughtAt = (i % 13) * day,
                normalizerVersion = if (i % 11 == 0) 0 else NORMALIZER_VERSION)
        }
        val history = HistorySnapshot(purchases = purchases,
            aliases = listOf(ItemAlias("oat milk", "milk"), ItemAlias("gtin:${gtin.value}", "eggs")))
        val aliases = history.aliases.associate { it.fromKey to it.toKey }
        val grouped = purchases.groupBy { io.theficos.usuals.parse.ItemIdentity.purchaseKey(it, aliases) }
        val hints = QuantityHints()
        val keys = names + listOf("gtin:${gtin.value}", "missing")
        assertThat(keys.mapNotNull { scanned(it, history) }).isNotEmpty()
        for (key in keys) {
            assertThat(hints.hint(grouped[key].orEmpty())).isEqualTo(scanned(key, history))
            assertThat(hints.hint(key, history)).isEqualTo(scanned(key, history))
        }
    }
    private fun memories(vararg names: String) = HistorySnapshot(purchases = names.mapIndexed { index, name ->
        Purchase(ItemId("name-$index"), "trip-$index", name, name, boughtAt = index.toLong())
    })

    @Test fun `matching never adds or removes qualifiers or changes numeric details`() {
        val matcher = SimilarItems(HistoryRanker())
        val history = memories("milk", "whole milk", "oat milk", "gluten free bread", "milk 2", "leche")
        for (name in listOf("almond milk", "milk 3", "bread", "leche entera", "semi skimmed milk"))
            assertThat(matcher.candidates(name, history, emptyList())).isEmpty()
        assertThat(matcher.candidates("whole milkk", history, emptyList()).single().item.name).isEqualTo("whole milk")
    }

    @Test fun `automatic matching only permits an unambiguous transposition or doubled letter`() {
        val matcher = SimilarItems(HistoryRanker())
        assertThat(matcher.candidates("bananna", memories("banana"), emptyList()).single().automatic).isTrue()
        assertThat(matcher.candidates("yogrut", memories("yogurt"), emptyList()).single().automatic).isTrue()
        assertThat(matcher.candidates("silk", memories("milk"), emptyList()).single().automatic).isFalse()
        assertThat(matcher.candidates("breaad", memories("bread", "breard"), emptyList()).map { it.automatic }).containsExactly(false, false)
        assertThat(matcher.candidates("banana", memories("banana"), emptyList())).isEmpty()
    }

    @Test fun `confirmed names correct spelling without stripping details from older aliases`() {
        val matcher = SimilarItems(HistoryRanker())
        val history = memories("milk", "banana").copy(aliases = listOf(ItemAlias("whole milk", "milk"), ItemAlias("bananna", "banana")))
        assertThat(matcher.confirmedName("bananna", history, emptyList())).isEqualTo("banana")
        assertThat(matcher.confirmedName("whole milk", history, emptyList())).isNull()
    }

}

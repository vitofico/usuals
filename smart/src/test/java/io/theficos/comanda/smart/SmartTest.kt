package io.theficos.comanda.smart

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.model.*
import io.theficos.comanda.parse.InputLanguage
import io.theficos.comanda.parse.InputLanguages
import io.theficos.comanda.parse.Normalizer
import org.junit.Test

class SmartTest {
    private val day = RepeatPlanner.DAY_MILLIS
    private fun purchase(id: String, name: String, at: Long, key: String = Normalizer.normalize(name)) =
        Purchase(ItemId(id), "trip-$id", key, name, boughtAt = at)
    private fun item(name: String, gtin: Gtin? = null) = Item(ItemId("active"), name, name,
        Normalizer.normalize(name), gtin = gtin, createdAt = 0, updatedAt = 0)

    @Test fun `fresh English and Spanish words group without history`() {
        val classifier = CategoryClassifier(InputLanguages.available)
        for (word in listOf("milk", "yoghurt", "leche", "yogur", "queso")) {
            assertThat(classifier.classify(word)).isEqualTo(Category.DAIRY)
        }
        for (word in listOf("tomatoes", "manzanas", "zanahorias", "bananas")) {
            assertThat(classifier.classify(word)).isEqualTo(Category.PRODUCE)
        }
        assertThat(classifier.classify("bread")).isEqualTo(Category.BAKERY)
        assertThat(classifier.classify("lavavajillas")).isEqualTo(Category.HOUSEHOLD)
    }

    @Test fun `specific phrases beat component words and overrides beat every seed`() {
        val classifier = CategoryClassifier(InputLanguages.available)
        assertThat(classifier.classify("apple juice")).isEqualTo(Category.DRINKS)
        assertThat(classifier.classify("hand cream")).isEqualTo(Category.PERSONAL_CARE)
        assertThat(classifier.classify("frozen peas")).isEqualTo(Category.FROZEN)
        assertThat(classifier.classify("milk", "HOUSEHOLD")).isEqualTo(Category.HOUSEHOLD)
        assertThat(classifier.classify("unknown item")).isEqualTo(Category.OTHER)
    }

    @Test fun `category vocabulary for another input language is only pack data`() {
        val pack = InputLanguage.read("category.DAIRY=latte,formaggio\n".reader())
        assertThat(CategoryClassifier(listOf(pack)).classify("latte")).isEqualTo(Category.DAIRY)
    }

    @Test fun `repeat adds milk on day 21 and not day 20`() {
        val history = HistorySnapshot(purchases = listOf(purchase("milk", "milk", 0)),
            preferences = listOf(ItemPreference("milk", repeatEveryDays = 21)))
        val planner = RepeatPlanner()
        assertThat(planner.due(21 * day - 1, history, emptyList())).isEmpty()
        assertThat(planner.due(21 * day, history, emptyList())).containsExactly(RepeatItem("milk", "milk"))
    }

    @Test fun `a repeat rule needs no learned history and never duplicates an active item`() {
        val history = HistorySnapshot(preferences = listOf(ItemPreference("milk", repeatEveryDays = 21)))
        val planner = RepeatPlanner()
        assertThat(planner.due(0, history, emptyList())).containsExactly(RepeatItem("milk", "milk"))
        assertThat(planner.due(30 * day, history, listOf(item("milk")))).isEmpty()
    }

    @Test fun `confirmed aliases suppress duplicate repeats and combine autocomplete counts`() {
        val history = HistorySnapshot(
            purchases = listOf(purchase("a", "whole milk", day), purchase("b", "milk", 2 * day)),
            aliases = listOf(ItemAlias("whole milk", "milk")),
            preferences = listOf(ItemPreference("milk", repeatEveryDays = 21)),
        )
        assertThat(RepeatPlanner().due(30 * day, history, listOf(item("whole milk")))).isEmpty()
        assertThat(HistoryRanker().memories(history).single().purchaseCount).isEqualTo(2)
    }

    @Test fun `muted rules stay quiet and future purchases do not become due`() {
        val history = HistorySnapshot(purchases = listOf(purchase("milk", "milk", 100 * day)),
            preferences = listOf(ItemPreference("milk", repeatEveryDays = 21)))
        assertThat(RepeatPlanner().due(30 * day, history, emptyList())).isEmpty()
        assertThat(RepeatPlanner().due(130 * day, history.copy(
            preferences = listOf(ItemPreference("milk", muted = true, repeatEveryDays = 21))), emptyList())).isEmpty()
    }

    @Test fun `autocomplete ranks frequency then recency with a stable key tie-break`() {
        val history = HistorySnapshot(purchases = listOf(
            purchase("a", "milk", day), purchase("b", "milk", 2 * day),
            purchase("c", "milk", 3 * day), purchase("d", "mint", 4 * day),
        ))
        val ranked = HistoryRanker().autocomplete("mi", history)
        assertThat(ranked.map { it.name }).containsExactly("milk", "mint").inOrder()
        assertThat(HistoryRanker().autocomplete("mi", history, setOf("milk")).single().name).isEqualTo("mint")
        assertThat(HistoryRanker().autocomplete("", history)).isEmpty()
    }
    @Test fun `removing a repeat snoozes it until another shop closes`() {
        val history = HistorySnapshot(trips = listOf(Trip("first", 0)),
            purchases = listOf(purchase("milk", "milk", 0)),
            preferences = listOf(ItemPreference("milk", snoozedUntil = 0, repeatEveryDays = 21)))
        val planner = RepeatPlanner()
        assertThat(planner.due(21 * day, history, emptyList())).isEmpty()
        assertThat(planner.due(21 * day, history.copy(trips = history.trips + Trip("next", 21 * day)), emptyList()))
            .containsExactly(RepeatItem("milk", "milk"))
    }

    @Test fun `a canonical stop decision overrides an older aliased rule`() {
        val history = HistorySnapshot(aliases = listOf(ItemAlias("whole milk", "milk")),
            preferences = listOf(ItemPreference("whole milk", repeatEveryDays = 21), ItemPreference("milk")))
        assertThat(RepeatPlanner().due(30 * day, history, emptyList())).isEmpty()
    }

}

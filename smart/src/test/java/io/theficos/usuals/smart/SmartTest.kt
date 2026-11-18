package io.theficos.usuals.smart

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.InputLanguage
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.Normalizer
import org.junit.Test

class SmartTest {
    private val day = RepeatPlanner.DAY_MILLIS
    private fun purchase(id: String, name: String, at: Long, key: String = Normalizer.normalize(name)) =
        Purchase(ItemId(id), "trip-$id", key, name, boughtAt = at)
    private fun item(name: String, gtin: Gtin? = null) = Item(ItemId("active"), name, name,
        Normalizer.normalize(name), gtin = gtin, createdAt = 0, updatedAt = 0)

    @Test fun `category seeds classify names across input languages`() {
        val classifier = CategoryClassifier(InputLanguages.available)
        val namesByCategory = mapOf(
            Category.DAIRY to listOf("milk", "yoghurt", "leche", "yogur", "queso", "latte", "lait", "Milch"),
            Category.PRODUCE to listOf("tomatoes", "manzanas", "zanahorias", "bananas", "mele", "pommes", "Äpfel"),
            Category.BAKERY to listOf("bread", "pane", "pain", "Brot"),
            Category.HOUSEHOLD to listOf("lavavajillas", "carta igienica", "papier toilette", "Toilettenpapier"),
            Category.DRINKS to listOf("apple juice"),
            Category.PERSONAL_CARE to listOf("hand cream"),
            Category.FROZEN to listOf("frozen peas"),
            Category.OTHER to listOf("unknown item"),
        )
        for ((category, names) in namesByCategory) {
            for (name in names) {
                assertWithMessage("category for %s", name).that(classifier.classify(name)).isEqualTo(category)
            }
        }
        assertThat(classifier.classify("milk", "HOUSEHOLD")).isEqualTo(Category.HOUSEHOLD)
    }

    @Test fun `only a whole produce seed name is fresh produce`() {
        val classifier = CategoryClassifier(InputLanguages.available)
        for (name in listOf("tomates", "Manzanas", "bananas", "karotten", "patate")) {
            assertThat(classifier.isProduceName(name)).isTrue()
        }
        // Each of these files under Produce through one of its words.
        for (name in listOf("salsa de tomate", "tomato sauce", "zumo de naranja", "milk")) {
            assertThat(classifier.isProduceName(name)).isFalse()
        }
    }

    @Test fun `category vocabulary for another input language is only pack data`() {
        val pack = InputLanguage.read("category.DAIRY=latte,formaggio\n".reader())
        assertThat(CategoryClassifier(listOf(pack)).classify("latte")).isEqualTo(Category.DAIRY)
    }

    @Test fun `repeat rules respect the day boundary and active items`() {
        val preference = ItemPreference("milk", repeatEveryDays = 21)
        val noPurchases = HistorySnapshot(preferences = listOf(preference))
        val bought = noPurchases.copy(purchases = listOf(purchase("milk", "milk", 0)))
        val planner = RepeatPlanner()
        assertThat(planner.due(21 * day - 1, bought, emptyList())).isEmpty()
        assertThat(planner.due(21 * day, bought, emptyList())).containsExactly(RepeatItem("milk", "milk"))
        assertThat(planner.due(0, noPurchases, emptyList())).containsExactly(RepeatItem("milk", "milk"))
        assertThat(planner.due(30 * day, noPurchases, listOf(item("milk")))).isEmpty()
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

    @Test fun `autocomplete hides muted aliases without hiding snoozed purchases or changing history`() {
        val history = HistorySnapshot(purchases = listOf(
            purchase("a", "whole milk", day), purchase("b", "mint", day), purchase("c", "millet", day),
        ), aliases = listOf(ItemAlias("whole milk", "milk")), preferences = listOf(
            ItemPreference("whole milk", muted = true), ItemPreference("mint", snoozedUntil = 100 * day),
        ))
        assertThat(HistoryRanker().autocomplete("mi", history).map { it.name }).containsExactly("millet", "mint").inOrder()
        assertThat(HistoryRanker().memories(history).map { it.name }).contains("whole milk")
        val canonicalOverride = history.copy(preferences = history.preferences + ItemPreference("milk"))
        assertThat(HistoryRanker().autocomplete("milk", canonicalOverride).single().name).isEqualTo("whole milk")
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

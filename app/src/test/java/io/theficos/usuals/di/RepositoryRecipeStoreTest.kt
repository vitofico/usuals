package io.theficos.usuals.di

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.data.local.*
import io.theficos.usuals.llm.*
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.*
import io.theficos.usuals.recipe.*
import io.theficos.usuals.ui.recipe.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepositoryRecipeStoreTest {
    @Test fun `structured reads bypass the model and confirmed add rechecks duplicate identities`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try { MockWebServer().use { server ->
            server.start()
            val items = ItemRepository(db)
            val history = HistoryRepository(db, items)
            var calls = 0
            val model = object : LlmClient {
                override suspend fun probe() = ModelCapabilities(true, true, "fake")
                override suspend fun parseUtterance(utterance: String): List<ParsedItem> { calls++; return listOf(ParsedItem("carrots")) }
            }
            val budget = ModelBudget(1)
            val store = RepositoryRecipeStore(RecipeFetcher(RecipeJsonLd()), IngredientReviewPlanner(IngredientLineParser(), listOf("salt")),
                items, history, { model }, budget)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("""<script type="application/ld+json">{"@type":"Recipe","recipeIngredient":["1 onion","salt"]}</script>"""))
            val review = store.read(server.url("/structured").toString()) as RecipeLoad.Review
            assertThat(calls).isEqualTo(0)
            assertThat(budget.usedToday()).isEqualTo(0)
            assertThat(items.items()).isEmpty()
            items.add(ParsedItem("onion", Quantity.pieces(1)))
            store.add(review.rows.map { it.item })
            assertThat(items.items().single { it.name == "onion" }.quantity).isEqualTo(Quantity.pieces(1))
            val parsed = store.model(server.url("/prose").toString(), "one carrot") as RecipeLoad.Review
            assertThat(parsed.viaModel).isTrue()
            assertThat(calls).isEqualTo(1)
            assertThat(items.items().map { it.name }).doesNotContain("carrots")
            assertThat(store.model("x", "one carrot")).isEqualTo(RecipeLoad.Failed(modelProblem = RecipeModelProblem.BUDGET))
            assertThat(calls).isEqualTo(1)
        } } finally { db.close() }
    }
}

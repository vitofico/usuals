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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
            val markdown = store.markdown("# Dinner\n## Ingredients\n- 2 kg carrots\n- [x] bread\n## Instructions\n1. boil") as RecipeLoad.Review
            assertThat(markdown.rows.map { it.item.name }).containsExactly("carrots")
            assertThat(markdown.rows.single().item.quantity).isEqualTo(Quantity(2.0, MeasureUnit.KILOGRAM))
            assertThat(calls).isEqualTo(0)
            assertThat(server.requestCount).isEqualTo(1)
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

    @Test fun `photo upload needs a connected image model and budgets each request before review`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try {
            val items = ItemRepository(db); val history = HistoryRepository(db, items)
            var configured: LlmClient? = null; var vision = false; var reachable = true; var probes = 0; var photos = 0
            val model = object : LlmClient {
                override suspend fun probe() = ModelCapabilities(true, true, "fake")
                override suspend fun parseUtterance(utterance: String) = emptyList<ParsedItem>()
                override suspend fun probeImages(): ModelCapabilities { probes++; return ModelCapabilities(reachable, vision, "fake", supportsImages = vision) }
                override suspend fun parseImage(image: ModelImage): List<ParsedItem> { photos++; return listOf(ParsedItem("tomatoes")) }
            }
            val budget = ModelBudget(3, clock = { 1000 })
            val store = RepositoryRecipeStore(RecipeFetcher(RecipeJsonLd()), IngredientReviewPlanner(IngredientLineParser(), emptyList()), items, history, { configured }, budget)
            val image = ModelImage("image/png", byteArrayOf(1))
            assertThat(store.photo(image)).isEqualTo(RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONFIGURED))
            assertThat(budget.usedToday()).isEqualTo(0)
            configured = model
            assertThat(store.photo(image)).isEqualTo(RecipeLoad.Failed(modelProblem = RecipeModelProblem.NO_IMAGES))
            assertThat(photos).isEqualTo(0)
            vision = true
            val review = store.photo(image) as RecipeLoad.Review
            assertThat(review.viaModel).isTrue()
            assertThat(budget.usedToday()).isEqualTo(3)
            assertThat(probes).isEqualTo(2)
            assertThat(photos).isEqualTo(1)
            assertThat(items.items()).isEmpty()
            assertThat(store.photo(image)).isEqualTo(RecipeLoad.Failed(modelProblem = RecipeModelProblem.BUDGET))
            assertThat(probes).isEqualTo(2)
            budget.changeCeiling(4); reachable = false
            assertThat(store.photo(image)).isEqualTo(RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONNECTED))
            assertThat(photos).isEqualTo(1)
            store.add(review.rows.map { it.item })
            assertThat(items.items().single().name).isEqualTo("tomatoes")
        } finally { db.close() }
    }
    @Test fun `server managed AI ignores the legacy direct ceiling and still requires a successful vision probe`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try {
            val items = ItemRepository(db); val history = HistoryRepository(db, items)
            var photos = 0; var supportsImages = false
            val model = object : LlmClient {
                override suspend fun probe() = ModelCapabilities(true, true, "synthetic")
                override suspend fun parseUtterance(utterance: String) = listOf(ParsedItem("carrots"))
                override suspend fun probeImages() = ModelCapabilities(true, true, "synthetic", supportsImages = supportsImages)
                override suspend fun parseImage(image: ModelImage): List<ParsedItem> { photos++; return listOf(ParsedItem("carrots")) }
            }
            val legacy = ModelBudget(0)
            val store = RepositoryRecipeStore(RecipeFetcher(RecipeJsonLd()), IngredientReviewPlanner(IngredientLineParser(), emptyList()),
                items, history, { model }, legacy, access = { RecipeModelAccess(model, serverManaged = true) })
            assertThat(store.model("", "carrots")).isInstanceOf(RecipeLoad.Review::class.java)
            val image = ModelImage("image/png", byteArrayOf(1))
            assertThat(store.photo(image)).isEqualTo(RecipeLoad.Failed(modelProblem = RecipeModelProblem.NO_IMAGES))
            assertThat(photos).isEqualTo(0)
            supportsImages = true
            assertThat(store.photo(image)).isInstanceOf(RecipeLoad.Review::class.java)
            assertThat(photos).isEqualTo(1)
            assertThat(legacy.usedToday()).isEqualTo(0)
            assertThat(items.items()).isEmpty()
        } finally { db.close() }
    }

    @Test fun `connecting a server during a legacy vision probe cancels the real photo upload`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try {
            val items = ItemRepository(db); val history = HistoryRepository(db, items)
            var routeCurrent = true; var photos = 0
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val model = object : LlmClient {
                override suspend fun probe() = error("unused")
                override suspend fun parseUtterance(utterance: String) = error("unused")
                override suspend fun probeImages(): ModelCapabilities {
                    entered.complete(Unit); release.await()
                    return ModelCapabilities(true, true, "synthetic", supportsImages = true)
                }
                override suspend fun parseImage(image: ModelImage): List<ParsedItem> { photos++; return emptyList() }
            }
            val budget = ModelBudget(2)
            val store = RepositoryRecipeStore(RecipeFetcher(RecipeJsonLd()), IngredientReviewPlanner(IngredientLineParser(), emptyList()),
                items, history, { model }, budget, access = { RecipeModelAccess(model, isCurrent = { routeCurrent }) })
            val read = async { store.photo(ModelImage("image/png", byteArrayOf(1))) }
            entered.await(); routeCurrent = false; release.complete(Unit)
            assertThat(read.await()).isEqualTo(RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONNECTED))
            assertThat(photos).isEqualTo(0)
            assertThat(budget.usedToday()).isEqualTo(1)
            assertThat(items.items()).isEmpty()
        } finally { db.close() }
    }

}

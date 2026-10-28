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
    @Test fun `plain copied lists keep quantities and stay local until confirmed`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try {
            db.listDao().insert(ListEntity(LOCAL_LIST_ID, ""))
            val items = ItemRepository(db)
            val history = HistoryRepository(db, items)
            val budget = ModelBudget(1)
            val store = RepositoryRecipeStore(RecipeFetcher(RecipeJsonLd()), IngredientReviewPlanner(IngredientLineParser(), emptyList()),
                items, history, { error("Copied lists never need a model") }, budget)
            val review = store.markdown("# Shopping\n2 l milk\n6 eggs\n2 kg apples") as RecipeLoad.Review
            assertThat(review.rows.map { it.item.name }).containsExactly("milk", "eggs", "apples").inOrder()
            assertThat(review.rows.map { it.item.quantity }).containsExactly(Quantity(2.0, MeasureUnit.LITRE),
                Quantity.pieces(6), Quantity(2.0, MeasureUnit.KILOGRAM)).inOrder()
            assertThat(items.items()).isEmpty()
            assertThat(budget.usedToday()).isEqualTo(0)
            val quick = store.review(LocalUtteranceParser().parse("1,5 l leche, huevos y pan")) as RecipeLoad.Review
            assertThat(quick.rows.map { it.item.name }).containsExactly("leche", "huevos", "pan").inOrder()
            assertThat(quick.rows.first().item.quantity).isEqualTo(Quantity(1.5, MeasureUnit.LITRE))
            assertThat(items.items()).isEmpty()
            assertThat(store.review(List(MarkdownIngredients.MAX_ITEMS + 1) { ParsedItem("milk") }))
                .isEqualTo(RecipeLoad.Failed(importProblem = ImportProblem.TOO_LARGE))
            store.add(review.rows.take(2), items.currentListId())
            assertThat(items.items().map { it.name }).containsExactly("milk", "eggs")
        } finally { db.close() }
    }
    @Test fun `a long note with ticked rows is added in batches and keeps them ticked`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try {
            db.listDao().insert(ListEntity(LOCAL_LIST_ID, ""))
            val items = ItemRepository(db)
            val history = HistoryRepository(db, items)
            val store = RepositoryRecipeStore(RecipeFetcher(RecipeJsonLd()), IngredientReviewPlanner(IngredientLineParser(), emptyList()),
                items, history, { error("Copied lists never need a model") }, ModelBudget(1))
            items.add(ParsedItem("Item 1"))
            val note = (1..250).joinToString("\n") { if (it % 5 == 0) "[x] Item $it" else "[ ] Item $it" }
            val review = store.markdown(note) as RecipeLoad.Review
            assertThat(review.rows.count { it.checked }).isEqualTo(50)
            store.add(review.rows, items.currentListId())
            val stored = items.items()
            assertThat(stored).hasSize(250)
            assertThat(stored.filter { it.checked }.map { it.name }).containsExactlyElementsIn((5..250 step 5).map { "Item $it" }).inOrder()
        } finally { db.close() }
    }
    @Test fun `a confirmed import keeps its original list before and between batches`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try {
            val lists = ListRepository(db)
            val origin = lists.create("Groceries")
            val other = lists.create("Shared shopping")
            var created = 0
            var switchAfterBatch = false
            val items = ItemRepository(db, newId = {
                if (++created == ItemRepository.IMPORT_BATCH && switchAfterBatch) {
                    // Commit the selection with the first batch, before the next batch starts.
                    db.openHelper.writableDatabase.execSQL("UPDATE list_selection SET listId = ? WHERE singleton = 1", arrayOf(other))
                }
                java.util.UUID.randomUUID().toString()
            })
            val history = HistoryRepository(db, items)
            val store = RepositoryRecipeStore(RecipeFetcher(RecipeJsonLd()), IngredientReviewPlanner(IngredientLineParser(), emptyList()),
                items, history, { error("Copied lists never need a model") }, ModelBudget(1))
            items.add(ParsedItem("Item 250", Quantity.pieces(7)))
            lists.select(origin)
            items.add(ParsedItem("Item 1"))
            val review = store.markdown((1..250).joinToString("\n") { "[ ] Item $it" }) as RecipeLoad.Review
            created = 0
            switchAfterBatch = true
            store.add(review.rows, origin)
            assertThat(lists.selected()).isEqualTo(other)
            assertThat(items.items(origin)).hasSize(250)
            assertThat(items.items(origin).map { it.position }).containsExactlyElementsIn((1..250).map { it.toDouble() }).inOrder()
            assertThat(items.items(other).single().quantity).isEqualTo(Quantity.pieces(7))

            // The confirmed scope is authoritative even if selection changes before add starts.
            val beforeStart = store.review(listOf(ParsedItem("bread"))) as RecipeLoad.Review
            store.add(beforeStart.rows, origin)
            assertThat(items.items(origin).map { it.name }).contains("bread")
            assertThat(items.items(other).map { it.name }).containsExactly("Item 250")
        } finally { db.close() }
    }

    @Test fun `an import cannot recreate a removed or archived list`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try {
            val lists = ListRepository(db)
            val origin = lists.create("Groceries")
            val items = ItemRepository(db)
            val history = HistoryRepository(db, items)
            val replica = LocalReplica(db)
            val store = RepositoryRecipeStore(RecipeFetcher(RecipeJsonLd()), IngredientReviewPlanner(IngredientLineParser(), emptyList()),
                items, history, { error("Copied lists never need a model") }, ModelBudget(1))
            val review = store.review(listOf(ParsedItem("milk"))) as RecipeLoad.Review
            replica.archive(origin, true)
            assertThat(runCatching { store.add(review.rows, origin) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(db.itemDao().active(origin)).isEmpty()
            replica.drop(origin)
            assertThat(runCatching { store.add(review.rows, origin) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(lists.find(origin)).isNull()
            assertThat(db.itemDao().active(origin)).isEmpty()
            assertThat(items.items()).isEmpty()
        } finally { db.close() }
    }

    @Test fun `structured reads bypass the model and confirmed add rechecks duplicate identities`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try { MockWebServer().use { server ->
            server.start()
            db.listDao().insert(ListEntity(LOCAL_LIST_ID, ""))
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
            val markdown = store.markdown("# Dinner\n## Ingredients\n- 2 kg carrots\n## Instructions\n1. boil") as RecipeLoad.Review
            assertThat(markdown.rows.map { it.item.name }).containsExactly("carrots")
            assertThat(markdown.rows.single().item.quantity).isEqualTo(Quantity(2.0, MeasureUnit.KILOGRAM))
            assertThat(calls).isEqualTo(0)
            assertThat(server.requestCount).isEqualTo(1)
            items.add(ParsedItem("onion", Quantity.pieces(1)))
            store.add(review.rows, items.currentListId())
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
            db.listDao().insert(ListEntity(LOCAL_LIST_ID, ""))
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
            store.add(review.rows, items.currentListId())
            assertThat(items.items().single().name).isEqualTo("tomatoes")
        } finally { db.close() }
    }
    @Test fun `server managed AI ignores the legacy direct ceiling and still requires a successful vision probe`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        try {
            db.listDao().insert(ListEntity(LOCAL_LIST_ID, ""))
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
            db.listDao().insert(ListEntity(LOCAL_LIST_ID, ""))
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

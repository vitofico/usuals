package io.theficos.usuals.recipe

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test

class RecipeTest {
    private val extractor = RecipeJsonLd()
    private val html = """<html><script type='application/ld+json'>{"@graph":[{"@type":"WebPage"},{"@type":["Recipe","HowTo"],"name":"Soup &amp; bread","recipeYield":["4","4 servings"],"recipeIngredient":["1 cebolla, picada","500 g carrots","salt"],"recipeInstructions":[{"@type":"HowToSection","name":"Soup","itemListElement":[{"@type":"HowToStep","text":"Chop &amp; cook."}]}]}]}</script></html>"""

    @Test fun `graph arrays nested instruction sections ingredients and yield are extracted`() {
        val recipe = extractor.extract(html)!!
        assertThat(recipe.title).isEqualTo("Soup & bread")
        assertThat(recipe.ingredients).containsExactly("1 cebolla, picada", "500 g carrots", "salt").inOrder()
        assertThat(recipe.servings).isEqualTo("4, 4 servings")
        assertThat(recipe.instructions).containsExactly("Chop & cook.")
    }

    @Test fun `malformed scripts do not hide a later recipe and external contexts are never fetched`() {
        val bad = "<script type=\"application/ld+json\">not json</script>"
        assertThat(extractor.extract(bad + html)!!.ingredients).hasSize(3)
        assertThat(extractor.extract("<script>fetch('https://example.invalid')</script>")).isNull()
        val structured = """<script type="application/ld+json">{"@context":"https://example.invalid/context","@type":"https://schema.org/Recipe","recipeIngredient":{"itemListElement":[{"value":2,"unitText":"g","name":"salt"}]}}</script>"""
        assertThat(extractor.extract(structured)!!.ingredients).containsExactly("2 g salt")
    }

    @Test fun `fetch distinguishes blocked pages non html and prose without a model`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fetcher = RecipeFetcher(extractor)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html))
            val read = fetcher.fetch(server.url("/recipe").toString()) as RecipeFetch.Read
            assertThat(read.document!!.ingredients).hasSize(3)
            assertThat(read.prose).isEmpty()
            server.enqueue(MockResponse().setResponseCode(403))
            assertThat(fetcher.fetch(server.url("/blocked").toString())).isEqualTo(RecipeFetch.Failed(RecipeProblem.BLOCKED, 403))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("PDF"))
            assertThat(fetcher.fetch(server.url("/pdf").toString())).isEqualTo(RecipeFetch.Failed(RecipeProblem.NOT_HTML))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<script>secret()</script><p>Chop one onion.</p>"))
            assertThat((fetcher.fetch(server.url("/prose").toString()) as RecipeFetch.Read).prose).isEqualTo("Chop one onion.")
            assertThat(fetcher.fetch("file:///tmp/recipe.html")).isEqualTo(RecipeFetch.Failed(RecipeProblem.INVALID_URL))
        }
    }

    @Test fun `review deduplicates active items and defaults pantry and recent purchases off`() {
        val planner = IngredientReviewPlanner(IngredientLineParser(), listOf("salt", "olive oil"))
        val active = Item(ItemId("a"), "milk", "milk", "milk", createdAt = 0, updatedAt = 0)
        val history = HistorySnapshot(purchases = listOf(Purchase(ItemId("p"), "t", "carrots", "carrots", boughtAt = 1000)))
        val rows = planner.plan(listOf("milk", "salt", "1 cebolla, picada", "2 cebolla", "500 g carrots"), history, listOf(active), 2000)
        assertThat(rows.map { it.reason }).containsExactly(IngredientReason.ON_LIST, IngredientReason.PANTRY, IngredientReason.NONE, IngredientReason.RECENT).inOrder()
        assertThat(rows.single { it.key == "cebolla" }.item.quantity).isEqualTo(Quantity.pieces(3))
    }

    @Test fun `a merged ingredient is ticked only when every source line was ticked`() {
        val planner = IngredientReviewPlanner(IngredientLineParser(), emptyList())
        val rows = planner.plan(listOf("milk", "2 cebolla", "1 cebolla", "eggs", "eggs"), HistorySnapshot(), emptyList(), 0, checked = setOf(0, 1, 3, 4))
        assertThat(rows.map { it.checked }).containsExactly(true, false, true).inOrder()
    }

    @Test fun `cancelling a recipe before headers closes its HTTP call`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val started = CompletableDeferred<Call>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { started.complete(call) }
            }).build()
            val job = async { RecipeFetcher(extractor, client).fetch(server.url("/slow").toString()) }
            val call = started.await()
            job.cancelAndJoin()
            assertThat(call.isCanceled()).isTrue()
        }
    }

    @Test fun `cancelling a recipe after headers stops its body download`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html")
                .setBody(html.repeat(100)).throttleBody(1, 1, TimeUnit.SECONDS))
            val headers = CompletableDeferred<Call>()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) { headers.complete(call) }
            }).build()
            val job = async { RecipeFetcher(extractor, client).fetch(server.url("/slow-body").toString()) }
            val call = headers.await()
            job.cancelAndJoin()
            assertThat(call.isCanceled()).isTrue()
        }
    }
}

package io.theficos.usuals.recipe

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Explicit opt-in only. These pages and policies may change independently of Usuals. */
class RecipeLiveTest {
    @Test fun `ten real URLs expose ingredient reviews without any model`() = runTest(timeout = 180.seconds) {
        assumeTrue(java.lang.Boolean.getBoolean("usuals.recipe.live"))
        val urls = javaClass.getResourceAsStream("/recipe-urls.txt")!!.bufferedReader().use { it.readLines() }
        val fetcher = RecipeFetcher(RecipeJsonLd())
        assertThat(urls).hasSize(10)
        val failures = mutableListOf<String>()
        for (url in urls) {
            val read = fetcher.fetch(url)
            val document = (read as? RecipeFetch.Read)?.document
            val status = (read as? RecipeFetch.Failed)?.let { "${it.problem}/${it.httpCode}" } ?: "no structured ingredients"
            if (document == null || document.ingredients.isEmpty()) failures += "$url $status"
            println("$url ingredients=${document?.ingredients?.size ?: 0} status=${if (document == null) status else "ok"}")
        }
        assertThat(failures).isEmpty()
    }

    @Test fun `a live blocking site returns a structured block reason`() = runTest {
        assumeTrue(java.lang.Boolean.getBoolean("usuals.recipe.live"))
        val read = RecipeFetcher(RecipeJsonLd()).fetch("https://www.loveandlemons.com/roasted-broccoli/")
        assertThat((read as? RecipeFetch.Failed)?.problem).isEqualTo(RecipeProblem.BLOCKED)
    }
}

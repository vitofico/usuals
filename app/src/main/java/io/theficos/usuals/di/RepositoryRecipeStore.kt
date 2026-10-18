package io.theficos.usuals.di

import io.theficos.usuals.data.local.HistoryRepository
import io.theficos.usuals.data.local.ItemRepository
import io.theficos.usuals.llm.LlmClient
import io.theficos.usuals.llm.ModelBudget
import io.theficos.usuals.parse.ItemIdentity
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.recipe.*
import io.theficos.usuals.ui.recipe.*

class RepositoryRecipeStore(
    private val fetcher: RecipeFetcher,
    private val planner: IngredientReviewPlanner,
    private val items: ItemRepository,
    private val history: HistoryRepository,
    private val client: () -> LlmClient?,
    private val budget: ModelBudget,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecipeStore {
    override suspend fun read(url: String): RecipeLoad = when (val result = fetcher.fetch(url)) {
        is RecipeFetch.Failed -> RecipeLoad.Failed(result.problem, httpCode = result.httpCode)
        is RecipeFetch.Read -> result.document?.let { document ->
            RecipeLoad.Review(result.url, document.title, document.servings,
                planner.plan(document.ingredients, history.snapshot(), items.items(), clock()))
        } ?: RecipeLoad.Unstructured(result.url, result.prose)
    }

    override suspend fun model(url: String, text: String): RecipeLoad {
        val model = client() ?: return RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONFIGURED)
        if (!budget.tryConsume()) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.BUDGET)
        val parsed = model.parseUtterance(text.take(30_000))
        if (parsed.isEmpty()) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.EMPTY)
        return RecipeLoad.Review(url, null, null,
            planner.planParsed(parsed.map { it.name to it }, history.snapshot(), items.items(), clock()), viaModel = true)
    }

    override suspend fun add(items: List<ParsedItem>) {
        this.items.transaction {
            val facts = history.snapshot()
            val aliases = facts.aliases.associate { it.fromKey to it.toKey }
            val present = this.items.items().map { ItemIdentity.key(it, aliases) }.toMutableSet()
            for (item in items) {
                val key = ItemIdentity.resolve(Normalizer.normalize(item.name), aliases)
                if (key.isNotBlank() && present.add(key)) this.items.add(item)
            }
        }
    }
}

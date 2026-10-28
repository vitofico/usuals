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

internal class RepositoryRecipeStore(
    private val fetcher: RecipeFetcher,
    private val planner: IngredientReviewPlanner,
    private val items: ItemRepository,
    private val history: HistoryRepository,
    private val client: () -> LlmClient?,
    private val budget: ModelBudget,
    private val clock: () -> Long = System::currentTimeMillis,
    private val markdown: MarkdownIngredients = MarkdownIngredients(),
    private val files: RecipeImportFiles = NoRecipeImportFiles,
    private val access: () -> RecipeModelAccess? = { client()?.let { RecipeModelAccess(it) } },
) : RecipeStore {
    override suspend fun read(url: String): RecipeLoad = when (val result = fetcher.fetch(url)) {
        is RecipeFetch.Failed -> RecipeLoad.Failed(result.problem, httpCode = result.httpCode)
        is RecipeFetch.Read -> result.document?.let { document ->
            RecipeLoad.Review(result.url, document.title, document.servings,
                planner.plan(document.ingredients, history.snapshot(), items.items(), clock()))
        } ?: RecipeLoad.Unstructured(result.url, result.prose)
    }

    override suspend fun model(url: String, text: String): RecipeLoad {
        val selected = access() ?: return RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONFIGURED)
        if (!selected.isCurrent()) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONNECTED)
        val model = selected.client
        if (!selected.serverManaged && !budget.tryConsume()) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.BUDGET)
        val parsed = model.parseUtterance(text.take(30_000))
        if (parsed.isEmpty()) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.EMPTY)
        return RecipeLoad.Review(url, null, null,
            planner.planParsed(parsed.map { it.name to it }, history.snapshot(), items.items(), clock()), viaModel = true)
    }

    override suspend fun markdown(text: String, emojiQuantities: Boolean): RecipeLoad = try {
        val document = markdown.read(text, emojiQuantities)
        RecipeLoad.Review("", document.title, null,
            planner.plan(document.ingredients, history.snapshot(), items.items(), clock(), document.checked))
    } catch (_: IllegalArgumentException) { RecipeLoad.Failed(importProblem = ImportProblem.TOO_LARGE) }

    override suspend fun markdownFile(uri: String, emojiQuantities: Boolean): RecipeLoad = importing { markdown(files.markdown(uri), emojiQuantities) }
    override suspend fun review(parsed: List<ParsedItem>): RecipeLoad {
        if (parsed.size > MarkdownIngredients.MAX_ITEMS) return RecipeLoad.Failed(importProblem = ImportProblem.TOO_LARGE)
        return RecipeLoad.Review("", null, null,
            planner.planParsed(parsed.map { it.name to it }, history.snapshot(), items.items(), clock()))
    }
    override suspend fun photoPreview(uri: String): RecipeLoad = importing {
        try { RecipeLoad.Photo(files.photo(uri)) } finally { files.releaseCamera(uri) }
    }
    override fun cameraUri(): String = files.cameraUri()
    override fun releaseCamera(uri: String) = files.releaseCamera(uri)
    override suspend fun photo(image: io.theficos.usuals.llm.ModelImage): RecipeLoad {
        val selected = access() ?: return RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONFIGURED)
        if (!selected.isCurrent()) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONNECTED)
        val model = selected.client
        if (!selected.serverManaged && !budget.tryConsume()) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.BUDGET)
        val capability = model.probeImages()
        if (!capability.reachable) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONNECTED)
        if (!capability.supportsImages || !capability.supportsStructuredOutput) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.NO_IMAGES)
        if (!selected.isCurrent()) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONNECTED)
        if (!selected.serverManaged && !budget.tryConsume()) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.BUDGET)
        val parsed = model.parseImage(image)
        if (parsed.isEmpty()) return RecipeLoad.Failed(modelProblem = RecipeModelProblem.EMPTY)
        return RecipeLoad.Review("", null, null,
            planner.planParsed(parsed.map { it.name to it }, history.snapshot(), items.items(), clock()), viaModel = true)
    }
    private suspend fun importing(read: suspend () -> RecipeLoad): RecipeLoad = try { read() }
        catch (problem: ImportFailure) { RecipeLoad.Failed(importProblem = problem.problem) }

    /** Writes in bounded batches, each its own short transaction, so the list stays usable while a long note lands. */
    override suspend fun add(rows: List<IngredientReview>, scope: String) {
        val fresh = items.transaction {
            val aliases = history.snapshot(scope).aliases.associate { it.fromKey to it.toKey }
            val present = items.items(scope).map { ItemIdentity.key(it, aliases) }.toMutableSet()
            rows.filter { row -> ItemIdentity.resolve(Normalizer.normalize(row.item.name), aliases).let { it.isNotBlank() && present.add(it) } }
        }
        for (batch in fresh.chunked(ItemRepository.IMPORT_BATCH)) items.addBatch(batch.map { it.item to it.checked }, scope)
    }
}

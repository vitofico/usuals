package io.theficos.comanda.ui.recipe

import io.theficos.comanda.parse.ParsedItem
import io.theficos.comanda.recipe.IngredientReview
import io.theficos.comanda.recipe.RecipeProblem

enum class RecipeModelProblem { NOT_CONFIGURED, BUDGET, EMPTY }
sealed interface RecipeLoad {
    data class Review(val url: String, val title: String?, val servings: String?, val rows: List<IngredientReview>, val viaModel: Boolean = false) : RecipeLoad
    data class Unstructured(val url: String, val text: String) : RecipeLoad
    data class Failed(val problem: RecipeProblem? = null, val modelProblem: RecipeModelProblem? = null, val httpCode: Int? = null) : RecipeLoad
}
data class RecipeUiState(val url: String = "", val busy: Boolean = false, val result: RecipeLoad? = null, val selected: Set<Int> = emptySet())

interface RecipeStore {
    suspend fun read(url: String): RecipeLoad
    suspend fun model(url: String, text: String): RecipeLoad
    suspend fun add(items: List<ParsedItem>)
}
object NoRecipeStore : RecipeStore {
    override suspend fun read(url: String) = RecipeLoad.Failed(RecipeProblem.UNREACHABLE)
    override suspend fun model(url: String, text: String) = RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONFIGURED)
    override suspend fun add(items: List<ParsedItem>) = Unit
}

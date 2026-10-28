package io.theficos.usuals.ui.recipe

import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.recipe.IngredientReview
import io.theficos.usuals.recipe.RecipeProblem
import io.theficos.usuals.llm.ModelImage

enum class RecipeModelProblem { NOT_CONFIGURED, BUDGET, EMPTY, NOT_CONNECTED, NO_IMAGES }
enum class ImportProblem { READ, TOO_LARGE, INVALID_IMAGE, INVALID_MARKDOWN }
enum class RecipeSource { MARKDOWN, URL, PHOTO }
sealed interface RecipeLoad {
    data class Review(val url: String, val title: String?, val servings: String?, val rows: List<IngredientReview>, val viaModel: Boolean = false) : RecipeLoad
    data class Unstructured(val url: String, val text: String) : RecipeLoad
    data class Photo(val image: ModelImage) : RecipeLoad
    data class Failed(val problem: RecipeProblem? = null, val modelProblem: RecipeModelProblem? = null, val httpCode: Int? = null,
        val importProblem: ImportProblem? = null) : RecipeLoad
}
data class RecipeUiState(val url: String = "", val busy: Boolean = false, val result: RecipeLoad? = null, val selected: Set<Int> = emptySet(),
    val source: RecipeSource = if (url.isBlank()) RecipeSource.MARKDOWN else RecipeSource.URL, val markdown: String = "", val fromShare: Boolean = false)

interface RecipeStore {
    suspend fun read(url: String): RecipeLoad
    suspend fun model(url: String, text: String): RecipeLoad
    suspend fun markdown(text: String, emojiQuantities: Boolean = false): RecipeLoad = RecipeLoad.Failed(importProblem = ImportProblem.READ)
    suspend fun review(parsed: List<ParsedItem>): RecipeLoad = RecipeLoad.Failed(importProblem = ImportProblem.READ)
    suspend fun markdownFile(uri: String, emojiQuantities: Boolean = false): RecipeLoad = RecipeLoad.Failed(importProblem = ImportProblem.READ)
    suspend fun photoPreview(uri: String): RecipeLoad = RecipeLoad.Failed(importProblem = ImportProblem.READ)
    suspend fun photo(image: ModelImage): RecipeLoad = RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONFIGURED)
    fun cameraUri(): String? = null
    fun releaseCamera(uri: String) = Unit
    suspend fun add(rows: List<IngredientReview>, scope: String)
}

interface RecipeImportFiles {
    suspend fun markdown(uri: String): String
    suspend fun photo(uri: String): ModelImage
    fun cameraUri(): String
    fun releaseCamera(uri: String)
}
class ImportFailure(val problem: ImportProblem) : Exception(problem.name)
object NoRecipeImportFiles : RecipeImportFiles {
    override suspend fun markdown(uri: String): String = throw ImportFailure(ImportProblem.READ)
    override suspend fun photo(uri: String): ModelImage = throw ImportFailure(ImportProblem.READ)
    override fun cameraUri(): String = throw ImportFailure(ImportProblem.READ)
    override fun releaseCamera(uri: String) = Unit
}
object NoRecipeStore : RecipeStore {
    override suspend fun read(url: String) = RecipeLoad.Failed(RecipeProblem.UNREACHABLE)
    override suspend fun model(url: String, text: String) = RecipeLoad.Failed(modelProblem = RecipeModelProblem.NOT_CONFIGURED)
    override suspend fun add(rows: List<IngredientReview>, scope: String) = Unit
}

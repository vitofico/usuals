package io.theficos.usuals.ui.recipe

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.recipe.IngredientReason
import io.theficos.usuals.recipe.IngredientReview
import io.theficos.usuals.ui.list.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class RecipeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun `sharing a URL reviews ingredients without adding and defaults pantry off`() = runTest(main.dispatcher) {
        var added = emptyList<ParsedItem>()
        val rows = listOf(IngredientReview("onion", "1 onion", ParsedItem("onion"), IngredientReason.NONE),
            IngredientReview("salt", "salt", ParsedItem("salt"), IngredientReason.PANTRY),
            IngredientReview("milk", "milk", ParsedItem("milk"), IngredientReason.ON_LIST))
        val recipe = object : RecipeStore by NoRecipeStore {
            override suspend fun read(url: String) = RecipeLoad.Review(url, "Soup", "4", rows)
            override suspend fun add(items: List<ParsedItem>) { added = items }
        }
        val store = FakeItemStore()
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), recipeStore = recipe)
        vm.onSharedText("Soup https://example.org/recipe")
        assertThat(store.current).isEmpty()
        assertThat(vm.state.value.recipe!!.selected).containsExactly(0)
        vm.onRecipeToggle(2)
        assertThat(vm.state.value.recipe!!.selected).containsExactly(0)
        vm.onAddRecipe()
        assertThat(added).containsExactly(ParsedItem("onion"))
        assertThat(vm.state.value.recipe).isNull()
    }

    @Test fun `closing a pending recipe cancels it and a new import remains usable`() = runTest(main.dispatcher) {
        val pending = CompletableDeferred<RecipeLoad>()
        var cancelled = false
        val recipe = object : RecipeStore by NoRecipeStore {
            override suspend fun read(url: String): RecipeLoad = if (url.endsWith("/slow")) {
                try { pending.await() } finally { cancelled = true }
            } else RecipeLoad.Review(url, "Next recipe", null, emptyList())
        }
        val items = FakeItemStore()
        val vm = ListViewModel(items, LocalUtteranceParser(), FakeSpeechSource(false), recipeStore = recipe)
        vm.onOpenRecipe("https://example.org/slow")
        assertThat(vm.state.value.recipe!!.busy).isTrue()
        vm.onDismissRecipe()
        assertThat(cancelled).isTrue()
        assertThat(vm.state.value.recipe).isNull()
        vm.onOpenRecipe("https://example.org/next")
        pending.complete(RecipeLoad.Failed())
        assertThat((vm.state.value.recipe!!.result as RecipeLoad.Review).title).isEqualTo("Next recipe")
        assertThat(items.current).isEmpty()
    }
    @Test fun `photo selection stays local and nothing is added until the reviewed proposal is confirmed`() = runTest(main.dispatcher) {
        var calls = 0; var added = emptyList<ParsedItem>()
        val image = io.theficos.usuals.llm.ModelImage("image/png", byteArrayOf(1))
        val row = IngredientReview("onion", "onion", ParsedItem("onion"), IngredientReason.NONE)
        val recipe = object : RecipeStore by NoRecipeStore {
            override suspend fun photoPreview(uri: String) = RecipeLoad.Photo(image)
            override suspend fun photo(image: io.theficos.usuals.llm.ModelImage): RecipeLoad { calls++; return RecipeLoad.Review("", null, null, listOf(row), true) }
            override suspend fun add(items: List<ParsedItem>) { added = items }
        }
        val vm = ListViewModel(FakeItemStore(), LocalUtteranceParser(), FakeSpeechSource(false), recipeStore = recipe)
        vm.onOpenRecipe(); vm.onRecipeSource(RecipeSource.PHOTO); vm.onRecipePhoto("content://test/photo")
        assertThat(vm.state.value.recipe!!.result).isInstanceOf(RecipeLoad.Photo::class.java)
        assertThat(calls).isEqualTo(0); assertThat(added).isEmpty()
        vm.onRecipeModel()
        assertThat(calls).isEqualTo(1); assertThat(added).isEmpty()
        vm.onAddRecipe(); assertThat(added).containsExactly(ParsedItem("onion"))
    }
}

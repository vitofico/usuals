package io.theficos.usuals.ui.recipe

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.recipe.IngredientReason
import io.theficos.usuals.recipe.IngredientReview
import io.theficos.usuals.ui.list.*
import io.theficos.usuals.ui.shopping.ShoppingToolsStore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test

class RecipeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun `single-line quick shares retain every locally parsed item and require review`() = runTest(main.dispatcher) {
        var proposals = emptyList<ParsedItem>()
        var added = emptyList<ParsedItem>()
        var reviews = 0
        val recipe = object : RecipeStore by NoRecipeStore {
            override suspend fun markdown(text: String, emojiQuantities: Boolean): RecipeLoad = error("Quick shares use the capture parser")
            override suspend fun review(parsed: List<ParsedItem>): RecipeLoad {
                proposals = parsed
                reviews++
                return RecipeLoad.Review("", null, null, parsed.map { IngredientReview(it.name, it.name, it, IngredientReason.NONE) })
            }
            override suspend fun add(rows: List<IngredientReview>) { added = rows.map { it.item } }
        }
        val vm = ListViewModel(FakeItemStore(), LocalUtteranceParser(), FakeSpeechSource(false), recipeStore = recipe)
        vm.onSharedText("leche, huevos y pan")
        assertThat(proposals.map { it.name }).containsExactly("leche", "huevos", "pan").inOrder()
        vm.onReadRecipeMarkdown()
        assertThat(reviews).isEqualTo(2)
        assertThat((vm.state.value.recipe!!.result as RecipeLoad.Review).rows.map { it.item.name })
            .containsExactly("leche", "huevos", "pan").inOrder()
        assertThat(added).isEmpty()
        vm.onDismissRecipe()
        assertThat(added).isEmpty()
        vm.onSharedText("leche, huevos y pan")
        vm.onAddRecipe()
        assertThat(added.map { it.name }).containsExactly("leche", "huevos", "pan").inOrder()
    }
    @Test fun `notes and Markdown links stay local and need confirmation`() = runTest(main.dispatcher) {
        var received: String? = null
        var added = emptyList<ParsedItem>()
        val recipe = object : RecipeStore by NoRecipeStore {
            override suspend fun read(url: String): RecipeLoad = error("A note must not fetch embedded links")
            override suspend fun markdown(text: String, emojiQuantities: Boolean): RecipeLoad {
                received = text
                return RecipeLoad.Review("", null, null, listOf(IngredientReview("milk", "2 l milk", ParsedItem("milk"), IngredientReason.NONE)))
            }
            override suspend fun add(rows: List<IngredientReview>) { added = rows.map { it.item } }
        }
        val store = FakeItemStore()
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), recipeStore = recipe)
        vm.onOpenRecipe()
        assertThat(vm.state.value.recipe!!.source).isEqualTo(RecipeSource.MARKDOWN)
        for (note in listOf("milk\neggs", "# Shopping\n- [milk](https://example.invalid)",
            "- [milk](https://example.invalid)", "- https://example.invalid", "Shopping\nhttps://example.invalid",
            "Buy milk https://example.invalid/tracker", "milk\u2028https://example.invalid", "> milk, eggs", "<b>milk</b>, eggs")) {
            vm.onSharedText(note)
            assertThat(received).isEqualTo(note)
            assertThat(vm.state.value.recipe!!.source).isEqualTo(RecipeSource.MARKDOWN)
            assertThat(vm.state.value.recipe!!.selected).containsExactly(0)
            vm.onDismissRecipe()
            assertThat(added).isEmpty()
            assertThat(store.current).isEmpty()
        }
        vm.onSharedText("2 l milk")
        assertThat(added).isEmpty()
        vm.onAddRecipe()
        assertThat(added).containsExactly(ParsedItem("milk"))
    }
    @Test fun `sharing a URL reviews ingredients without adding and defaults pantry off`() = runTest(main.dispatcher) {
        var added = emptyList<ParsedItem>()
        val rows = listOf(IngredientReview("onion", "1 onion", ParsedItem("onion"), IngredientReason.NONE),
            IngredientReview("salt", "salt", ParsedItem("salt"), IngredientReason.PANTRY),
            IngredientReview("milk", "milk", ParsedItem("milk"), IngredientReason.ON_LIST))
        val recipe = object : RecipeStore by NoRecipeStore {
            override suspend fun read(url: String) = RecipeLoad.Review(url, "Soup", "4", rows)
            override suspend fun add(rows: List<IngredientReview>) { added = rows.map { it.item } }
        }
        val store = FakeItemStore()
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), recipeStore = recipe)
        vm.onSharedText("https://example.org/recipe")
        assertThat(store.current).isEmpty()
        assertThat(vm.state.value.recipe!!.selected).containsExactly(0)
        vm.onRecipeToggle(2)
        assertThat(vm.state.value.recipe!!.selected).containsExactly(0)
        vm.onAddRecipe()
        assertThat(added).containsExactly(ParsedItem("onion"))
        assertThat(vm.state.value.recipe).isNull()
    }

    @Test fun `adding closes the sheet at once and keeps going when another import opens`() = runTest(main.dispatcher) {
        val release = CompletableDeferred<Unit>()
        var added = emptyList<IngredientReview>()
        val row = IngredientReview("onion", "onion", ParsedItem("onion"), IngredientReason.NONE, checked = true)
        val recipe = object : RecipeStore by NoRecipeStore {
            override suspend fun markdown(text: String, emojiQuantities: Boolean) = RecipeLoad.Review("", null, null, listOf(row))
            override suspend fun add(rows: List<IngredientReview>) { release.await(); added = rows }
        }
        val vm = ListViewModel(FakeItemStore(), LocalUtteranceParser(), FakeSpeechSource(false), recipeStore = recipe)
        vm.onSharedText("[x] onion\n[ ] leeks")
        vm.onAddRecipe()
        assertThat(vm.state.value.recipe).isNull()
        vm.onOpenRecipe(); vm.onDismissRecipe()
        release.complete(Unit)
        assertThat(added).containsExactly(row)
    }
    @Test fun `the emoji quantity choice is remembered and rereads the note`() = runTest(main.dispatcher) {
        val choices = mutableListOf<Boolean>()
        val recipe = object : RecipeStore by NoRecipeStore {
            override suspend fun markdown(text: String, emojiQuantities: Boolean): RecipeLoad { choices += emojiQuantities; return RecipeLoad.Review("", null, null, emptyList()) }
        }
        val tools = ShoppingToolsStore()
        val vm = ListViewModel(FakeItemStore(), LocalUtteranceParser(), FakeSpeechSource(false), recipeStore = recipe, shoppingTools = tools, background = main.dispatcher)
        vm.onOpenRecipe(); vm.onRecipeMarkdown("- Beer 🍺🍺"); vm.onReadRecipeMarkdown()
        vm.onEmojiQuantities(true)
        assertThat(choices).containsExactly(false, true).inOrder()
        assertThat(vm.state.value.recipe!!.result).isInstanceOf(RecipeLoad.Review::class.java)
        assertThat(tools.state.value.emojiQuantities).isTrue()
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
            override suspend fun add(rows: List<IngredientReview>) { added = rows.map { it.item } }
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

package io.theficos.usuals

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.theficos.usuals.di.*
import io.theficos.usuals.llm.*
import io.theficos.usuals.ui.list.ListViewModel
import io.theficos.usuals.ui.recipe.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in emulator UI fixture. No real provider, credential, photo or external request. */
class ImportDeviceScenarioTest {
    @Test fun reviewMarkdownAndSyntheticPhotoOnDevice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("importQa") == "synthetic")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "import-qa").apply { mkdirs() }
        fun mark(name: String, value: String = "ready") { File(directory, name).writeText(value) }
        withTimeout(600_000) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val challenge = VisionChallenge.create()
                var calls = 0
                val http = defaultHttpClient().newBuilder().addInterceptor { chain ->
                    val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer)
                    val request = JSONObject(buffer.readUtf8())
                    val image = request.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url")
                    val content = if (calls++ == 0) {
                        assertTrue(image.endsWith(java.util.Base64.getEncoder().encodeToString(challenge.image.bytes())))
                        JSONObject().put("colours", JSONArray(challenge.colours))
                    } else JSONObject().put("items", JSONArray().put(JSONObject().put("name", "Import QA peaches").put("amount", 2).put("unit", "KILOGRAM")))
                    val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content.toString()))))
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("synthetic")
                        .body(body.toString().toResponseBody("application/json".toMediaType())).build()
                }.build()
                val client = OpenAiCompatibleClient(ProviderConfig.ollama("synthetic-vision", "http://127.0.0.1:9/v1"), http) { challenge }
                val store = RepositoryRecipeStore(ServiceLocator.recipeFetcher, ServiceLocator.recipePlanner,
                    ServiceLocator.itemRepository, ServiceLocator.historyRepository, { client }, ModelBudget(4),
                    markdown = ServiceLocator.markdownIngredients, files = ServiceLocator.recipeImportFiles)
                lateinit var vm: ListViewModel
                scenario.onActivity { activity ->
                    vm = ListViewModel(ServiceLocator.itemStore, ServiceLocator.parser, ServiceLocator.speechSource, recipeStore = store, historyStore = ServiceLocator.historyStore)
                    activity.setContent { io.theficos.usuals.ui.theme.UsualsTheme {
                        val state by vm.state.collectAsState()
                        state.recipe?.let { RecipeImportSheet(it, vm::onRecipeUrl, vm::onReadRecipe, vm::onRecipeModel,
                            vm::onRecipeToggle, vm::onAddRecipe, vm::onDismissRecipe, vm::onRecipeSource,
                            vm::onRecipeMarkdown, vm::onReadRecipeMarkdown, vm::onRecipeMarkdownFile,
                            vm::onRecipePhoto, vm::createRecipePhoto, vm::releaseRecipePhoto, vm::onRecipeFileFailure) }
                    } }
                    vm.onOpenRecipe(); vm.onRecipeSource(RecipeSource.MARKDOWN)
                    vm.onRecipeMarkdown("# Shopping\n- [ ] 2 kg Import QA kiwis\n- [x] already done")
                }
                mark("markdown-ready")
                while (vm.state.value.recipe != null) delay(100)
                assertTrue(ServiceLocator.itemRepository.items().any { it.name == "Import QA kiwis" })
                assertEquals(0, calls)
                val uri = ServiceLocator.recipeImportFiles.cameraUri()
                val source = File(context.cacheDir, "import-photos/" + Uri.parse(uri).lastPathSegment)
                val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply { drawColor(Color.WHITE); drawText("2 kg peaches", 30f, 180f, Paint().apply { color = Color.BLACK; textSize = 56f }) }
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
                ExifInterface(source.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,2/1,3/1"); saveAttributes() }
                val prepared = ServiceLocator.recipeImportFiles.photo(uri)
                val bytes = prepared.bytes(); val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                assertEquals(400, decoded.width); assertEquals(600, decoded.height); decoded.recycle()
                assertNull(ExifInterface(bytes.inputStream()).getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                scenario.onActivity { vm.onOpenRecipe(); vm.onRecipeSource(RecipeSource.PHOTO); vm.onRecipePhoto(uri) }
                while (vm.state.value.recipe?.result !is RecipeLoad.Photo) delay(100)
                assertEquals(0, calls); mark("photo-ready")
                while (vm.state.value.recipe != null) delay(100)
                assertEquals(2, calls)
                assertTrue(ServiceLocator.itemRepository.items().any { it.name == "Import QA peaches" })
                assertFalse(source.exists())
                mark("result.json", JSONObject().put("status", "passed").put("modelRequests", calls).put("orientation", "applied")
                    .put("sourceMetadata", "removed").put("explicitReview", true).toString())
            }
        }
    }
}

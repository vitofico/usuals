package io.theficos.usuals.di

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.data.local.*
import io.theficos.usuals.llm.*
import io.theficos.usuals.parse.*
import io.theficos.usuals.smart.*
import io.theficos.usuals.ui.history.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepositoryReceiptStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun image(): ModelImage {
        val bitmap = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output); bitmap.recycle()
        return ModelImage("image/jpeg", output.toByteArray())
    }
    private inner class Fixture(access: () -> RecipeModelAccess? = { null }, budget: ModelBudget = ModelBudget(10)) : AutoCloseable {
        val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
        val items = ItemRepository(db)
        val history = HistoryRepository(db, items)
        val root = File(context.cacheDir, "receipts-${UUID.randomUUID()}")
        val files = ReceiptFiles(root)
        val imports = AndroidRecipeImportFiles(context, 4096)
        val store = RepositoryReceiptStore(db, history, files, imports, IngredientLineParser(), budget, access)
        val smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
        val archive = ReceiptHistoryStore(RepositoryHistoryStore(history, LocalUtteranceParser(), smart, io.theficos.usuals.ui.shopping.ShoppingToolsStore()), store, db, imports)
        suspend fun list(id: String) { db.listDao().insert(ListEntity(id, id)); ListRepository(db).select(id) }
        override fun close() { db.close(); root.deleteRecursively() }
    }
    @Test fun reviewed_receipt_creates_history_and_photo_without_changing_the_list() = runTest {
        Fixture().use { f ->
            f.list("groceries")
            f.items.add(ParsedItem("still needed"))
            val trip = f.store.save("groceries", null, image(), "2 milk\n500 g apples", 1_700_000_000_000)
            val snapshot = f.history.snapshot()
            assertThat(snapshot.trips.single().id).isEqualTo(trip)
            assertThat(snapshot.purchases.map { it.name }).containsExactly("milk", "apples")
            assertThat(snapshot.purchases.first { it.name == "apples" }.quantity!!.amount).isEqualTo(500.0)
            assertThat(f.items.items().single().name).isEqualTo("still needed")
            assertThat(f.store.ids("groceries")).containsExactly(trip)
            assertThat(f.store.load("groceries", trip)).isNotNull()
            f.store.save("groceries", trip, image(), null, 0)
            assertThat(f.history.snapshot().purchases).hasSize(2)
            f.store.remove("groceries", trip)
            assertThat(f.store.load("groceries", trip)).isNull()
            assertThat(f.history.snapshot().purchases).hasSize(2)
        }
    }
    @Test fun stale_scope_and_photo_write_failure_do_not_create_a_trip() = runTest {
        Fixture().use { f ->
            f.list("old"); f.list("new")
            var failure: Exception? = null
            try { f.store.save("old", null, image(), "milk", 0) } catch (e: Exception) { failure = e }
            assertThat((failure as ReceiptFailure).problem).isEqualTo(ReceiptProblem.SCOPE)
            assertThat(f.history.snapshot().trips).isEmpty()
            f.root.writeText("blocks the receipt directory")
            try { f.store.save("new", null, image(), "milk", 0) } catch (_: Exception) { }
            assertThat(f.history.snapshot().trips).isEmpty()
        }
    }
    @Test fun archive_round_trip_keeps_photos_and_invalid_receipt_is_rejected_before_import() = runTest {
        Fixture().use { source -> Fixture().use { destination ->
            source.list("groceries")
            val trip = source.store.save("groceries", null, image(), "milk", 1_700_000_000_000)
            val archive = source.archive.exportJson()
            destination.list("empty")
            destination.archive.importJson(archive)
            assertThat(destination.files.load("groceries", trip)).isNotNull()
            assertThat(destination.db.historyDao().purchases("groceries")).hasSize(1)
            val root = Json.parseToJsonElement(archive).jsonObject
            val photo = root.getValue("receipts").jsonArray.single().jsonObject
            val broken = JsonObject(root + ("receipts" to JsonArray(listOf(JsonObject(photo + ("tripId" to JsonPrimitive("missing"))))))).toString()
            var rejected = false
            try { destination.archive.importJson(broken) } catch (_: IllegalArgumentException) { rejected = true }
            assertThat(rejected).isTrue()
            assertThat(destination.db.historyDao().purchases("groceries")).hasSize(1)
        } }
    }
    @Test fun account_archive_photos_follow_the_imported_copy_and_never_the_live_account_list() = runTest {
        Fixture().use { source -> Fixture().use { destination ->
            source.list("account")
            val trip = source.store.save("account", null, image(), "milk", 1_700_000_000_000)
            source.db.listDao().update(source.db.listDao().find("account")!!.copy(synced = true))
            destination.list("account")
            destination.db.listDao().update(destination.db.listDao().find("account")!!.copy(synced = true))
            destination.archive.importJson(source.archive.exportJson())
            val copy = destination.db.listDao().all().single { it.archiveSource == "account" }
            assertThat(copy.id).isNotEqualTo("account")
            assertThat(destination.files.load(copy.id, trip)).isNotNull()
            assertThat(destination.files.load("account", trip)).isNull()
            assertThat(destination.db.historyDao().purchases("account")).isEmpty()
        } }
    }

    @Test fun changed_model_connection_after_probe_does_not_receive_the_receipt() = runTest {
        var current = true
        var calls = 0
        val client = object : LlmClient {
            override suspend fun probe() = ModelCapabilities(true, true, "qa")
            override suspend fun probeImages(): ModelCapabilities { current = false; return ModelCapabilities(true, true, "qa", supportsImages = true) }
            override suspend fun parseUtterance(utterance: String) = emptyList<ParsedItem>()
            override suspend fun parseImage(image: ModelImage): List<ParsedItem> { calls++; return listOf(ParsedItem("milk")) }
        }
        Fixture(access = { RecipeModelAccess(client, isCurrent = { current }) }).use { f ->
            var problem: ReceiptProblem? = null
            try { f.store.read(image()) } catch (failure: ReceiptFailure) { problem = failure.problem }
            assertThat(problem).isEqualTo(ReceiptProblem.SCOPE)
            assertThat(calls).isEqualTo(0)
        }
    }

    @Test fun unsupported_images_and_exhausted_budget_never_send_the_receipt() = runTest {
        var images = false
        var calls = 0
        val client = object : LlmClient {
            override suspend fun probe() = ModelCapabilities(true, true, "qa")
            override suspend fun probeImages() = ModelCapabilities(true, true, "qa", supportsImages = images)
            override suspend fun parseUtterance(utterance: String) = emptyList<ParsedItem>()
            override suspend fun parseImage(image: ModelImage): List<ParsedItem> { calls++; return listOf(ParsedItem("milk")) }
        }
        Fixture(access = { RecipeModelAccess(client) }, budget = ModelBudget(1)).use { f ->
            var problem: ReceiptProblem? = null
            try { f.store.read(image()) } catch (failure: ReceiptFailure) { problem = failure.problem }
            assertThat(problem).isEqualTo(ReceiptProblem.NO_IMAGES)
            images = true
            try { f.store.read(image()) } catch (failure: ReceiptFailure) { problem = failure.problem }
            assertThat(problem).isEqualTo(ReceiptProblem.BUDGET)
            assertThat(calls).isEqualTo(0)
        }
    }

    @Test fun atomic_backup_survives_pruning_and_recovers_after_an_interrupted_replace() {
        val root = File(context.cacheDir, UUID.randomUUID().toString())
        val files = ReceiptFiles(root)
        val original = image()
        try {
            files.save("list", "trip", original)
            val target = root.walkTopDown().single { it.isFile }
            val backup = File(target.path + ".bak")
            assertThat(target.renameTo(backup)).isTrue()
            assertThat(files.ids("list", setOf("trip"))).containsExactly("trip")
            files.prune(mapOf("list" to setOf("trip")))
            assertThat(files.has("list", "trip")).isTrue()
            assertThat(files.load("list", "trip")!!.bytes()).isEqualTo(original.bytes())
        } finally { files.clear() }
    }

    @Test fun scoped_files_cannot_leak_between_lists_and_clear_removes_all_copies() {
        val root = File(context.cacheDir, UUID.randomUUID().toString())
        val files = ReceiptFiles(root)
        files.save("list A/../../", "trip", image())
        assertThat(files.load("list B", "trip")).isNull()
        assertThat(root.listFiles()!!.single().canonicalFile.parentFile).isEqualTo(root.canonicalFile)
        files.clear()
        assertThat(root.exists()).isFalse()
    }
}

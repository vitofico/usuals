package io.theficos.usuals.di

import androidx.room.withTransaction
import io.theficos.usuals.data.local.UsualsDatabase
import io.theficos.usuals.llm.ModelImage
import io.theficos.usuals.ui.history.ScopedHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.util.Base64

/** The app's archive adds local photos; list sync and the Room schema stay unchanged. */
internal class ReceiptHistoryStore(private val delegate: ScopedHistoryStore, private val receipts: RepositoryReceiptStore,
    private val database: UsualsDatabase, private val images: AndroidRecipeImportFiles) : ScopedHistoryStore by delegate {
    override suspend fun exportJson(): String = withContext(Dispatchers.IO) { database.withTransaction {
        val root = Json.parseToJsonElement(delegate.exportJson()).jsonObject
        val result = JsonObject(root + mapOf("formatVersion" to JsonPrimitive(4), "receipts" to receipts.exportPhotos())).toString()
        require(result.toByteArray().size <= MAX_ARCHIVE_BYTES)
        result
    } }
    override suspend fun importJson(text: String) = withContext(Dispatchers.IO) {
        require(text.toByteArray().size <= MAX_ARCHIVE_BYTES)
        val root = Json.parseToJsonElement(text).jsonObject
        if (root["formatVersion"]?.jsonPrimitive?.intOrNull != 4) { delegate.importJson(text); return@withContext }
        val entries = root.getValue("receipts").jsonArray
        require(entries.size <= 500)
        val shared = root.getValue("lists").jsonArray.map { it.jsonObject }.filter { it["shared"]?.jsonPrimitive?.booleanOrNull == true }
            .map { it.getValue("id").jsonPrimitive.content }.toSet()
        val photos = entries.map { entry ->
            val value = entry.jsonObject
            val encoded = value.getValue("jpeg").jsonPrimitive.content
            require(encoded.length <= ModelImage.MAX_BYTES * 4 / 3 + 8)
            ArchivedReceipt(value.getValue("listId").jsonPrimitive.content, value.getValue("tripId").jsonPrimitive.content,
                images.preparePhoto(Base64.getDecoder().decode(encoded)), value.getValue("listId").jsonPrimitive.content in shared)
        }
        require(photos.map { it.scope to it.trip }.distinct().size == photos.size)
        val trips = root.getValue("trips").jsonArray.map { it.jsonObject }.map { it.getValue("listId").jsonPrimitive.content to it.getValue("id").jsonPrimitive.content }.toSet()
        require(photos.all { it.scope to it.trip in trips })
        val base = JsonObject(root - "receipts" + ("formatVersion" to JsonPrimitive(3))).toString()
        database.withTransaction { delegate.importJson(base); receipts.importPhotos(photos) }
    }
    companion object { const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024 }
}

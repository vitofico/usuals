package io.theficos.usuals.di

import androidx.room.withTransaction
import io.theficos.usuals.data.local.*
import io.theficos.usuals.llm.*
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.parse.IngredientLineParser
import io.theficos.usuals.ui.history.*
import io.theficos.usuals.ui.recipe.RecipeImportFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*
import java.util.Base64

internal class RepositoryReceiptStore(private val database: UsualsDatabase, private val history: HistoryRepository,
    private val files: ReceiptFiles, private val imports: RecipeImportFiles, private val parser: IngredientLineParser,
    private val budget: ModelBudget, private val access: () -> RecipeModelAccess?) : ReceiptStore {
    override val revision = MutableStateFlow(0)
    override suspend fun ids(scope: String): Set<String> = withContext(Dispatchers.IO) { database.withTransaction {
        val trips = database.historyDao().trips(scope).map { it.id }.toSet()
        files.ids(scope, trips)
    } }
    private suspend fun validTrips() = database.listDao().all().associate { list -> list.id to database.historyDao().trips(list.id).map { it.id }.toSet() }
    private suspend fun checkScope(scope: String, trip: String? = null) {
        if (database.listDao().selected() != scope || database.listDao().find(scope)?.archived != false ||
            (trip != null && database.historyDao().trips(scope).none { it.id == trip })) throw ReceiptFailure(ReceiptProblem.SCOPE)
    }
    override suspend fun load(scope: String, trip: String): ModelImage? = withContext(Dispatchers.IO) {
        database.withTransaction { checkScope(scope, trip); files.load(scope, trip) }
    }
    override suspend fun photo(uri: String) = imports.photo(uri)
    override fun cameraUri() = imports.cameraUri()
    override fun releaseCamera(uri: String) = imports.releaseCamera(uri)
    override suspend fun read(image: ModelImage): String {
        val selected = access() ?: throw ReceiptFailure(ReceiptProblem.NOT_CONFIGURED)
        fun current() { if (!selected.isCurrent()) throw ReceiptFailure(ReceiptProblem.SCOPE) }
        fun consume() { if (!selected.serverManaged && !budget.tryConsume()) throw ReceiptFailure(ReceiptProblem.BUDGET) }
        current(); consume()
        val capability = selected.client.probeImages()
        if (!capability.reachable || !capability.supportsImages || !capability.supportsStructuredOutput) throw ReceiptFailure(ReceiptProblem.NO_IMAGES)
        current(); consume()
        val rows = selected.client.parseImage(image)
        current()
        if (rows.isEmpty()) throw ReceiptFailure(ReceiptProblem.EMPTY)
        require(rows.size <= 500)
        // Editable input data, using unit tokens understood by the existing ingredient parser.
        return rows.joinToString("\n") { row -> row.quantity?.let { quantity ->
            val unit = when (quantity.unit) { MeasureUnit.PIECE -> ""; MeasureUnit.GRAM -> "g"; MeasureUnit.KILOGRAM -> "kg"
                MeasureUnit.MILLILITRE -> "ml"; MeasureUnit.LITRE -> "l"; MeasureUnit.PACK -> "pack" }
            listOf(java.math.BigDecimal.valueOf(quantity.amount).stripTrailingZeros().toPlainString(), unit, row.name).filter { it.isNotBlank() }.joinToString(" ")
        } ?: row.name }
    }
    override suspend fun save(scope: String, trip: String?, image: ModelImage, text: String?, date: Long): String = withContext(Dispatchers.IO) {
        val parsed = if (trip == null) {
            if (text == null || text.length > 30_000) throw ReceiptFailure(ReceiptProblem.EMPTY)
            val lines = text.lines().filter { it.isNotBlank() }
            require(lines.size <= 500)
            val rows = lines.map { parser.parse(it) ?: throw ReceiptFailure(ReceiptProblem.EMPTY) }
            if (rows.isEmpty()) throw ReceiptFailure(ReceiptProblem.EMPTY)
            rows
        } else emptyList()
        database.withTransaction {
            checkScope(scope, trip)
            val id = trip ?: requireNotNull(history.importPast(parsed, date)).trip.id
            try { files.save(scope, id, image) }
            catch (failure: Exception) { if (trip == null) files.remove(scope, id); throw failure }
            id
        }.also { revision.update { it + 1 } }
    }
    override suspend fun remove(scope: String, trip: String) = withContext(Dispatchers.IO) {
        database.withTransaction { checkScope(scope, trip); files.remove(scope, trip) }
        revision.update { it + 1 }
    }
    suspend fun exportPhotos(): JsonArray = withContext(Dispatchers.IO) { database.withTransaction {
        val valid = validTrips()
        files.prune(valid)
        buildJsonArray { valid.forEach { (scope, trips) -> trips.forEach { trip ->
            files.load(scope, trip)?.let { image -> add(buildJsonObject {
                put("listId", scope); put("tripId", trip); put("jpeg", Base64.getEncoder().encodeToString(image.bytes()))
            }) }
        } } }
    } }
    suspend fun importPhotos(photos: List<ArchivedReceipt>) {
        val lists = database.listDao().all()
        photos.forEach { photo ->
            val original = lists.firstOrNull { it.id == photo.scope }
            val destination = (if (photo.shared || original?.synced == true) lists.firstOrNull { it.archiveSource == photo.scope } else original)
                ?: throw IllegalArgumentException("receipt list")
            require(database.historyDao().trips(destination.id).any { it.id == photo.trip })
            // Never overwrite a receipt already held on this phone during an archive merge.
            if (!files.has(destination.id, photo.trip)) files.save(destination.id, photo.trip, photo.image)
        }
        revision.update { it + 1 }
    }
}
internal class ArchivedReceipt(val scope: String, val trip: String, val image: ModelImage, val shared: Boolean)

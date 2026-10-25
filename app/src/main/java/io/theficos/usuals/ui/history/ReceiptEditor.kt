package io.theficos.usuals.ui.history

import io.theficos.usuals.llm.ModelImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class ReceiptProblem { READ, SAVE, NOT_CONFIGURED, NO_IMAGES, BUDGET, EMPTY, DATE, SCOPE, MODEL }
class ReceiptFailure(val problem: ReceiptProblem) : Exception(problem.name)

data class ReceiptDraft(val id: String = UUID.randomUUID().toString(), val scope: String, val trip: String? = null,
    val date: String, val image: ModelImage? = null, val text: String? = null, val busy: Boolean = false,
    val saving: Boolean = false, val existing: Boolean = false, val problem: ReceiptProblem? = null)

interface ReceiptStore {
    val revision: StateFlow<Int>
    suspend fun ids(scope: String): Set<String>
    suspend fun load(scope: String, trip: String): ModelImage?
    suspend fun photo(uri: String): ModelImage
    fun cameraUri(): String?
    fun releaseCamera(uri: String)
    suspend fun read(image: ModelImage): String
    suspend fun save(scope: String, trip: String?, image: ModelImage, text: String?, date: Long): String
    suspend fun remove(scope: String, trip: String)
}

object NoReceiptStore : ReceiptStore {
    override val revision = MutableStateFlow(0)
    override suspend fun ids(scope: String) = emptySet<String>()
    override suspend fun load(scope: String, trip: String): ModelImage? = null
    override suspend fun photo(uri: String): ModelImage = throw ReceiptFailure(ReceiptProblem.READ)
    override fun cameraUri(): String? = null
    override fun releaseCamera(uri: String) = Unit
    override suspend fun read(image: ModelImage): String = throw ReceiptFailure(ReceiptProblem.NOT_CONFIGURED)
    override suspend fun save(scope: String, trip: String?, image: ModelImage, text: String?, date: Long): String = throw ReceiptFailure(ReceiptProblem.SAVE)
    override suspend fun remove(scope: String, trip: String) = Unit
}

/** Each asynchronous result belongs to one draft. Switching lists discards the editor. */
class ReceiptEditor(private val scope: CoroutineScope, private val store: ReceiptStore,
    private val today: () -> String, private val zone: ZoneId, private val saved: (String) -> Unit) {
    private val mutable = MutableStateFlow<ReceiptDraft?>(null)
    val state: StateFlow<ReceiptDraft?> = mutable
    private var job: Job? = null
    fun scopeChanged(selected: String) { if (mutable.value?.scope != selected) { job?.cancel(); mutable.value = null } }
    fun open(selected: String, trip: String? = null, date: String = today()) {
        job?.cancel()
        val draft = ReceiptDraft(scope = selected, trip = trip, date = date, busy = trip != null)
        mutable.value = draft
        if (trip != null) run(draft) { draft.copy(image = store.load(selected, trip), busy = false).let { it.copy(existing = it.image != null) } }
    }
    fun close() { if (mutable.value?.saving != true) { job?.cancel(); mutable.value = null } }
    fun date(value: String) { mutable.value?.takeUnless { it.busy }?.let { mutable.value = it.copy(date = value, problem = null) } }
    fun text(value: String) { if (value.length <= 30_000) mutable.value?.takeUnless { it.busy }?.let { mutable.value = it.copy(text = value, problem = null) } }
    fun cameraUri(): String? = try { store.cameraUri() } catch (_: Exception) { failed(ReceiptProblem.READ); null }
    fun releaseCamera(uri: String) = store.releaseCamera(uri)
    fun failed(problem: ReceiptProblem = ReceiptProblem.READ) { mutable.value = mutable.value?.copy(busy = false, saving = false, problem = problem) }
    fun photo(uri: String) {
        val draft = mutable.value?.takeUnless { it.busy } ?: return
        run(draft) {
            val image = try { store.photo(uri) } finally { store.releaseCamera(uri) }
            draft.copy(image = image, text = null, existing = false, busy = false)
        }
    }
    fun read() {
        val draft = mutable.value?.takeUnless { it.busy || it.trip != null } ?: return
        val image = draft.image ?: return
        run(draft) {
            val text = try { store.read(image) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: ReceiptFailure) { throw failure }
                catch (_: Exception) { throw ReceiptFailure(ReceiptProblem.MODEL) }
            draft.copy(text = text, busy = false)
        }
    }
    fun save() {
        val draft = mutable.value?.takeUnless { it.busy } ?: return
        val image = draft.image ?: return
        val date = if (draft.trip != null) 0L else try { LocalDate.parse(draft.date).also { require(!it.isAfter(LocalDate.parse(today()))) }.atStartOfDay(zone).toInstant().toEpochMilli() }
            catch (_: Exception) { failed(ReceiptProblem.DATE); return }
        run(draft.copy(saving = true)) {
            store.save(draft.scope, draft.trip, image, draft.text, date)
            saved(draft.scope)
            null
        }
    }
    fun remove() {
        val draft = mutable.value?.takeUnless { it.busy } ?: return
        val trip = draft.trip ?: return
        run(draft.copy(saving = true)) { store.remove(draft.scope, trip); null }
    }
    private fun run(draft: ReceiptDraft, action: suspend () -> ReceiptDraft?) {
        job?.cancel()
        mutable.value = draft.copy(busy = true, problem = null)
        job = scope.launch {
            try { val result = action(); currentCoroutineContext().ensureActive(); if (mutable.value?.id == draft.id) mutable.value = result }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (mutable.value?.id == draft.id) failed((failure as? ReceiptFailure)?.problem ?: if (draft.saving) ReceiptProblem.SAVE else ReceiptProblem.READ) }
        }
    }
}

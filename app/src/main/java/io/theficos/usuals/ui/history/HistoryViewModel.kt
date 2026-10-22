package io.theficos.usuals.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.usuals.model.HistorySnapshot
import io.theficos.usuals.ui.basket.BasketOverview
import io.theficos.usuals.ui.basket.BasketSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

enum class HistoryNotice { IMPORTED, EXPORTED, INVALID_FILE, INVALID_DATE, EMPTY_LIST, FAILED, REBOUGHT }
data class HistoryUiState(val history: HistorySnapshot = HistorySnapshot(), val notice: HistoryNotice? = null,
    val scope: String = "", val query: String = "", val selected: Set<String> = emptySet(), val added: Int = 0,
    /** Basket overview per trip id, empty while the overview is off. */
    val baskets: Map<String, BasketOverview> = emptyMap())

class HistoryViewModel(
    private val store: HistoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val basket: BasketSource? = null,
) : ViewModel() {
    private val notice = MutableStateFlow<HistoryNotice?>(null)
    private val query = MutableStateFlow("")
    private data class Selection(val scope: String = "", val ids: Set<String> = emptySet())
    private val selected = MutableStateFlow(Selection())
    private var rebuying = false
    private val added = MutableStateFlow(0)
    private class Sourced(val scope: String, val history: HistorySnapshot, val baskets: Map<String, BasketOverview>)
    private val source = (if (store is ScopedHistoryStore) store.observeShopping().map { it.listId to it.history }
        else store.observe().map { "" to it }).map { (scope, history) -> Sourced(scope, history, baskets(history)) }
    val state: StateFlow<HistoryUiState> = combine(source, notice, query, selected, added) { source, message, text, ids, count ->
        HistoryUiState(source.history, message, source.scope, text, ids.takeIf { it.scope == source.scope }?.ids.orEmpty(), count, source.baskets)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

    /** Read from the phone's saved product details only; a failed read leaves the trips without the section. */
    private suspend fun baskets(history: HistorySnapshot): Map<String, BasketOverview> {
        val source = basket?.takeIf { it.enabled() } ?: return emptyMap()
        return try { source.forTrips(history) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyMap() }
    }

    fun search(text: String) { query.value = text; selected.value = Selection() }
    fun select(id: String) {
        val current = state.value
        if (current.history.purchases.none { it.id.value == id }) return
        val ids = selected.value.takeIf { it.scope == current.scope }?.ids.orEmpty()
        selected.value = Selection(current.scope, if (id in ids) ids - id else ids + id)
    }
    fun selectTrip(id: String) {
        val matches = historyMatcher(query.value)
        selected.value = Selection(state.value.scope, state.value.history.purchases.filter { it.tripId == id && matches(it.name) }.map { it.id.value }.toSet())
    }
    fun rebuy() {
        val snapshot = state.value
        val rows = snapshot.history.purchases.filter { it.id.value in snapshot.selected }
        if (rows.isEmpty() || rebuying) return
        rebuying = true
        selected.value = Selection()
        notice.value = null
        viewModelScope.launch {
            try { added.value = store.rebuy(rows, snapshot.scope); notice.value = HistoryNotice.REBOUGHT }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice.value = HistoryNotice.FAILED }
            finally { rebuying = false }
        }
    }

    fun today(): String = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toString()
    fun dismissNotice() { notice.value = null }
    fun fileFailed() { notice.value = HistoryNotice.FAILED }

    suspend fun exportJson(): String = store.exportJson()
    fun exported() { notice.value = HistoryNotice.EXPORTED }

    suspend fun importJson(text: String) {
        notice.value = try {
            store.importJson(text)
            HistoryNotice.IMPORTED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            HistoryNotice.INVALID_FILE
        } catch (_: Exception) {
            HistoryNotice.FAILED
        }
    }

    fun importPast(text: String, date: String) {
        viewModelScope.launch {
            val parsedDate = try {
                LocalDate.parse(date).also { require(!it.isAfter(LocalDate.parse(today()))) }
            } catch (_: DateTimeParseException) {
                notice.value = HistoryNotice.INVALID_DATE
                return@launch
            } catch (_: IllegalArgumentException) {
                notice.value = HistoryNotice.INVALID_DATE
                return@launch
            }
            notice.value = try {
                val result = store.importPast(text, parsedDate.atStartOfDay(zone).toInstant().toEpochMilli())
                if (result == null) HistoryNotice.EMPTY_LIST else HistoryNotice.IMPORTED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                HistoryNotice.FAILED
            }
        }
    }
}

private val MARKS = Regex("\\p{M}+")

private fun folded(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
    .replace(MARKS, "").lowercase(java.util.Locale.ROOT)

/** Folds the query once: a search runs over every purchase on each keystroke. */
internal fun historyMatcher(query: String): (String) -> Boolean {
    val wanted = folded(query.trim())
    if (wanted.isEmpty()) return { true }
    return { name -> folded(name).contains(wanted) }
}

package io.theficos.comanda.ui.history

import io.theficos.comanda.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

class FakeHistoryStore : HistoryStore by NoHistoryStore {
    val data = MutableStateFlow(HistorySnapshot())
    var finishCalls = 0
    var requestedIds: Set<ItemId>? = null
    var gate: CompletableDeferred<Unit>? = null
    var fail = false
    var undone: String? = null
    var pasted: Pair<String, Long>? = null
    override fun observe() = data
    override suspend fun finish(onlyIds: Set<ItemId>?): FinishedTrip {
        finishCalls++
        requestedIds = onlyIds
        if (fail) throw IllegalStateException()
        gate?.await()
        return FinishedTrip(Trip("trip", 10_000), 1)
    }
    override suspend fun undo(tripId: String): Boolean { undone = tripId; return true }
    override suspend fun importPast(text: String, date: Long): FinishedTrip {
        pasted = text to date
        return FinishedTrip(Trip("past", date), 1)
    }
    override suspend fun importJson(text: String) { if (fail) throw IllegalArgumentException() }
}

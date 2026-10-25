package io.theficos.usuals.ui.list

import io.theficos.usuals.model.ItemVersion
import io.theficos.usuals.smart.ReviewCandidate

sealed interface ReviewAddResult {
    data class Added(val version: ItemVersion) : ReviewAddResult
    data object AlreadyPresent : ReviewAddResult
    data object Unavailable : ReviewAddResult
}

enum class ReviewProblem { SAVE, UNDO_CHANGED }
data class ReviewChoice(val index: Int, val added: ItemVersion? = null)

/** Only the session is transient. Accepted items are ordinary durable list rows. */
data class ReviewSession(
    val scope: String,
    val queue: List<ReviewCandidate>,
    val startedAt: Long = 0,
    val cursor: Int = 0,
    val limit: Int = minOf(10, queue.size),
    val added: Int = 0,
    val last: ReviewChoice? = null,
    val busy: Boolean = false,
    val problem: ReviewProblem? = null,
) {
    val current: ReviewCandidate? get() = queue.getOrNull(cursor)?.takeIf { cursor < limit }
    val hasMore: Boolean get() = limit < queue.size

    fun advance(version: ItemVersion? = null, undoable: Boolean = true) = copy(cursor = cursor + 1,
        added = added + if (version != null) 1 else 0,
        last = if (undoable) ReviewChoice(cursor, version) else null, busy = false, problem = null)

    fun undo() = copy(cursor = requireNotNull(last).index, added = added - if (last.added != null) 1 else 0,
        last = null, busy = false, problem = null)

    fun more() = copy(limit = minOf(limit + 10, queue.size), last = null, problem = null)

    fun reconcile(eligible: Set<String>): ReviewSession {
        if (busy) return this
        var next = cursor
        while (next < limit && queue[next].key !in eligible) next++
        return if (next == cursor) this else copy(cursor = next, problem = null)
    }
}

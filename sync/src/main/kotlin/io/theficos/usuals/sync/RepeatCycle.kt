package io.theficos.usuals.sync

import kotlinx.serialization.Serializable

/** A new cycle follows an explicit deletion and a later effective close, never a device clock. */
@Serializable
data class RepeatCycle(val previous: String, val deletion: String, val finish: String)

fun firstEffectiveFinishAfter(state: Projection, deletion: String): String? {
    val ordered = state.seen.keys.toList()
    val deletedAt = ordered.indexOf(deletion)
    if (deletedAt < 0) return null
    return ordered.drop(deletedAt + 1).firstOrNull { it in state.closes && it !in state.cancelledCloses }
}

/** Returns the next unconsumed cycle, or null while an occurrence is still present or deferred. */
fun nextRepeatCycle(state: Projection, list: String, key: String, rule: String, purchase: String): Pair<String, RepeatCycle?>? {
    val base = Reducer.occurrenceId(list, key, rule, purchase)
    val head = state.repeatHeads[base] ?: base
    val winner = state.occurrences[head] ?: return base to null
    val row = state.rows[winner] ?: return null
    val deletion = state.tombstones[winner] ?: return null
    if (state.seen[deletion]?.kind != "delete" || row.lifecycle != deletion) return null
    val finish = firstEffectiveFinishAfter(state, deletion) ?: return null
    val cycle = RepeatCycle(head, deletion, finish)
    return Reducer.occurrenceId(list, key, rule, purchase, cycle) to cycle
}

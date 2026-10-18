package io.theficos.usuals.enrich

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One computation per key for overlapping callers. Repeated scans share one
 * request and permit. The result is forgotten when the operation ends; persistent
 * answers and freshness belong to QualityCache. Failures are shared, never retained.
 */
class SingleFlight<K, V> {

    private val gate = Mutex()

    /** The flight in progress for each key, if any. Empty between flights, never stale. */
    private val inFlight = mutableMapOf<K, CompletableDeferred<V>>()

    /**
     * Returns the value for [key], running [block] only if nobody else already
     * is.
     *
     * The loop exists for one case: the caller who was running the block can be
     * cancelled, and the callers waiting on it have not been. Rather than
     * pushing somebody else's cancellation onto them, a survivor goes round
     * again and starts a flight of its own.
     */
    suspend fun run(key: K, block: suspend () -> V): V {
        while (true) {
            var leading = false
            val slot = gate.withLock {
                inFlight[key] ?: CompletableDeferred<V>().also { fresh ->
                    inFlight[key] = fresh
                    leading = true
                }
            }

            if (leading) return lead(key, slot, block)

            try {
                return slot.await()
            } catch (leaderGone: CancellationException) {
                // Either the leader walked away or this caller did, and the two
                // arrive here looking identical. ensureActive tells them apart:
                // our own cancellation is rethrown, somebody else's is not our
                // problem and we retry.
                currentCoroutineContext().ensureActive()
            }
        }
    }

    /**
     * Runs the block on behalf of everyone waiting on [slot], then hands them
     * the outcome.
     *
     * The bookkeeping is [NonCancellable] on purpose, and the reason is the
     * case that makes it necessary in the first place. If this caller is
     * cancelled mid-request, taking a mutex would throw before the key was
     * dropped, the slot would never be completed, and every follower would wait
     * for an answer nobody is left to produce. Freeing the key and completing
     * the slot is a few microseconds of uninterruptible work set against a
     * hang.
     *
     * [runCatching] swallowing a [CancellationException] is usually a mistake.
     * Here it is caught only long enough to release the key and is then
     * rethrown by [Result.getOrThrow], so the leader still ends up cancelled.
     */
    private suspend fun lead(key: K, slot: CompletableDeferred<V>, block: suspend () -> V): V {
        val outcome = runCatching { block() }

        withContext(NonCancellable) {
            gate.withLock { if (inFlight[key] === slot) inFlight.remove(key) }
        }

        outcome
            .onSuccess { value -> slot.complete(value) }
            .onFailure { failure -> slot.completeExceptionally(failure) }

        return outcome.getOrThrow()
    }
}

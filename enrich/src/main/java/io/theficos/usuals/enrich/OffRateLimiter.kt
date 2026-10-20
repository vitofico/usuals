package io.theficos.usuals.enrich

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The pace Open Food Facts asks for, kept honestly.
 *
 * OFF documents 15 product reads a minute and 10 searches a minute per IP, and
 * sends no RateLimit headers back, so there is nothing to discover at runtime.
 * The client has to pace itself from those two published numbers and be right
 * about it: the penalty for guessing high is not a slow app, it is an address
 * that stops being served, for everyone behind it.
 *
 * ## Why a rolling window rather than a bucket
 *
 * The obvious implementation is a bucket of fifteen tokens that refills once a
 * minute, and it quietly breaks the promise it appears to make. Spend all
 * fifteen in the last second of one minute, wait for the refill, spend fifteen
 * more in the first second of the next: thirty requests inside a two second
 * span, entirely legal according to the bucket and entirely over the limit
 * according to OFF, who counts the last sixty seconds from wherever they
 * happen to be standing. A fixed window only ever agrees with the server by
 * coincidence of where the boundaries fall.
 *
 * So this keeps the timestamp of every permit granted in the last
 * [windowMillis] and hands out another only while fewer than
 * [permitsPerWindow] of them are still inside it. Timestamps drop off the
 * front as they expire, which also bounds the memory at one entry per permit.
 *
 * Nothing spins. When the budget is gone the wait is computed exactly, as the
 * time until the oldest surviving permit leaves the window, and [sleep] is
 * asked for precisely that before the check runs again.
 *
 * ## Cold start, which this does not solve
 *
 * A freshly built limiter starts with an empty window, so it will hand out its
 * whole budget at once. That gap is deliberate and worth stating plainly:
 * nothing here survives process death, so a new instance genuinely does not
 * know what the previous one spent, and inventing a startup delay would be a
 * guess wearing the costume of safety. Somebody who force-stops and reopens
 * the app three times inside a minute can therefore exceed the documented
 * rate. The only real fix is persisting the window across restarts, and it is
 * not done here.
 *
 * Both the clock and the sleep are injected, so a test can exercise a full
 * minute of pacing without spending one.
 */
class OffRateLimiter(
    val permitsPerWindow: Int,
    val windowMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
) {

    init {
        require(permitsPerWindow >= 1) { "a budget of $permitsPerWindow permits grants nothing" }
        require(windowMillis > 0) { "a window needs a length: $windowMillis" }
    }

    private val gate = Mutex()

    /** When each live permit was granted, oldest first. Never longer than [permitsPerWindow]. */
    private val granted = ArrayDeque<Long>()

    /**
     * Epoch millis until which the server has told us to stop, or 0 for never.
     *
     * Atomic rather than guarded by [gate] because [noteRetryAfter] is not a
     * suspending function, and it is not one on purpose: see there.
     */
    private val blockedUntil = AtomicLong(0)

    /**
     * Runs [block] as soon as a permit is available, which may be right now.
     *
     * The permit is taken under the lock and the lock is released before
     * [block] starts. That ordering is the whole point. Holding it across the
     * request would turn a rate limiter into a queue of one, where a single
     * thirty second timeout stalls every other lookup behind it. Fifteen
     * requests a minute is a budget, not an instruction to send them one at a
     * time.
     *
     * The permit is spent even when [block] throws, because the budget counts
     * requests made and a request that failed was still made. Handing it back
     * on failure would let an outage turn into the burst that follows it.
     */
    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        return block()
    }

    /**
     * Records a server-imposed backoff that holds every permit until it passes,
     * on top of whatever the window says.
     *
     * This is the handler for an HTTP 429 carrying `Retry-After`: when the far
     * end names a time, that number outranks our own arithmetic. Treat it as a
     * contingency and not as measured behaviour. OFF has not been observed to
     * send `Retry-After` at all, and the only place this path has ever run is a
     * synthetic response in the tests, so nothing here is verified against the
     * live API.
     *
     * Deliberately not suspending, so the HTTP failure path can record the
     * backoff wherever it happens to be rather than waiting for a mutex it does
     * not otherwise need. Concurrent 429s keep the longest deadline rather than
     * the last one written, which is the conservative direction.
     */
    fun noteRetryAfter(millisFromNow: Long) {
        if (millisFromNow <= 0) return
        val until = clock() + millisFromNow
        blockedUntil.updateAndGet { current -> maxOf(current, until) }
    }

    private suspend fun acquire() {
        while (true) {
            val wait = gate.withLock { takePermitOrWait() }
            if (wait <= 0) return
            sleep(wait)
        }
    }

    /**
     * Either records a permit and returns 0, or returns how long to wait before
     * asking again.
     *
     * Runs under [gate] so that reading the window and spending it cannot be
     * split by another caller, which is the whole difference between a limit
     * and a suggestion. Time is only assumed to move forward: if the wall clock
     * is dragged backwards under us the wait is capped at one window, so the
     * worst case is over-waiting for a minute rather than for however far the
     * clock jumped.
     */
    private fun takePermitOrWait(): Long {
        val now = clock()

        // A clock that went backwards, which on a phone means an NTP correction
        // or somebody fixing a fast device. Every recorded grant is now in the
        // future, so nothing can ever expire and the deque stays full: the cap
        // below then hands out a one-window wait, over and over, for as long as
        // the jump was. An hour's correction became an hour of no enrichment,
        // while the comment above promised a minute.
        //
        // Rebasing the grants onto `now` restores exactly what that comment
        // says. The budget stays spent, so a backward step cannot be used to
        // mint a second window's worth of requests, and the window it is spent
        // over restarts from an instant that exists.
        if (granted.isNotEmpty() && granted.last() > now) {
            val spent = granted.size
            granted.clear()
            repeat(spent) { granted.addLast(now) }
        }

        while (granted.isNotEmpty() && now - granted.first() >= windowMillis) granted.removeFirst()

        val backoff = blockedUntil.get() - now
        if (backoff > 0) return minOf(backoff, MAX_BACKOFF_MILLIS)

        if (granted.size < permitsPerWindow) {
            granted.addLast(now)
            return 0
        }

        // The oldest grant is the first that will leave the window, and the
        // budget frees up at exactly that instant, not a tick later. The floor
        // of one millisecond is load-bearing rather than cosmetic: zero here
        // means "permit taken" to the caller, so a clock that stood still or
        // stepped backwards would otherwise hand out a permit it never
        // recorded.
        val untilOldestExpires = granted.first() + windowMillis - now
        return untilOldestExpires.coerceIn(1L, windowMillis)
    }

    companion object {
        /** OFF's documented ceiling for product reads from one address. */
        const val PRODUCT_READS_PER_MINUTE: Int = 15

        /** OFF's documented ceiling for searches, lower because a search costs them more. */
        const val SEARCHES_PER_MINUTE: Int = 10

        /** The window both of those ceilings are counted over. */
        const val ONE_MINUTE_MILLIS: Long = 60_000

        /**
         * A ceiling on any single wait, because a `Retry-After` is a number
         * from someone else's server and a malformed or hostile one should cost
         * us five minutes at worst, not an afternoon. The wait is re-checked
         * after it elapses, so a genuinely longer backoff is still honoured,
         * just in instalments.
         */
        const val MAX_BACKOFF_MILLIS: Long = 5L * ONE_MINUTE_MILLIS
    }
}

package io.theficos.comanda.enrich

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

/**
 * The pacing contract, asserted to the millisecond.
 *
 * The failure guarded against here is the quiet one. A limiter that looks like
 * it honours fifteen requests a minute, and in fact lets thirty through across
 * a window boundary, produces no error and no wrong answer: it produces a
 * blocked IP address a week later, for every user of the app behind it. The
 * boundary tests below are the ones that would fail a fixed-window
 * implementation, which is otherwise indistinguishable from this one.
 *
 * Nothing sleeps for real. The clock is a var and the fake sleep advances it,
 * so a full minute of pacing is asserted in microseconds.
 */
class OffRateLimiterTest {

    private var now = 0L
    private val waits = mutableListOf<Long>()

    private fun paced(permits: Int = 3, window: Long = 1_000) = OffRateLimiter(
        permitsPerWindow = permits,
        windowMillis = window,
        clock = { now },
        sleep = { millis ->
            waits += millis
            now += millis
        },
    )

    @Test
    fun `the whole documented budget goes out without waiting`() = runTest {
        val limiter = paced(
            permits = OffRateLimiter.PRODUCT_READS_PER_MINUTE,
            window = OffRateLimiter.ONE_MINUTE_MILLIS,
        )
        val grants = mutableListOf<Long>()

        repeat(OffRateLimiter.PRODUCT_READS_PER_MINUTE) { limiter.withPermit { grants += now } }

        assertThat(waits).isEmpty()
        assertThat(grants).hasSize(OffRateLimiter.PRODUCT_READS_PER_MINUTE)
        assertThat(grants.toSet()).containsExactly(0L)
    }

    @Test
    fun `the request past the budget waits for the oldest permit to expire, and no longer`() =
        runTest {
            val limiter = paced(permits = 3, window = 1_000)
            repeat(3) { limiter.withPermit { } }

            val grants = mutableListOf<Long>()
            limiter.withPermit { grants += now }

            assertThat(waits).containsExactly(1_000L)
            assertThat(grants).containsExactly(1_000L)
        }

    @Test
    fun `a permit frees up at the instant the oldest leaves the window, not a tick before`() =
        runTest {
            val limiter = paced(permits = 2, window = 1_000)
            limiter.withPermit { }
            limiter.withPermit { }

            now = 999 // one millisecond short of the first permit expiring
            limiter.withPermit { }

            assertThat(waits).containsExactly(1L)
            assertThat(now).isEqualTo(1_000L)
        }

    @Test
    fun `time already spent elsewhere counts, so an idle app never waits`() = runTest {
        val limiter = paced(permits = 2, window = 1_000)
        limiter.withPermit { }
        limiter.withPermit { }

        now = 5_000 // the person was reading the proposal screen

        limiter.withPermit { }
        limiter.withPermit { }

        assertThat(waits).isEmpty()
    }

    @Test
    fun `a budget spent at the end of one window does not refill at the start of the next`() =
        runTest {
            val limiter = paced(permits = 3, window = 1_000)
            now = 900
            repeat(3) { limiter.withPermit { } }

            // A fixed window of [0,1000) and [1000,2000) would call this a new
            // period and grant three more permits immediately, which is six
            // requests inside 101 ms and twice the rate OFF is counting.
            now = 1_000
            val grants = mutableListOf<Long>()
            limiter.withPermit { grants += now }

            assertThat(waits).containsExactly(900L)
            assertThat(grants).containsExactly(1_900L)
        }

    @Test
    fun `concurrent callers never exceed the budget in any window`() = runTest {
        val limiter = paced(permits = 3, window = 1_000)
        val grants = mutableListOf<Long>()

        coroutineScope {
            repeat(9) { launch { limiter.withPermit { grants += now } } }
        }

        assertThat(grants).hasSize(9)
        assertThat(busiestWindow(grants.sorted(), windowMillis = 1_000L)).isAtMost(3)
    }

    @Test
    fun `a permit does not serialize the request that follows it`() = runTest {
        val limiter = paced(permits = 4, window = 1_000)
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()

        coroutineScope {
            repeat(4) {
                launch {
                    limiter.withPermit {
                        val depth = inFlight.incrementAndGet()
                        peak.updateAndGet { seen -> maxOf(seen, depth) }
                        // Two suspension points, so a limiter holding its lock
                        // across the request would certainly show up here.
                        yield()
                        yield()
                        inFlight.decrementAndGet()
                    }
                }
            }
        }

        assertThat(peak.get()).isEqualTo(4)
    }

    @Test
    fun `a request that failed still spent its permit`() = runTest {
        val limiter = paced(permits = 2, window = 1_000)

        repeat(2) {
            runCatching { limiter.withPermit { throw IllegalStateException("off is down") } }
        }
        limiter.withPermit { }

        assertThat(waits).containsExactly(1_000L)
    }

    @Test
    fun `a server backoff holds every permit even while the window has room`() = runTest {
        val limiter = paced(permits = 3, window = 1_000)
        // Synthetic, as the KDoc says: OFF has not been seen to send Retry-After.
        limiter.noteRetryAfter(5_000)

        val grants = mutableListOf<Long>()
        limiter.withPermit { grants += now }

        assertThat(waits).containsExactly(5_000L)
        assertThat(grants).containsExactly(5_000L)
    }

    @Test
    fun `the longest server backoff survives a shorter one arriving after it`() = runTest {
        val limiter = paced()

        limiter.noteRetryAfter(5_000)
        limiter.noteRetryAfter(1_000)
        limiter.withPermit { }

        assertThat(waits).containsExactly(5_000L)
    }

    @Test
    fun `a backoff that has already elapsed stops nothing`() = runTest {
        val limiter = paced()
        limiter.noteRetryAfter(5_000)

        now = 5_000
        limiter.withPermit { }

        assertThat(waits).isEmpty()
    }

    @Test
    fun `a limiter needs a budget and a window`() {
        assertThat(
            runCatching { OffRateLimiter(0, 1_000, { now }, { _ -> }) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching { OffRateLimiter(1, 0, { now }, { _ -> }) }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    /** The most permits granted inside any [windowMillis] span. [grants] must be sorted. */
    private fun busiestWindow(grants: List<Long>, windowMillis: Long): Int =
        grants.maxOfOrNull { start ->
            grants.count { at -> at >= start && at - start < windowMillis }
        } ?: 0

    /**
     * A clock that goes backwards, which on a phone is an NTP correction or
     * somebody fixing a fast device, not a hypothetical.
     *
     * Every grant is then in the future, so nothing expires, the window stays
     * full, and the one-window cap hands out the same wait over and over for as
     * long as the jump lasted. An hour's correction became an hour with no
     * enrichment, while the limiter's own comment promised a minute. Rebasing
     * the spent grants onto the new now restores the promise without minting a
     * second window's worth of requests out of the jump.
     */
    @Test
    fun `a backward clock step costs one window, not the size of the jump`() = runTest {
        var now = 10_000_000L
        val waits = mutableListOf<Long>()
        val limiter = OffRateLimiter(
            permitsPerWindow = 2,
            windowMillis = 60_000,
            clock = { now },
            sleep = { millis ->
                waits += millis
                now += millis
            },
        )

        limiter.withPermit { }
        limiter.withPermit { }

        // An hour backwards. The budget is spent and must stay spent.
        now -= 3_600_000L
        limiter.withPermit { }

        assertThat(waits).containsExactly(60_000L)
    }
}

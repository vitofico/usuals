package io.theficos.comanda.enrich

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The collapsing contract, and the line it must not cross.
 *
 * Two failures are guarded against here, and they pull in opposite directions.
 * Sharing too little means six candidates carrying one barcode spend six of
 * the fifteen requests a minute the app is allowed, to learn one fact. Sharing
 * too much turns this into an accidental cache with no freshness rules: a key
 * left behind after its flight finished would serve a two-hour-old answer, or
 * worse, a two-hour-old outage, to a caller that asked for a live one.
 *
 * Nothing here sleeps. Callers are parked on an explicit gate so that "at the
 * same time" is a fact of the test rather than a matter of timing.
 */
class SingleFlightTest {

    private val flight = SingleFlight<String, String>()
    private val runs = AtomicInteger()
    private val gate = CompletableDeferred<Unit>()

    /** A lookup that has started and cannot finish until the test lets it. */
    private suspend fun blocked(value: String): String {
        runs.incrementAndGet()
        gate.await()
        return value
    }

    private suspend fun failing(): String {
        runs.incrementAndGet()
        gate.await()
        throw IllegalStateException("off is down")
    }

    @Test
    fun `callers overlapping on one key spend a single lookup between them`() = runTest {
        val first = async { flight.run(BARCODE) { blocked("nutri-score B") } }
        val second = async { flight.run(BARCODE) { blocked("nutri-score B") } }
        testScheduler.runCurrent() // both are now inside run, one of them leading

        gate.complete(Unit)

        assertThat(first.await()).isEqualTo("nutri-score B")
        assertThat(second.await()).isEqualTo("nutri-score B")
        assertThat(runs.get()).isEqualTo(1)
    }

    @Test
    fun `callers on different keys each get their own lookup`() = runTest {
        val one = async { flight.run(BARCODE) { blocked("uno") } }
        val two = async { flight.run(OTHER_BARCODE) { blocked("dos") } }
        testScheduler.runCurrent()

        gate.complete(Unit)

        assertThat(one.await()).isEqualTo("uno")
        assertThat(two.await()).isEqualTo("dos")
        assertThat(runs.get()).isEqualTo(2)
    }

    @Test
    fun `a later caller runs again instead of being handed a finished answer`() = runTest {
        gate.complete(Unit)

        val first = flight.run(BARCODE) { blocked("the old record") }
        val second = flight.run(BARCODE) { blocked("the corrected record") }

        assertThat(first).isEqualTo("the old record")
        assertThat(second).isEqualTo("the corrected record")
        assertThat(runs.get()).isEqualTo(2)
    }

    @Test
    fun `a failure reaches everyone waiting and is not remembered afterwards`() = runTest {
        val first = async { runCatching { flight.run(BARCODE) { failing() } } }
        val second = async { runCatching { flight.run(BARCODE) { failing() } } }
        testScheduler.runCurrent()

        gate.complete(Unit)

        assertThat(first.await().exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(second.await().exceptionOrNull()).hasMessageThat().contains("off is down")
        assertThat(runs.get()).isEqualTo(1)

        // The outage was shared, not stored: the next caller gets a real
        // attempt rather than a replay of somebody else's bad minute.
        assertThat(flight.run(BARCODE) { "nutri-score A" }).isEqualTo("nutri-score A")
    }

    @Test
    fun `a caller that walks away does not take the ones still waiting with it`() = runTest {
        val leader = async { flight.run(BARCODE) { blocked("nutri-score B") } }
        val follower = async { flight.run(BARCODE) { blocked("nutri-score B") } }
        testScheduler.runCurrent()

        // The screen that wanted this answer was closed. The other caller did
        // not close anything and is entitled to its answer.
        leader.cancel()
        testScheduler.runCurrent()
        gate.complete(Unit)

        assertThat(follower.await()).isEqualTo("nutri-score B")
        assertThat(runs.get()).isEqualTo(2)
    }

    private companion object {
        const val BARCODE = "8410000000000"
        const val OTHER_BARCODE = "8480000000000"
    }
}

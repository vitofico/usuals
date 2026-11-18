package io.theficos.usuals.capture

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.Gtin
import org.junit.Test

class ScanDebounceTest {

    /** A clock the test moves by hand, so no case here waits on real time. */
    private class FakeClock(var nowMillis: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = nowMillis
        fun advance(millis: Long) {
            nowMillis += millis
        }
    }

    private val leche = Gtin.parse("8480000123459")!!
    private val aceite = Gtin.parse("8410010101017")!!

    @Test
    fun `the first scan is always reported`() {
        val debounce = ScanDebounce(windowMillis = 2_000L, clock = FakeClock())

        assertThat(debounce.accept(leche)).isTrue()
    }

    /**
     * The failure this class exists to prevent. Thirty frames a second, one
     * carton held steady, one item on the list.
     */
    @Test
    fun `a code held in frame is reported once`() {
        val clock = FakeClock()
        val debounce = ScanDebounce(windowMillis = 2_000L, clock = clock)

        var reported = 0
        // Two seconds of frames at thirty a second.
        repeat(60) {
            if (debounce.accept(leche)) reported++
            clock.advance(33L)
        }

        assertThat(reported).isEqualTo(1)
    }

    /**
     * Working through a shopping bag: one product after another, no waiting.
     * A window that made the user pause between different items would be the
     * app fighting them.
     */
    @Test
    fun `a different code is reported immediately`() {
        val clock = FakeClock()
        val debounce = ScanDebounce(windowMillis = 2_000L, clock = clock)

        assertThat(debounce.accept(leche)).isTrue()
        clock.advance(50L)
        assertThat(debounce.accept(aceite)).isTrue()
        clock.advance(50L)
        assertThat(debounce.accept(leche)).isTrue()
    }

    @Test
    fun `the same code is reported again once the window has passed`() {
        val clock = FakeClock()
        val debounce = ScanDebounce(windowMillis = 2_000L, clock = clock)

        assertThat(debounce.accept(leche)).isTrue()
        clock.advance(1_999L)
        assertThat(debounce.accept(leche)).isFalse()
        clock.advance(2_000L)
        assertThat(debounce.accept(leche)).isTrue()
    }

    /**
     * The window slides from the last frame seen, not from the first. A carton
     * held in front of the lens for ten seconds must not pop a second item onto
     * the list two seconds in.
     */
    @Test
    fun `holding a code in frame keeps sliding the window`() {
        val clock = FakeClock()
        val debounce = ScanDebounce(windowMillis = 2_000L, clock = clock)

        assertThat(debounce.accept(leche)).isTrue()
        repeat(300) {
            clock.advance(33L)
            assertThat(debounce.accept(leche)).isFalse()
        }
    }

    @Test
    fun `two different codes each keep their own place`() {
        val clock = FakeClock()
        val debounce = ScanDebounce(windowMillis = 2_000L, clock = clock)

        debounce.accept(leche)
        clock.advance(10L)
        debounce.accept(aceite)
        clock.advance(10L)

        // Only the most recent code is remembered, deliberately: the scanner
        // reports what is in front of it now, and alternating between two
        // products is a person scanning two products.
        assertThat(debounce.accept(leche)).isTrue()
    }

    @Test
    fun `reset makes the next scan a first scan`() {
        val clock = FakeClock()
        val debounce = ScanDebounce(windowMillis = 2_000L, clock = clock)

        assertThat(debounce.accept(leche)).isTrue()
        assertThat(debounce.accept(leche)).isFalse()

        debounce.reset()

        assertThat(debounce.accept(leche)).isTrue()
    }

    @Test
    fun `an equal gtin from a separate parse is the same code`() {
        val clock = FakeClock()
        val debounce = ScanDebounce(windowMillis = 2_000L, clock = clock)

        assertThat(debounce.accept(Gtin.parse("8480000123459")!!)).isTrue()
        // A different instance, same canonical value. Debouncing on identity
        // rather than equality would report every frame.
        assertThat(debounce.accept(Gtin.parse("8480000123459")!!)).isFalse()
    }
}

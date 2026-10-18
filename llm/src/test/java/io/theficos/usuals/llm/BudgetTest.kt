package io.theficos.usuals.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/** UTC midnight at the start of the fixture day, derived rather than hard coded. */
private const val NOW: Long = 1_700_000_000_000L

private val MIDNIGHT = (NOW / DAY_MILLIS) * DAY_MILLIS

class ModelBudgetTest {

    @Test
    fun `a fresh budget has its whole ceiling available`() {
        val budget = ModelBudget(3, FrozenClock()::now)

        assertThat(budget.remaining()).isEqualTo(3)
        assertThat(budget.usedToday()).isEqualTo(0)
    }

    @Test
    fun `calls are consumed until the ceiling and then refused`() {
        val budget = ModelBudget(2, FrozenClock()::now)

        assertThat(budget.tryConsume()).isTrue()
        assertThat(budget.tryConsume()).isTrue()
        assertThat(budget.tryConsume()).isFalse()
        assertThat(budget.tryConsume()).isFalse()
        assertThat(budget.remaining()).isEqualTo(0)
        assertThat(budget.usedToday()).isEqualTo(2)
    }

    /**
     * quire's convention, kept deliberately: zero is off, not unlimited. An
     * app that reads a blank field as "no limit" is the app that surprises
     * somebody with a bill.
     */
    @Test
    fun `a ceiling of zero disables the model entirely`() {
        val budget = ModelBudget(0, FrozenClock()::now)

        assertThat(budget.tryConsume()).isFalse()
        assertThat(budget.remaining()).isEqualTo(0)
    }

    @Test
    fun `a negative ceiling is not a configuration`() {
        val failure = runCatching { ModelBudget(-1, FrozenClock()::now) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the allowance resets at UTC midnight and not before`() {
        val clock = FrozenClock(MIDNIGHT + DAY_MILLIS - 1)
        val budget = ModelBudget(2, clock::now)

        assertThat(budget.tryConsume()).isTrue()
        assertThat(budget.tryConsume()).isTrue()
        assertThat(budget.tryConsume()).isFalse()

        // One millisecond earlier, still yesterday.
        assertThat(budget.remaining()).isEqualTo(0)

        clock.current = MIDNIGHT + DAY_MILLIS
        assertThat(budget.remaining()).isEqualTo(2)
        assertThat(budget.usedToday()).isEqualTo(0)
        assertThat(budget.tryConsume()).isTrue()
    }

    @Test
    fun `hours passing inside one day do not reset anything`() {
        val clock = FrozenClock(MIDNIGHT)
        val budget = ModelBudget(1, clock::now)
        assertThat(budget.tryConsume()).isTrue()

        clock.current = MIDNIGHT + 23 * 60 * 60 * 1000

        assertThat(budget.tryConsume()).isFalse()
        assertThat(budget.usedToday()).isEqualTo(1)
    }

    @Test
    fun `the default ceiling is the one quire ships`() {
        assertThat(ModelBudget().dailyCallCeiling).isEqualTo(200)
    }
    @Test fun `saved usage survives reconstruction and ceiling changes do not refill it`() {
        var saved: ModelUsage? = null
        fun budget(limit: Int) = ModelBudget(limit, { NOW }, { saved }, { saved = it })
        val first = budget(2)
        assertThat(first.tryConsume()).isTrue()
        val reopened = budget(2)
        assertThat(reopened.usedToday()).isEqualTo(1)
        reopened.changeCeiling(1)
        assertThat(reopened.tryConsume()).isFalse()
        reopened.changeCeiling(3)
        assertThat(reopened.remaining()).isEqualTo(2)
    }

}

private class FrozenClock(var current: Long = NOW) {
    fun now(): Long = current
}

package io.theficos.usuals.llm

data class ModelUsage(val utcDay: Long, val calls: Int)

/** UTC daily attempt ceiling. Production persists usage before sending a request. */
class ModelBudget(
    dailyCallCeiling: Int = DEFAULT_DAILY_CALL_CEILING,
    private val clock: () -> Long = System::currentTimeMillis,
    loadUsage: () -> ModelUsage? = { null },
    private val saveUsage: (ModelUsage) -> Unit = {},
) {
    @Volatile var dailyCallCeiling: Int = dailyCallCeiling
        private set
    private val lock = Any()
    private val saved = loadUsage()
    private var day = saved?.utcDay ?: Long.MIN_VALUE
    private var used = saved?.calls?.coerceAtLeast(0) ?: 0

    init { require(dailyCallCeiling >= 0) }

    fun changeCeiling(value: Int) = synchronized(lock) {
        require(value >= 0)
        dailyCallCeiling = value
    }

    fun tryConsume(): Boolean = synchronized(lock) {
        rollOver()
        if (used >= dailyCallCeiling) return@synchronized false
        saveUsage(ModelUsage(day, used + 1))
        used++
        true
    }

    fun remaining(): Int = synchronized(lock) { rollOver(); (dailyCallCeiling - used).coerceAtLeast(0) }
    fun usedToday(): Int = synchronized(lock) { rollOver(); used }

    private fun rollOver() {
        val today = Math.floorDiv(clock(), MILLIS_PER_DAY)
        if (today != day) {
            saveUsage(ModelUsage(today, 0))
            day = today
            used = 0
        }
    }

    companion object {
        const val DEFAULT_DAILY_CALL_CEILING = 200
        const val DISABLED = 0
        private const val MILLIS_PER_DAY = 86_400_000L
    }
}

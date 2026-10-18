package io.theficos.comanda.ui.shopping

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

@Serializable
data class ReminderSettings(val enabled: Boolean = false, val day: Int = 6, val hour: Int = 9,
    val minute: Int = 0, val zoneId: String = "", val token: String = "", val nextAt: Long = 0,
    @kotlinx.serialization.Transient val unavailable: Boolean = false, val version: Int = 1) {
    fun validate() { require(day in 1..7 && hour in 0..23 && minute in 0..59) }
}

/** Wall-clock weekly scheduling also follows daylight-saving and timezone changes. */
fun nextReminder(settings: ReminderSettings, now: Long, zone: ZoneId): Long {
    settings.validate()
    val current = Instant.ofEpochMilli(now).atZone(zone)
    var date = current.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(settings.day)))
    var target = date.atTime(LocalTime.of(settings.hour, settings.minute)).atZone(zone)
    if (!target.toInstant().isAfter(current.toInstant())) {
        date = date.plusWeeks(1)
        target = date.atTime(LocalTime.of(settings.hour, settings.minute)).atZone(zone)
    }
    return target.toInstant().toEpochMilli()
}

/** Durable consent and revision checks prevent stale work from notifying after disable. */
class ReminderController(
    read: () -> String?, private val write: (String) -> Unit,
    private val enqueue: (ReminderSettings) -> Unit, private val cancel: () -> Unit,
    private val notify: () -> Unit, private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val token: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(runCatching {
        (read()?.let { json.decodeFromString<ReminderSettings>(it) } ?: ReminderSettings()).also {
            it.validate(); require(it.version == 1)
            if (it.enabled) { require(it.token.isNotBlank() && it.nextAt > 0); ZoneId.of(it.zoneId) }
        }
    }.getOrElse { ReminderSettings(unavailable = true) })
    val state = mutable.asStateFlow()
    private fun save(value: ReminderSettings) { write(json.encodeToString(value)); mutable.value = value }

    @Synchronized fun configure(enabled: Boolean, day: Int, hour: Int, minute: Int) {
        check(!mutable.value.unavailable)
        val next = ReminderSettings(enabled, day, hour, minute, zone().id, token()).also { it.validate() }
        save(next.copy(nextAt = if (enabled) nextReminder(next, clock(), zone()) else 0))
        cancel()
        if (enabled) enqueue(mutable.value)
    }
    @Synchronized fun reconcile() {
        val current = mutable.value
        if (current.unavailable) return
        if (current.enabled && current.zoneId != zone().id) configure(true, current.day, current.hour, current.minute)
        else if (current.enabled) enqueue(current) else cancel()
    }
    @Synchronized fun fire(expectedToken: String, expectedAt: Long): Boolean {
        val current = mutable.value
        if (!current.enabled || current.unavailable || current.token != expectedToken || current.nextAt != expectedAt) return true
        if (current.zoneId != zone().id) { configure(true, current.day, current.hour, current.minute); return true }
        if (clock() < expectedAt) return false
        // Persist consumption first. A process crash may skip a reminder, never duplicate it.
        save(current.copy(nextAt = nextReminder(current, clock(), zone())))
        enqueue(mutable.value)
        notify()
        return true
    }
}

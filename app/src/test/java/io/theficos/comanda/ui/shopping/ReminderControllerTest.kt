package io.theficos.comanda.ui.shopping

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderControllerTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun `weekly local time follows DST and never schedules in the past`() {
        val weekly = ReminderSettings(day = 7, hour = 9)
        assertThat(nextReminder(weekly, at("2026-10-24T10:00:00Z"), zone)).isEqualTo(at("2026-10-25T08:00:00Z"))
        assertThat(nextReminder(weekly, at("2026-10-25T08:00:00Z"), zone)).isEqualTo(at("2026-11-01T08:00:00Z"))
        assertThat(nextReminder(weekly.copy(hour = 2, minute = 30), at("2026-03-28T12:00:00Z"), zone)).isEqualTo(at("2026-03-29T01:30:00Z"))
    }
    @Test fun `consent survives restart and stale or duplicated work never notifies`() {
        var raw: String? = null
        var now = at("2026-10-24T10:00:00Z")
        var notices = 0
        val jobs = mutableListOf<ReminderSettings>()
        var counter = 0
        fun create() = ReminderController({ raw }, { raw = it }, jobs::add, {}, { notices++ }, { now }, { zone }, { (++counter).toString() })
        val first = create()
        assertThat(first.state.value.enabled).isFalse()
        first.configure(true, 7, 9, 0)
        val pending = jobs.last()
        val restarted = create()
        restarted.reconcile()
        assertThat(jobs.last()).isEqualTo(pending)
        now = pending.nextAt
        restarted.fire(pending.token, pending.nextAt)
        restarted.fire(pending.token, pending.nextAt)
        assertThat(notices).isEqualTo(1)
        val following = jobs.last()
        restarted.configure(false, 7, 9, 0)
        now = following.nextAt
        restarted.fire(following.token, following.nextAt)
        assertThat(notices).isEqualTo(1)
        assertThat(create().state.value.enabled).isFalse()
    }
    @Test fun `timezone changes replan and early execution retries without sending`() {
        var raw: String? = null
        var zone = ZoneId.of("Europe/Madrid")
        val now = at("2026-10-24T10:00:00Z")
        var sent = 0
        var sequence = 0
        val jobs = mutableListOf<ReminderSettings>()
        val controller = ReminderController({ raw }, { raw = it }, { jobs += it }, {}, { sent++ }, { now }, { zone }, { (++sequence).toString() })
        controller.configure(true, 7, 9, 0)
        val first = jobs.last()
        assertThat(controller.fire(first.token, first.nextAt)).isFalse()
        assertThat(sent).isEqualTo(0)
        zone = ZoneId.of("America/New_York")
        controller.reconcile()
        assertThat(jobs.last().nextAt).isEqualTo(at("2026-10-25T13:00:00Z"))
        controller.fire(first.token, first.nextAt)
        assertThat(sent).isEqualTo(0)
    }

    @Test fun `malformed preferences and failed writes cannot silently enable notifications`() {
        val broken = ReminderController({ "not json" }, { error("overwrite") }, {}, {}, {})
        assertThat(broken.state.value.unavailable).isTrue()
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { broken.configure(true, 1, 9, 0) }
        val failed = ReminderController({ null }, { error("disk full") }, {}, {}, {})
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { failed.configure(true, 1, 9, 0) }
        assertThat(failed.state.value.enabled).isFalse()
    }
}

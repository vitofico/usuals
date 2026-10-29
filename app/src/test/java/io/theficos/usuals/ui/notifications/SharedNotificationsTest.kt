package io.theficos.usuals.ui.notifications

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.*
import org.junit.Test

class SharedNotificationsTest {
    private var now = 100_000_000L
    private var raw: String? = null
    private var fails = false
    private var cancelled = 0
    private val notices = mutableListOf<SharedActivityNotice>()
    private fun controller() = SharedNotifications({ raw }, { if (fails) error("storage"); raw = it }, notices::add, { cancelled++ }, { now })
    private fun event(id: String, kind: String = "items_added", at: Long = now, count: Int = 1) = ListActivity(id, kind, count, at)
    private fun list(vararg events: ListActivity) = SharedList("list", now, "editor", Person("other", "Owner"),
        listOf(Member("me", "Me", "editor"), Member("other", "Other", "owner")), Projection(), activity = events.toList())
    private fun page(list: SharedList = list(), reset: Boolean = false) = ListChanges("cursor", reset, false, listOf(list), emptyList())
    private fun enabled(): SharedNotifications = controller().also { it.bind("account"); it.receive("account", page(), true); it.configure(true); now++ }

    @Test fun defaultsAndInitialOrResetSnapshotsNeverNotify() {
        val c = controller(); c.bind("account")
        assertThat(c.state.value.enabled).isFalse()
        c.receive("account", page(list(event("old"))), false)
        c.configure(true); now++
        c.receive("account", page(list(event("reset")), reset = true), false)
        c.receive("account", page(list(event("initial"))), true)
        assertThat(notices).isEmpty()
    }

    @Test fun groupsFreshKindsAndPersistsConsumptionAcrossRestart() {
        val c = enabled()
        val update = page(list(event("a", count = 3), event("b", "shop_finished")))
        c.receive("account", update, false)
        assertThat(notices).containsExactly(SharedActivityNotice("list", 3, 1))
        controller().receive("account", update, false)
        assertThat(notices).hasSize(1)
    }

    @Test fun disabledForegroundArchivedAndUnsharedActivityIsConsumed() {
        val c = enabled()
        c.configure(false); now++
        c.receive("account", page(list(event("disabled"))), false)
        c.configure(true); now++
        c.foreground(true)
        c.receive("account", page(list(event("visible"))), false)
        c.foreground(false)
        c.receive("account", page(list(event("archived")).copy(archived = true)), false)
        c.receive("account", page(list(event("alone")).copy(members = listOf(Member("me", "Me", "owner")))), false)
        assertThat(notices).isEmpty()
        c.receive("account", page(list(event("new"))), false)
        assertThat(notices).hasSize(1)
        assertThat(cancelled).isGreaterThan(0)
    }

    @Test fun categorySwitchesStartAtEnableTimeWithoutBackfill() {
        val c = enabled()
        c.configure(true, itemsAdded = false); now++
        c.receive("account", page(list(event("item"), event("shop", "shop_finished"))), false)
        assertThat(notices.single()).isEqualTo(SharedActivityNotice("list", 0, 1))
        c.configure(true, itemsAdded = true, shopsFinished = false)
        val before = now - 1; now++
        c.receive("account", page(list(event("oldItem", at = before), event("newItem"), event("newShop", "shop_finished"))), false)
        assertThat(notices.last()).isEqualTo(SharedActivityNotice("list", 1, 0))
    }

    @Test fun staleFutureUnknownAndInvalidEventsAreQuiet() {
        val c = enabled()
        c.receive("account", page(list(event("stale", at = now - 86_400_001), event("future", at = now + 300_001),
            event("unknown", "other"), event("zero", count = 0))), false)
        assertThat(notices).isEmpty()
    }

    @Test fun smallServerClockSkewIsToleratedAndIndependentChangesCannotUndoEachOther() {
        val c = enabled()
        c.receive("account", page(list(event("ahead", at = now + 1000))), false)
        assertThat(notices).hasSize(1)
        c.configure(itemsAdded = false)
        c.configure(shopsFinished = false)
        assertThat(c.state.value.itemsAdded).isFalse()
        assertThat(c.state.value.shopsFinished).isFalse()
        val account = c.account()
        c.configure(enabled = false)
        c.configure(itemsAdded = true)
        assertThat(c.state.value.enabled).isFalse()
        c.bind("new-account")
        assertThat(runCatching { c.configure(true, account = account) }.isFailure).isTrue()
        assertThat(c.state.value.enabled).isFalse()
    }

    @Test fun removalAndAccountChangesRequireNewBaselineAndOptIn() {
        val c = enabled()
        c.receive("account", ListChanges("next", false, false, emptyList(), listOf(RemovedList("list", now))), false)
        c.receive("account", page(list(event("reshared"))), false)
        assertThat(notices).isEmpty()
        c.bind("different-account")
        assertThat(c.state.value.enabled).isFalse()
        c.receive("different-account", page(list(event("foreign"))), false)
        assertThat(notices).isEmpty()
        c.bind(null)
        assertThat(c.available.value).isFalse()
    }

    @Test fun failedPersistenceCannotPostOrEnableAndMalformedStateStartsOff() {
        val c = enabled(); fails = true
        assertThat(runCatching { c.receive("account", page(list(event("a"))), false) }.isFailure).isTrue()
        assertThat(notices).isEmpty()
        fails = false; raw = "broken"
        val fresh = controller(); fresh.bind("account"); fails = true
        assertThat(runCatching { fresh.configure(true) }.isFailure).isTrue()
        assertThat(fresh.state.value.enabled).isFalse()
    }
}

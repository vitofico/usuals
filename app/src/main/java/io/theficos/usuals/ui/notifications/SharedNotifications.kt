package io.theficos.usuals.ui.notifications

import io.theficos.usuals.sync.ListChanges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SharedNotificationSettings(
    val enabled: Boolean = false,
    val itemsAdded: Boolean = true,
    val shopsFinished: Boolean = true,
    val itemsSince: Long = Long.MAX_VALUE,
    val shopsSince: Long = Long.MAX_VALUE,
)

@Serializable
private data class NotificationLedger(
    val account: String? = null,
    val settings: SharedNotificationSettings = SharedNotificationSettings(),
    val seen: Map<String, List<String>> = emptyMap(),
)

data class SharedActivityNotice(val list: String, val items: Int, val shops: Int)

/** Consumes before delivery: losing a notice is preferable to replaying shopping activity. */
class SharedNotifications(
    read: () -> String?,
    private val write: (String) -> Unit,
    private val deliver: (SharedActivityNotice) -> Unit,
    private val cancel: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var ledger = runCatching { read()?.let { json.decodeFromString<NotificationLedger>(it) } }.getOrNull() ?: NotificationLedger()
    private val mutable = MutableStateFlow(ledger.settings)
    val state = mutable.asStateFlow()
    private val bound = MutableStateFlow(ledger.account != null)
    val available = bound.asStateFlow()
    @Synchronized fun account(): String? = ledger.account
    private var visible = false

    private fun save(next: NotificationLedger) {
        write(json.encodeToString(next))
        ledger = next
        mutable.value = next.settings
        bound.value = next.account != null
    }

    @Synchronized fun bind(account: String?) {
        if (ledger.account == account) return
        cancel()
        save(NotificationLedger(account))
    }

    @Synchronized fun foreground(value: Boolean) {
        visible = value
        if (value) cancel()
    }

    @Synchronized fun configure(enabled: Boolean? = null, itemsAdded: Boolean? = null, shopsFinished: Boolean? = null, account: String? = ledger.account) {
        check(ledger.account != null && ledger.account == account)
        val previous = ledger.settings
        val nextEnabled = enabled ?: previous.enabled
        val nextItems = itemsAdded ?: previous.itemsAdded
        val nextShops = shopsFinished ?: previous.shopsFinished
        val now = clock()
        save(ledger.copy(settings = SharedNotificationSettings(nextEnabled, nextItems, nextShops,
            if (nextEnabled && nextItems && (!previous.enabled || !previous.itemsAdded)) now else previous.itemsSince,
            if (nextEnabled && nextShops && (!previous.enabled || !previous.shopsFinished)) now else previous.shopsSince)))
        // A category turned off must also remove already displayed activity of that category.
        if (!nextEnabled || !nextItems || !nextShops) cancel()
    }

    @Synchronized fun receive(account: String, page: ListChanges, baseline: Boolean) {
        bind(account)
        val settings = ledger.settings
        val now = clock()
        val seen = ledger.seen.toMutableMap()
        val notices = mutableListOf<SharedActivityNotice>()
        for (list in page.lists) {
            val previous = seen[list.id]
            seen[list.id] = list.activity.takeLast(64).map { it.id }
            if (baseline || page.reset || previous == null || visible || !settings.enabled || list.archived || list.members.size < 2) continue
            val fresh = list.activity.filter { it.id !in previous && it.at <= now + 5 * 60 * 1000L && it.at > now - MAX_AGE && it.count > 0 }
            val items = if (settings.itemsAdded) fresh.filter { it.kind == "items_added" && it.at > settings.itemsSince }.sumOf { it.count.coerceAtMost(100) } else 0
            val shops = if (settings.shopsFinished) fresh.count { it.kind == "shop_finished" && it.at > settings.shopsSince } else 0
            if (items > 0 || shops > 0) notices += SharedActivityNotice(list.id, items, shops)
        }
        page.removed.forEach { seen.remove(it.id) }
        // Bound storage even after years of lists being shared and removed between feed resets.
        save(ledger.copy(seen = seen.entries.toList().takeLast(256).associate { it.toPair() }))
        notices.forEach(deliver)
    }

    private companion object { const val MAX_AGE = 24 * 60 * 60 * 1000L }
}

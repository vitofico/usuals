package io.theficos.usuals.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ParsedItem
import kotlinx.coroutines.test.runTest
import org.junit.Test
import app.cash.turbine.test
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import io.theficos.usuals.sync.Intent
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalReplicaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun memory() = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).allowMainThreadQueries().build()
    @Test fun listSelectionScopesCaptureHistoryPreferencesAndAliases() = runTest {
        val db = memory()
        val lists = ListRepository(db) { "camping" }
        val items = ItemRepository(db, newId = { "same-item-id" })
        val history = HistoryRepository(db, items, newId = { "same-trip-id" })
        items.add(ParsedItem("Milk")); items.setChecked(ItemId("same-item-id"), true); history.finish()
        lists.create("Camping")
        assertThat(items.items()).isEmpty()
        assertThat(history.snapshot().purchases).isEmpty()
        items.add(ParsedItem("Bread")); items.setChecked(ItemId("same-item-id"), true); history.finish()
        history.savePreference(ItemPreference("bread", "PANTRY", muted = true))
        assertThat(history.snapshot().purchases.single().name).isEqualTo("Bread")
        // A fixture's original implicit scope can be selected once its manifest exists.
        db.listDao().insert(ListEntity(LOCAL_LIST_ID, ""))
        lists.select(LOCAL_LIST_ID)
        assertThat(history.snapshot().purchases.single().name).isEqualTo("Milk")
        assertThat(history.snapshot().preferences).isEmpty()
        assertThat(db.syncDao().pending("camping")).hasSize(4)
        db.close()
    }
    @Test fun processRestartKeepsUndoAndDurableIntentAndFailureRollsBackBoth() = runTest {
        context.deleteDatabase("replica-restart")
        fun open() = Room.databaseBuilder(context, UsualsDatabase::class.java, "replica-restart").allowMainThreadQueries().build()
        var db = open()
        var items = ItemRepository(db)
        var history = HistoryRepository(db, items)
        val milk = items.add(ParsedItem("Milk"))
        items.setChecked(milk, true)
        val trip = history.finish()!!
        db.close(); db = open(); items = ItemRepository(db); history = HistoryRepository(db, items)
        assertThat(history.undo(trip.trip.id)).isTrue()
        assertThat(items.items().single().id).isEqualTo(milk)
        val count = db.syncDao().pending(LOCAL_LIST_ID).size
        runCatching { db.withTransaction { items.add(ParsedItem("Discard me")); error("simulated process interruption before commit") } }
        assertThat(items.items().map { it.name }).containsExactly("Milk")
        assertThat(db.syncDao().pending(LOCAL_LIST_ID)).hasSize(count)
        db.close(); context.deleteDatabase("replica-restart")
    }
    @Test fun syncedTombstonesNeverExpireAndPrivateMutesStayOutOfIntents() = runTest {
        val db = memory()
        db.listDao().insert(ListEntity(LOCAL_LIST_ID, "Home", synced = true))
        var now = 10L
        val items = ItemRepository(db, clock = { now })
        val history = HistoryRepository(db, items)
        val milk = items.add(ParsedItem("Milk")); items.delete(milk)
        now += ItemRepository.FORTNIGHT_MILLIS + 1
        assertThat(items.reap()).isEqualTo(0)
        assertThat(db.itemDao().find(milk.value)).isNotNull()
        history.savePreference(ItemPreference("milk", "DAIRY", muted = true, snoozedUntil = 123))
        val intents = db.syncDao().pending(LOCAL_LIST_ID).joinToString { it.command }
        assertThat(intents).doesNotContain("muted")
        assertThat(intents).doesNotContain("snoozed")
        val preference = db.syncDao().pending(LOCAL_LIST_ID).map { Json.decodeFromString<Intent>(it.command) }.single { it.kind == "preference" }
        assertThat(preference.payload.getValue("changes").let { it as kotlinx.serialization.json.JsonObject }.keys)
            .containsExactly("category", "repeatEveryDays")
        db.close()
    }
    @Test fun formatOneBackwardsImportAndServerListArchiveBecomesIndependentLocalScope() = runTest {
        val db = memory()
        val items = ItemRepository(db)
        val history = HistoryRepository(db, items)
        history.importJson("""{"formatVersion":1,"items":[{"id":"a","rawText":"Milk","name":"Milk","normalizedName":"milk","createdAt":1,"updatedAt":1}]}""")
        assertThat(items.items().single().name).isEqualTo("Milk")
        db.listDao().insert(ListEntity(LOCAL_LIST_ID, "Home", synced = true))
        val archive = history.exportJson()
        val second = memory()
        val otherItems = ItemRepository(second)
        val otherHistory = HistoryRepository(second, otherItems)
        otherHistory.importJson(archive); otherHistory.importJson(archive)
        val restored = second.listDao().all().single()
        assertThat(restored.synced).isFalse()
        assertThat(restored.id).isNotEqualTo(LOCAL_LIST_ID)
        assertThat(second.itemDao().active(restored.id).single().id).isEqualTo("a")
        assertThat(second.syncDao().pending(restored.id).all { it.acknowledged }).isTrue()
        second.close(); db.close()
    }
    @Test fun shoppingSnapshotsNeverMixRowsAndHistoryAcrossSelection() = runTest {
        val db = memory()
        db.listDao().insert(ListEntity(LOCAL_LIST_ID, "Home"))
        val items = ItemRepository(db)
        val history = HistoryRepository(db, items)
        val lists = ListRepository(db) { "camping" }
        items.add(ParsedItem("Milk"))
        history.importPast(listOf(ParsedItem("Old bread")), 10)
        history.observeShopping().test {
            val home = awaitItem()
            assertThat(home.listId).isEqualTo(LOCAL_LIST_ID)
            assertThat(home.items.single().name).isEqualTo("Milk")
            assertThat(home.history.purchases.single().name).isEqualTo("Old bread")
            lists.create("Camping")
            var camping = awaitItem()
            while (camping.listId != "camping") camping = awaitItem()
            assertThat(camping.items).isEmpty()
            assertThat(camping.history.purchases).isEmpty()
            items.add(ParsedItem("Bread"))
            do { camping = awaitItem() } while (camping.items.isEmpty())
            assertThat(camping.listId).isEqualTo("camping")
            assertThat(camping.items.single().name).isEqualTo("Bread")
            assertThat(camping.history.purchases).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
        db.close()
    }
    @Test fun staleCheckedPurchaseEnablesRepeatAndUndoRetractsItAfterRestart() = runTest {
        context.deleteDatabase("repeat-restart")
        fun open() = Room.databaseBuilder(context, UsualsDatabase::class.java, "repeat-restart").allowMainThreadQueries().build()
        var now = 1000L
        var db = open()
        var items = ItemRepository(db, clock = { now })
        var history = HistoryRepository(db, items, clock = { now })
        val milk = items.add(ParsedItem("Milk"))
        history.savePreference(ItemPreference("milk", repeatEveryDays = 21))
        items.setChecked(milk, true)
        val before = items.items()
        now += 21 * 86_400_000L
        val close = history.finish()!!
        items.addRepeats(listOf("milk" to "Milk"), now)
        assertThat(items.items().single().id).isNotEqualTo(milk)
        db.close(); db = open(); items = ItemRepository(db, clock = { now }); history = HistoryRepository(db, items, clock = { now })
        assertThat(history.undo(close.trip.id)).isTrue()
        assertThat(items.items()).containsExactlyElementsIn(before)
        assertThat(history.snapshot().purchases).isEmpty()
        db.close(); context.deleteDatabase("repeat-restart")
    }
}

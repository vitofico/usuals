package io.theficos.usuals.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.sync.*
import kotlinx.coroutines.flow.first
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedReplicaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun memory() = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).allowMainThreadQueries().build()
    private suspend fun shared(db: UsualsDatabase) {
        db.listDao().insert(ListEntity(LOCAL_LIST_ID, LOCAL_HOUSEHOLD_ID, "Home", shared = true))
    }
    private fun checkpoint(state: Projection, seq: Long) = ListCheckpoint(seq, Wire.hash("head-$seq".toByteArray()), SnapshotCodec.encode(state))
    @Test fun sameIdDifferentSourceRemainsReviewableAndCannotBlockOtherUploads() = runTest {
        val db = memory(); shared(db)
        var ordinal = 0
        val replica = LocalReplica(db, newId = { "intent-${ordinal++}" })
        val items = ItemRepository(db, replica = replica)
        items.add(ParsedItem("Milk"))
        val source = replica.pending(LOCAL_LIST_ID).single().intent
        val foreign = source.copy(payload = JsonObject(source.payload + mapOf("name" to JsonPrimitive("Bread"), "key" to JsonPrimitive("bread"))))
        val authoritative = Reducer(NORMALIZER_VERSION).apply(Projection(), foreign).projection
        replica.confirm(LOCAL_LIST_ID, checkpoint(authoritative, 1), 1, "manifest")
        assertThat(db.syncDao().pending(LOCAL_LIST_ID).single().acknowledged).isFalse()
        assertThat(replica.pending(LOCAL_LIST_ID)).isEmpty()
        val conflict = replica.observeStatus(LOCAL_LIST_ID).first().conflicts.single()
        assertThat(conflict.incoming["name"]).isEqualTo("Milk")
        assertThat(items.items().single().name).isEqualTo("Bread")
        items.add(ParsedItem("Apples"))
        assertThat(replica.pending(LOCAL_LIST_ID).single().intent.id).isEqualTo("intent-1")
        val exported = Wire.json.parseToJsonElement(replica.exportPending()).jsonObject
        val commands = exported.getValue("lists").jsonArray.single().jsonObject.getValue("commands").jsonArray
        assertThat(commands.map { it.jsonObject.getValue("status").jsonPrimitive.content }).containsExactly("conflict", "pending")
        assertThat(commands.first().jsonObject.getValue("source").jsonObject.getValue("payload").jsonObject.getValue("name").jsonPrimitive.content).isEqualTo("Milk")
        replica.dismissConflict(source.id)
        assertThat(replica.exportPending()).doesNotContain("intent_collision")
        assertThat(replica.observeStatus(LOCAL_LIST_ID).first().conflicts).isEmpty()
        assertThat(replica.observeStatus(LOCAL_LIST_ID).first().pending).isEqualTo(1)
        db.close()
    }
    @Test fun encryptedRetryBytesAndUnacknowledgedSourceSurviveRestartUntilAuthenticatedCatchUp() = runTest {
        val name = "s4-replica-restart"; context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, UsualsDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open(); shared(db)
        var replica = LocalReplica(db)
        ItemRepository(db, replica = replica).add(ParsedItem("Milk"))
        val source = replica.pending(LOCAL_LIST_ID).single().intent
        val ciphertext = byteArrayOf(10, 20, 30)
        replica.saveEnvelope(LOCAL_LIST_ID, source, ciphertext)
        db.close(); db = open(); replica = LocalReplica(db)
        assertThat(replica.pending(LOCAL_LIST_ID).single().envelope).isEqualTo(ciphertext)
        val confirmed = Reducer(NORMALIZER_VERSION).apply(Projection(), source).projection
        replica.confirm(LOCAL_LIST_ID, checkpoint(confirmed, 1), 1, "manifest")
        assertThat(replica.pending(LOCAL_LIST_ID)).isEmpty()
        assertThat(db.syncDao().pending(LOCAL_LIST_ID).single().command).isEqualTo(Wire.json.encodeToString(Intent.serializer(), source))
        assertThat(db.syncDao().cursor(LOCAL_LIST_ID)!!.seq).isEqualTo(1)
        assertThat(ItemRepository(db).items()).hasSize(1)
        assertThat(SnapshotCodec.decode(replica.confirmed(LOCAL_LIST_ID)!!.checkpoint.payload)).isEqualTo(confirmed)
        db.close(); context.deleteDatabase(name)
    }
    @Test fun pinnedEditorAppliesDifferentFieldsButRetainsSameFieldConflict() = runTest {
        val db = memory(); shared(db)
        val items = ItemRepository(db)
        val id = items.add(ParsedItem("Milk", Quantity(1.0, MeasureUnit.LITRE)))
        val original = items.items().single().version!!
        items.setQuantity(id, Quantity(2.0, MeasureUnit.LITRE))
        assertThat(items.edit(id, "Oat milk", Quantity(1.0, MeasureUnit.LITRE), emptyList(), version = original)).isTrue()
        assertThat(items.items().single().quantity!!.amount).isEqualTo(2.0)
        assertThat(items.edit(id, "Whole milk", Quantity(1.0, MeasureUnit.LITRE), emptyList(), version = original)).isFalse()
        assertThat(items.items().single().name).isEqualTo("Oat milk")
        assertThat(LocalReplica(db).observeStatus(LOCAL_LIST_ID).first().conflicts.single().incoming["name"]).isEqualTo("Whole milk")
        db.close()
    }
    @Test fun pinnedCheckDeleteAndFinishPreserveNewerRowsWithoutDuplicatePurchases() = runTest {
        val db = memory(); shared(db)
        val items = ItemRepository(db)
        val history = HistoryRepository(db, items)
        val id = items.add(ParsedItem("Milk"))
        val open = items.items().single().version!!
        items.setChecked(id, true); items.setChecked(id, false)
        items.setChecked(id, true, open)
        assertThat(items.items().single().checked).isFalse()
        items.setChecked(id, true)
        val checked = items.items().single().version!!
        items.rename(id, "Oat milk")
        items.delete(id, checked)
        assertThat(items.items().single().name).isEqualTo("Oat milk")
        assertThat(history.finish(versions = listOf(checked))).isNull()
        assertThat(history.snapshot().purchases).isEmpty()
        val current = items.items().single().version!!
        val trip = history.finish(versions = listOf(current))!!
        assertThat(history.finish(versions = listOf(current))).isNull()
        assertThat(history.snapshot().purchases).hasSize(1)
        assertThat(history.undo(trip.trip.id)).isTrue()
        assertThat(history.snapshot().purchases).isEmpty()
        db.close()
    }
    @Test fun sharedCopyStartsIndependentGenesisAndHistoryRequiresExplicitChoice() = runTest {
        val db = memory()
        db.listDao().insert(ListEntity(LOCAL_LIST_ID, LOCAL_HOUSEHOLD_ID, "Home"))
        val items = ItemRepository(db); val history = HistoryRepository(db, items)
        history.importPast(listOf(ParsedItem("Old bread")), 10)
        items.add(ParsedItem("Milk")); history.savePreference(ItemPreference("milk", repeatEveryDays = 21, muted = true))
        val replica = LocalReplica(db)
        val lists = ListRepository(db) { "shared-copy" }
        lists.prepareShare("household", LOCAL_LIST_ID, false, replica)
        val copy = replica.projection("shared-copy")
        assertThat(copy.rows.values.single().facts["name"]!!.jsonPrimitive.content).isEqualTo("Milk")
        assertThat(copy.purchases).isEmpty(); assertThat(copy.seen).isEmpty()
        assertThat(copy.rows.values.single().revisions).isEmpty()
        assertThat(copy.rules["milk"]!!["revision"]!!.jsonPrimitive.content).isEqualTo("genesis")
        assertThat(db.syncDao().pending("shared-copy")).isEmpty()
        assertThat(db.historyDao().preferences("shared-copy").single().muted).isFalse()
        assertThat(db.historyDao().purchases(LOCAL_LIST_ID)).hasSize(1)
        assertThat(db.listDao().find(LOCAL_LIST_ID)!!.shared).isFalse()
        db.close()
    }
    @Test fun explicitBarcodeMergeKeepsChosenQuantityAndStalePreviewCannotDeleteNewerWork() = runTest {
        val db = memory(); shared(db)
        val items = ItemRepository(db)
        val milk = items.add(ParsedItem("Milk", Quantity(2.0, MeasureUnit.LITRE)))
        val scan = items.add(ParsedItem("4006381333931", Quantity(1.0, MeasureUnit.LITRE)), gtin = Gtin.parse("4006381333931"))
        var rows = items.items()
        val oldSource = rows.first { it.id == scan }.version!!
        val oldTarget = rows.first { it.id == milk }.version!!
        items.setQuantity(milk, Quantity(3.0, MeasureUnit.LITRE))
        assertThat(items.merge(oldSource, oldTarget)).isFalse()
        assertThat(items.items()).hasSize(2)
        rows = items.items()
        assertThat(items.merge(rows.first { it.id == scan }.version!!, rows.first { it.id == milk }.version!!)).isTrue()
        assertThat(items.items().single().id).isEqualTo(milk)
        assertThat(items.items().single().quantity!!.amount).isEqualTo(3.0)
        assertThat(db.historyDao().aliases(LOCAL_LIST_ID).single().fromKey).isEqualTo("gtin:4006381333931")
        assertThat(db.historyDao().purchases(LOCAL_LIST_ID)).isEmpty()
        db.close()
    }
    @Test fun revisionOnlyChangesRemainObservableThroughSnapshotEquality() = runTest {
        val db = memory(); shared(db)
        val items = ItemRepository(db, clock = { 10 })
        val history = HistoryRepository(db, items)
        val milk = items.add(ParsedItem("Milk"))
        history.observeShopping().test {
            val before = awaitItem()
            items.setChecked(milk, false)
            val after = awaitItem()
            assertThat(after.items).isEqualTo(before.items)
            assertThat(after.revisions).isNotEqualTo(before.revisions)
            assertThat(after).isNotEqualTo(before)
            cancelAndIgnoreRemainingEvents()
        }
        db.close()
    }

    @Test fun retainedCopyPreservesShoppingFactsAndLocalChoicesWithoutTransportLineage() = runTest {
        val db = memory(); shared(db)
        val replica = LocalReplica(db); val items = ItemRepository(db, replica = replica)
        val history = HistoryRepository(db, items)
        history.importPast(listOf(ParsedItem("Old bread")), 10)
        items.add(ParsedItem("Milk", Quantity(3.0, MeasureUnit.LITRE)))
        history.savePreference(ItemPreference("milk", category = "DAIRY", repeatEveryDays = 21, muted = true, snoozedUntil = 99))
        history.saveAlias(ItemAlias("milks", "milk"))
        var next = 0
        val lists = ListRepository(db) { "retained-${next++}" }
        val pending = db.syncDao().pending(LOCAL_LIST_ID)
        val copy = lists.retainCopy(LOCAL_LIST_ID, replica, "Copy 1 · Home")
        assertThat(lists.selected()).isEqualTo(copy)
        assertThat(lists.all().single { it.id == copy }.shared).isFalse()
        assertThat(items.items().single().name).isEqualTo("Milk")
        assertThat(items.items().single().quantity!!.amount).isEqualTo(3.0)
        assertThat(history.snapshot().purchases.single().name).isEqualTo("Old bread")
        assertThat(history.snapshot().preferences.single()).isEqualTo(ItemPreference("milk", category = "DAIRY", repeatEveryDays = 21, muted = true, snoozedUntil = 99))
        assertThat(history.snapshot().aliases.single()).isEqualTo(ItemAlias("milks", "milk"))
        assertThat(replica.projection(copy).seen).isEmpty()
        assertThat(db.syncDao().pending(copy)).isEmpty()
        assertThat(db.syncDao().pending(LOCAL_LIST_ID)).isEqualTo(pending)
        items.rename(items.items().single().id, "Local milk")
        lists.select(LOCAL_LIST_ID)
        assertThat(items.items().single().name).isEqualTo("Milk")
        db.close()
    }
    @Test fun removingOneSharedCopyDeletesOnlyItsScopedFactsAndPendingExportExcludesCiphertext() = runTest {
        val db = memory(); shared(db)
        val replica = LocalReplica(db); val items = ItemRepository(db, replica = replica)
        items.add(ParsedItem("Milk"))
        val intent = replica.pending(LOCAL_LIST_ID).single().intent
        replica.saveEnvelope(LOCAL_LIST_ID, intent, "private-envelope-marker".toByteArray())
        val exported = replica.exportPending()
        assertThat(exported).contains("Milk")
        assertThat(exported).doesNotContain("envelope")
        assertThat(exported).doesNotContain("private-envelope-marker")
        assertThat(exported).doesNotContain("session")
        val lists = ListRepository(db) { "independent" }
        val other = lists.create("Other")
        items.add(ParsedItem("Bread"))
        lists.select(LOCAL_LIST_ID)
        lists.removeLocalCopy(LOCAL_LIST_ID)
        assertThat(lists.selected()).isEqualTo(other)
        assertThat(items.items().single().name).isEqualTo("Bread")
        assertThat(db.listDao().find(LOCAL_LIST_ID)).isNull()
        assertThat(db.syncDao().pending(LOCAL_LIST_ID)).isEmpty()
        assertThat(db.syncDao().projection(LOCAL_LIST_ID)).isNull()
        assertThat(db.historyDao().allItems(LOCAL_LIST_ID)).isEmpty()
        assertThat(Wire.json.parseToJsonElement(replica.exportPending()).jsonObject.getValue("lists").jsonArray).isEmpty()
        db.close()
    }

}

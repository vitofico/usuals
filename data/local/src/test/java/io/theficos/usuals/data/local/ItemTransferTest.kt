package io.theficos.usuals.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ItemTransferTest {
    private lateinit var db: UsualsDatabase
    private lateinit var replica: LocalReplica
    private lateinit var items: ItemRepository
    private var next = 0
    private var seq = 0L
    private val target = "camping"

    @Before fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().build()
        replica = LocalReplica(db, newId = { "intent-${++next}" })
        items = ItemRepository(db, clock = { 1234L }, newId = { "row-${++next}" }, replica = replica)
        db.listDao().insert(ListEntity(LOCAL_LIST_ID, "Home"))
        db.listDao().insert(ListEntity(target, "Camping"))
    }
    @After fun close() = db.close()

    private suspend fun source(): ItemVersion {
        items.add(ParsedItem("Milk", Quantity(2.0, MeasureUnit.LITRE), "whole"), gtin = Gtin.parse("3017620422003"), store = "Market")
        items.savePreference(ItemPreferenceEntity("milk", category = "DAIRY", repeatEveryDays = 7))
        items.setChecked(items.items().single().id, true)
        return requireNotNull(items.items().single().version)
    }
    private suspend fun connectedDestination() {
        db.listDao().update(requireNotNull(db.listDao().find(target)).copy(synced = true, created = true, role = "editor"))
    }
    private suspend fun confirmTarget(change: (Projection) -> Projection = { it }) {
        val pending = replica.pendingIntents(target, 100)
        var state = replica.creation(target)
        for (intent in pending) state = Reducer(NORMALIZER_VERSION).apply(state, intent).projection
        replica.accept(SharedList(target, ++seq, "editor", Person("owner", "Owner"), emptyList(), change(state)), pending.map { it.id })
    }
    private suspend fun originalRemains(source: ItemVersion) {
        assertThat(db.itemDao().active(source.list).map { it.id }).contains(source.id)
        assertThat(db.syncDao().pending(source.list).map { Wire.json.decodeFromString<Intent>(it.command).kind }).doesNotContain("delete")
        assertThat(replica.projection(source.list).tombstones).doesNotContainKey(source.id)
    }

    @Test fun copyPreservesMetadataCreatesUncheckedIdentityAndNoPurchase() = runTest {
        val source = source()
        val original = items.items().single()
        assertThat(items.transfer(source, target, move = false)).isEqualTo(ItemTransferResult.COPIED)
        val copied = db.itemDao().active(target).single().toItem()
        assertThat(copied.id).isNotEqualTo(original.id)
        assertThat(copied.name).isEqualTo(original.name)
        assertThat(copied.quantity).isEqualTo(original.quantity)
        assertThat(copied.note).isEqualTo(original.note)
        assertThat(copied.gtin).isEqualTo(original.gtin)
        assertThat(copied.store).isEqualTo(original.store)
        assertThat(copied.checked).isFalse()
        assertThat(copied.checkedAt).isNull()
        assertThat(db.historyDao().preferences(target).single().category).isEqualTo("DAIRY")
        assertThat(db.historyDao().preferences(target).single().repeatEveryDays).isNull()
        assertThat(db.historyDao().purchases(source.list) + db.historyDao().purchases(target)).isEmpty()
        assertThat(db.listDao().selected()).isEqualTo(source.list)
        originalRemains(source)
    }

    @Test fun localMoveCommitsCopyAndRemovalTogetherWithoutPurchase() = runTest {
        val source = source()
        assertThat(items.transfer(source, target, move = true) { error("local move must stay offline") }).isEqualTo(ItemTransferResult.MOVED)
        assertThat(db.itemDao().active(source.list)).isEmpty()
        assertThat(db.itemDao().active(target)).hasSize(1)
        assertThat(db.historyDao().purchases(source.list) + db.historyDao().purchases(target)).isEmpty()
        assertThat(db.historyDao().preferences(source.list).single().snoozedUntil).isEqualTo(1234L)
    }

    @Test fun aFailedLocalRemovalRollsBackDestinationRowPreferenceAndOutbox() = runTest {
        val source = source()
        val before = db.syncDao().pending(source.list)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_move BEFORE INSERT ON items
            WHEN NEW.listId = '$LOCAL_LIST_ID' AND NEW.deletedAt IS NOT NULL
            BEGIN SELECT RAISE(ABORT, 'test removal failure'); END""")
        assertThat(runCatching { items.transfer(source, target, move = true) }.isFailure).isTrue()
        assertThat(db.itemDao().active(target)).isEmpty()
        assertThat(db.historyDao().preferences(target)).isEmpty()
        assertThat(db.syncDao().pending(target)).isEmpty()
        assertThat(db.syncDao().pending(source.list)).isEqualTo(before)
        originalRemains(source)
    }

    @Test fun existingDestinationRowIsKeptSeparateAndItsPreferencesWin() = runTest {
        val source = source()
        db.listDao().select(ListSelectionEntity(listId = target))
        items.add(ParsedItem("Milk"), gtin = Gtin.parse("3017620422003"))
        items.savePreference(ItemPreferenceEntity("milk", "OTHER", muted = true, snoozedUntil = 99, repeatEveryDays = 3, listId = target))
        db.listDao().select(ListSelectionEntity(listId = source.list))
        assertThat(items.transfer(source, target, move = false)).isEqualTo(ItemTransferResult.COPIED)
        assertThat(db.itemDao().active(target)).hasSize(2)
        val pref = db.historyDao().preferences(target).single()
        assertThat(pref.category).isEqualTo("OTHER")
        assertThat(pref.repeatEveryDays).isEqualTo(3)
        assertThat(pref.muted).isTrue()
        assertThat(pref.snoozedUntil).isEqualTo(99)
    }

    @Test fun destinationAliasesDetermineCopiedCategoryKey() = runTest {
        val source = source()
        items.saveAlias(ItemAliasEntity("gtin:3017620422003", "breakfast", target))
        assertThat(items.transfer(source, target, move = false)).isEqualTo(ItemTransferResult.COPIED)
        assertThat(replica.projection(target).rows.values.single().facts.string("key")).isEqualTo("breakfast")
        assertThat(db.historyDao().preferences(target).single().key).isEqualTo("breakfast")
    }

    @Test fun sourceCategoryUnderAnAliasIsCopiedFromItsEffectivePreference() = runTest {
        val original = source()
        items.saveAlias(ItemAliasEntity("milk", "dairy", original.list))
        val refreshed = requireNotNull(items.items().single().version)
        assertThat(items.transfer(refreshed, target, move = false)).isEqualTo(ItemTransferResult.COPIED)
        assertThat(db.historyDao().preferences(target).single().category).isEqualTo("DAIRY")
    }

    @Test fun destinationAliasPreferenceIsPreservedWithoutCreatingACanonicalOverride() = runTest {
        val source = source()
        items.saveAlias(ItemAliasEntity("milk", "dairy", target))
        items.savePreference(ItemPreferenceEntity("milk", category = "OTHER", repeatEveryDays = 3, muted = true, listId = target))
        assertThat(items.transfer(source, target, move = false)).isEqualTo(ItemTransferResult.COPIED)
        val preference = db.historyDao().preferences(target).single()
        assertThat(preference.key).isEqualTo("milk")
        assertThat(preference.category).isEqualTo("OTHER")
        assertThat(preference.repeatEveryDays).isEqualTo(3)
        assertThat(preference.muted).isTrue()
    }

    @Test fun editedSourceDoesNotWriteDestination() = runTest {
        val source = source()
        items.setQuantity(ItemId(source.id), Quantity.pieces(4), source)
        assertThat(items.transfer(source, target, move = true)).isEqualTo(ItemTransferResult.SOURCE_CHANGED)
        assertThat(db.itemDao().active(target)).isEmpty()
        originalRemains(source)
    }

    @Test fun changedSourceCategoryDoesNotWriteDestination() = runTest {
        val source = source()
        items.savePreference(ItemPreferenceEntity("milk", category = "OTHER", repeatEveryDays = 7))
        assertThat(items.transfer(source, target, move = false)).isEqualTo(ItemTransferResult.SOURCE_CHANGED)
        assertThat(db.itemDao().active(target)).isEmpty()
    }

    @Test fun switchedListCannotTransferAnOldSheet() = runTest {
        val source = source()
        db.listDao().select(ListSelectionEntity(listId = target))
        assertThat(items.transfer(source, target, move = true)).isEqualTo(ItemTransferResult.SOURCE_CHANGED)
        assertThat(db.itemDao().active(target)).isEmpty()
    }

    @Test fun archivedRemovedAndSameDestinationRejectBeforeWriting() = runTest {
        val source = source()
        assertThat(items.transfer(source, source.list, move = true)).isEqualTo(ItemTransferResult.DESTINATION_UNAVAILABLE)
        assertThat(items.transfer(source, "missing", move = true)).isEqualTo(ItemTransferResult.DESTINATION_UNAVAILABLE)
        db.listDao().update(requireNotNull(db.listDao().find(target)).copy(archived = true))
        assertThat(items.transfer(source, target, move = true)).isEqualTo(ItemTransferResult.DESTINATION_UNAVAILABLE)
        assertThat(db.itemDao().active(target)).isEmpty()
        originalRemains(source)
    }

    @Test fun syncedMoveDeletesOnlyAfterConfirmedExactDestination() = runTest {
        val source = source()
        connectedDestination()
        assertThat(items.transfer(source, target, move = true) {
            originalRemains(source)
            confirmTarget()
        }).isEqualTo(ItemTransferResult.MOVED)
        assertThat(db.itemDao().active(source.list)).isEmpty()
        assertThat(db.itemDao().active(target)).hasSize(1)
    }

    @Test fun unconfirmedSyncedDestinationKeepsSourceAndDurableCopyAfterReconstruction() = runTest {
        val source = source()
        connectedDestination()
        assertThat(items.transfer(source, target, move = true)).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        originalRemains(source)
        replica = LocalReplica(db)
        replica.accept(SharedList(target, ++seq, "editor", Person("owner", "Owner"), emptyList(), Projection()))
        assertThat(db.itemDao().active(target)).hasSize(1)
        assertThat(replica.pendingIntents(target, 100)).hasSize(2)
        originalRemains(source)
    }

    @Test fun acknowledgedRejectedImportDoesNotDeleteSource() = runTest {
        val source = source()
        connectedDestination()
        assertThat(items.transfer(source, target, move = true) {
            confirmTarget { state ->
                val imported = state.seen.values.single { it.kind == "import" }
                state.copy(rows = emptyMap(), rejected = mapOf(imported.id to "invalid"))
            }
        }).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        originalRemains(source)
    }

    @Test fun conflictingCategoryAcknowledgementDoesNotDeleteSource() = runTest {
        val source = source()
        connectedDestination()
        assertThat(items.transfer(source, target, move = true) {
            confirmTarget { state ->
                val preference = state.seen.values.single { it.kind == "preference" }
                state.copy(conflicts = mapOf(preference.id to Conflict(preference.payload, JsonObject(emptyMap()), "stale")))
            }
        }).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        originalRemains(source)
    }

    @Test fun discardedCapacityImportDoesNotDeleteSource() = runTest {
        val source = source()
        connectedDestination()
        assertThat(items.transfer(source, target, move = true) {
            replica.discard(target, replica.pendingIntents(target, 100).map { it.id })
        }).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        originalRemains(source)
    }

    @Test fun networkFailureAndCancellationKeepOriginalWithoutPendingDelete() = runTest {
        val source = source()
        connectedDestination()
        assertThat(items.transfer(source, target, move = true) { error("offline") }).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        originalRemains(source)
        val failed = runCatching { items.transfer(source, target, move = true) { throw CancellationException("interrupted") } }
        assertThat(failed.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        originalRemains(source)
    }

    @Test fun destinationDisappearingDuringSyncDoesNotDeleteSource() = runTest {
        val source = source()
        connectedDestination()
        assertThat(items.transfer(source, target, move = true) { replica.drop(target) }).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        originalRemains(source)
    }

    @Test fun sourceChangingAfterCopyDoesNotLoseTheChangedOriginal() = runTest {
        val source = source()
        connectedDestination()
        assertThat(items.transfer(source, target, move = true) {
            confirmTarget()
            items.setQuantity(ItemId(source.id), Quantity.pieces(5), source)
        }).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        originalRemains(source)
        assertThat(items.items().single().quantity).isEqualTo(Quantity.pieces(5))
    }

    @Test fun destinationArchivedAfterAcknowledgementDoesNotDeleteSource() = runTest {
        val source = source()
        connectedDestination()
        assertThat(items.transfer(source, target, move = true) {
            confirmTarget()
            replica.archive(target, true)
        }).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        originalRemains(source)
    }

    @Test fun sourceAliasChangeDoesNotWriteDestination() = runTest {
        val source = source()
        items.saveAlias(ItemAliasEntity("milk", "dairy", source.list))
        assertThat(items.transfer(source, target, move = true)).isEqualTo(ItemTransferResult.SOURCE_CHANGED)
        assertThat(db.itemDao().active(target)).isEmpty()
    }

    @Test fun targetChangedAfterAcknowledgementDoesNotDeleteSource() = runTest {
        val source = source()
        connectedDestination()
        assertThat(items.transfer(source, target, move = true) {
            confirmTarget()
            val copied = db.itemDao().active(target).single()
            val state = replica.projection(target)
            replica.submitTo(target, "delete", kotlinx.serialization.json.buildJsonObject {
                put("target", kotlinx.serialization.json.JsonPrimitive(copied.id))
                put("expected", kotlinx.serialization.json.buildJsonObject { put("lifecycle", kotlinx.serialization.json.JsonPrimitive(requireNotNull(state.rows[copied.id]?.lifecycle))) })
                put("deletedAt", kotlinx.serialization.json.JsonPrimitive(2000)); put("updatedAt", kotlinx.serialization.json.JsonPrimitive(2000))
            })
        }).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        originalRemains(source)
    }

    @Test fun accountRemovalDuringConfirmationCannotResurrectItsListsOrQueueADelete() = runTest {
        val source = source()
        connectedDestination()
        db.listDao().update(requireNotNull(db.listDao().find(source.list)).copy(synced = true, created = true, role = "owner"))
        assertThat(items.transfer(source, target, move = true) { replica.forgetAccount() }).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        assertThat(db.listDao().find(source.list)).isNull()
        assertThat(db.listDao().find(target)).isNull()
        assertThat(db.syncDao().pending(source.list)).isEmpty()
    }

    @Test fun sourceSyncedAndLocalDestinationStillRequiresServerConfirmation() = runTest {
        val source = source()
        db.listDao().update(requireNotNull(db.listDao().find(source.list)).copy(synced = true, created = true, role = "owner"))
        assertThat(items.transfer(source, target, move = true)).isEqualTo(ItemTransferResult.SOURCE_KEPT)
        originalRemains(source)
    }
}

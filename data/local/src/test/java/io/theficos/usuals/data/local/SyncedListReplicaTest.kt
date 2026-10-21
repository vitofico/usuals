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
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncedListReplicaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun memory() = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).allowMainThreadQueries().build()
    private suspend fun synced(db: UsualsDatabase, id: String = LOCAL_LIST_ID) {
        db.listDao().insert(ListEntity(id, "Home", synced = true, created = true))
    }
    private fun server(state: Projection, seq: Long, id: String = LOCAL_LIST_ID) =
        SharedList(id, seq, "owner", Person("me", "Me"), listOf(io.theficos.usuals.sync.Member("me", "Me", "owner")), state)
    private fun page(cursor: String, lists: List<SharedList> = emptyList(), removed: List<RemovedList> = emptyList(), reset: Boolean = false, more: Boolean = false) =
        ListChanges(cursor, reset, more, lists, removed)

    @Test fun sameIdDifferentSourceRemainsReviewableAndCannotBlockOtherUploads() = runTest {
        val db = memory(); synced(db)
        var ordinal = 0
        val replica = LocalReplica(db, newId = { "intent-${ordinal++}" })
        val items = ItemRepository(db, replica = replica)
        items.add(ParsedItem("Milk"))
        val source = replica.pendingIntents(LOCAL_LIST_ID, 100).single()
        val foreign = source.copy(payload = JsonObject(source.payload + mapOf("name" to JsonPrimitive("Bread"), "key" to JsonPrimitive("bread"))))
        val authoritative = Reducer(NORMALIZER_VERSION).apply(Projection(), foreign).projection
        replica.accept(server(authoritative, 1))
        assertThat(db.syncDao().pending(LOCAL_LIST_ID).single().acknowledged).isFalse()
        assertThat(replica.pendingIntents(LOCAL_LIST_ID, 100)).isEmpty()
        val conflict = replica.observeStatus(LOCAL_LIST_ID).first().conflicts.single()
        assertThat(conflict.incoming["name"]).isEqualTo("Milk")
        assertThat(items.items().single().name).isEqualTo("Bread")
        items.add(ParsedItem("Apples"))
        assertThat(replica.pendingIntents(LOCAL_LIST_ID, 100).single().id).isEqualTo("intent-1")
        replica.dismissConflict(source.id)
        assertThat(replica.observeStatus(LOCAL_LIST_ID).first().conflicts).isEmpty()
        assertThat(replica.observeStatus(LOCAL_LIST_ID).first().pending).isEqualTo(1)
        db.close()
    }
    @Test fun pendingSourceSurvivesRestartUntilTheServerAcceptsIt() = runTest {
        val name = "synced-replica-restart"; context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, UsualsDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open(); synced(db)
        var replica = LocalReplica(db)
        ItemRepository(db, replica = replica).add(ParsedItem("Milk"))
        val source = replica.pendingIntents(LOCAL_LIST_ID, 100).single()
        db.close(); db = open(); replica = LocalReplica(db)
        assertThat(replica.pendingIntents(LOCAL_LIST_ID, 100)).containsExactly(source)
        val confirmed = Reducer(NORMALIZER_VERSION).apply(Projection(name = "Home"), source).projection
        replica.accept(server(confirmed, 1), listOf(source.id))
        assertThat(replica.pendingIntents(LOCAL_LIST_ID, 100)).isEmpty()
        assertThat(db.syncDao().pending(LOCAL_LIST_ID).single().acknowledged).isTrue()
        assertThat(db.listDao().find(LOCAL_LIST_ID)!!.seq).isEqualTo(1)
        assertThat(ItemRepository(db).items()).hasSize(1)
        assertThat(replica.creation(LOCAL_LIST_ID)).isEqualTo(confirmed)
        db.close(); context.deleteDatabase(name)
    }
    @Test fun anOlderAnswerAcknowledgesWithoutReplacingANewerConfirmedList() = runTest {
        val db = memory(); synced(db)
        val replica = LocalReplica(db)
        val items = ItemRepository(db, replica = replica)
        items.add(ParsedItem("Milk"))
        val milk = replica.pendingIntents(LOCAL_LIST_ID, 100).single()
        val withMilk = Reducer(NORMALIZER_VERSION).apply(Projection(name = "Home"), milk).projection
        val newer = withMilk.copy(name = "Kitchen", nameRevision = "elsewhere")
        replica.accept(server(newer, 5))
        replica.accept(server(withMilk, 4), listOf(milk.id))
        assertThat(db.listDao().find(LOCAL_LIST_ID)!!.name).isEqualTo("Kitchen")
        assertThat(db.listDao().find(LOCAL_LIST_ID)!!.seq).isEqualTo(5)
        assertThat(replica.pendingIntents(LOCAL_LIST_ID, 100)).isEmpty()
        assertThat(items.items().single().name).isEqualTo("Milk")
        db.close()
    }
    @Test fun pinnedEditorAppliesDifferentFieldsButRetainsSameFieldConflict() = runTest {
        val db = memory(); synced(db)
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
        val db = memory(); synced(db)
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
    @Test fun explicitBarcodeMergeKeepsChosenQuantityAndStalePreviewCannotDeleteNewerWork() = runTest {
        val db = memory(); synced(db)
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
        val db = memory(); synced(db)
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
    @Test fun adoptingALocalListMakesItsShownStateTheCreationSnapshot() = runTest {
        val db = memory()
        db.listDao().insert(ListEntity(LOCAL_LIST_ID, "Home"))
        val replica = LocalReplica(db)
        val items = ItemRepository(db, replica = replica)
        items.add(ParsedItem("Milk"))
        val shown = replica.projection(LOCAL_LIST_ID)
        replica.adoptLocalLists()
        val list = db.listDao().find(LOCAL_LIST_ID)!!
        assertThat(list.synced).isTrue(); assertThat(list.created).isFalse()
        assertThat(replica.creation(LOCAL_LIST_ID)).isEqualTo(shown)
        assertThat(replica.pendingIntents(LOCAL_LIST_ID, 100)).isEmpty()
        assertThat(replica.outgoing().map { it.id }).containsExactly(LOCAL_LIST_ID)
        items.add(ParsedItem("Bread"))
        assertThat(replica.pendingIntents(LOCAL_LIST_ID, 100)).hasSize(1)
        assertThat(replica.creation(LOCAL_LIST_ID)).isEqualTo(shown)
        assertThat(replica.unsent()).isEqualTo(2)
        db.close()
    }
    @Test fun copyUnderANewIdKeepsFactsAndPrivateChoicesWithoutLineage() = runTest {
        val db = memory(); synced(db)
        db.listDao().select(ListSelectionEntity(listId = LOCAL_LIST_ID))
        var n = 0
        val replica = LocalReplica(db, newId = { "id-${n++}" }); val items = ItemRepository(db, replica = replica)
        val history = HistoryRepository(db, items)
        history.importPast(listOf(ParsedItem("Old bread")), 10)
        items.add(ParsedItem("Milk", Quantity(3.0, MeasureUnit.LITRE)))
        history.savePreference(ItemPreference("milk", category = "DAIRY", repeatEveryDays = 21, muted = true, snoozedUntil = 99))
        history.saveAlias(ItemAlias("milks", "milk"))
        val copy = replica.copyAsNew(LOCAL_LIST_ID)
        assertThat(copy).isNotEqualTo(LOCAL_LIST_ID)
        assertThat(db.listDao().selected()).isEqualTo(copy)
        assertThat(db.listDao().find(LOCAL_LIST_ID)).isNull()
        val entry = db.listDao().find(copy)!!
        assertThat(entry.synced).isTrue(); assertThat(entry.created).isFalse(); assertThat(entry.name).isEqualTo("Home")
        assertThat(items.items().single().quantity!!.amount).isEqualTo(3.0)
        assertThat(history.snapshot().purchases.single().name).isEqualTo("Old bread")
        assertThat(history.snapshot().preferences.single()).isEqualTo(ItemPreference("milk", category = "DAIRY", repeatEveryDays = 21, muted = true, snoozedUntil = 99))
        assertThat(history.snapshot().aliases.single()).isEqualTo(ItemAlias("milks", "milk"))
        assertThat(replica.creation(copy).seen).isEmpty()
        assertThat(db.syncDao().pending(copy)).isEmpty()
        assertThat(db.syncDao().pending(LOCAL_LIST_ID)).isEmpty()
        assertThat(db.historyDao().allItems(LOCAL_LIST_ID)).isEmpty()
        db.close()
    }
    @Test fun droppingOneListDeletesOnlyItsScopedFactsAndKeepsASelection() = runTest {
        val db = memory(); synced(db)
        val replica = LocalReplica(db, newId = { "fresh" }); val items = ItemRepository(db, replica = replica)
        items.add(ParsedItem("Milk"))
        val lists = ListRepository(db) { "independent" }
        val other = lists.create("Other")
        items.add(ParsedItem("Bread"))
        lists.select(LOCAL_LIST_ID)
        replica.drop(LOCAL_LIST_ID)
        assertThat(lists.selected()).isEqualTo(other)
        assertThat(items.items().single().name).isEqualTo("Bread")
        assertThat(db.listDao().find(LOCAL_LIST_ID)).isNull()
        assertThat(db.syncDao().pending(LOCAL_LIST_ID)).isEmpty()
        assertThat(db.syncDao().projection(LOCAL_LIST_ID)).isNull()
        assertThat(db.historyDao().allItems(LOCAL_LIST_ID)).isEmpty()
        replica.drop(other)
        assertThat(db.listDao().all().map { it.id }).containsExactly("fresh")
        assertThat(lists.selected()).isEqualTo("fresh")
        db.close()
    }
    @Test fun aFeedPageInsertsListsAndCommitsItsCursorAtomically() = runTest {
        val db = memory()
        val replica = LocalReplica(db)
        val groceries = Projection(name = "Groceries")
        replica.applyPage(page("1.4", listOf(server(groceries, 4, "groceries"))))
        assertThat(db.listDao().find("groceries")!!.let { listOf(it.synced, it.created, it.seq, it.name) }).containsExactly(true, true, 4L, "Groceries").inOrder()
        assertThat(replica.cursor()).isEqualTo("1.4")
        val broken = Projection(name = "Broken", purchases = mapOf("p" to buildJsonObject {
            put("id", "p"); put("tripId", "missing"); put("key", "milk"); put("name", "Milk"); put("boughtAt", 1)
        }))
        val failed = runCatching { replica.applyPage(page("1.9", listOf(server(Projection(name = "Camping"), 8, "camping"), server(broken, 9, "broken")))) }
        assertThat(failed.isFailure).isTrue()
        assertThat(db.listDao().find("camping")).isNull()
        assertThat(replica.cursor()).isEqualTo("1.4")
        db.close()
    }
    @Test fun removedEntriesDeleteLocallyAndTheHigherSeqWins() = runTest {
        val db = memory()
        val replica = LocalReplica(db)
        replica.applyPage(page("1.2", listOf(server(Projection(name = "A"), 1, "a"), server(Projection(name = "B"), 2, "b"))))
        replica.applyPage(page("1.5", listOf(server(Projection(name = "B again"), 5, "b")), listOf(RemovedList("a", 3), RemovedList("b", 4))))
        assertThat(db.listDao().find("a")).isNull()
        assertThat(db.listDao().find("b")!!.name).isEqualTo("B again")
        replica.applyPage(page("1.6", removed = listOf(RemovedList("b", 6))))
        assertThat(db.listDao().find("b")).isNull()
        db.close()
    }
    @Test fun aResetDropsSyncedListsItDidNotReturnButKeepsUncreatedOnes() = runTest {
        val db = memory()
        val replica = LocalReplica(db)
        replica.applyPage(page("1.3", listOf(server(Projection(name = "A"), 1, "a"), server(Projection(name = "B"), 2, "b"), server(Projection(name = "C"), 3, "c"))))
        db.listDao().insert(ListEntity("new", "New", synced = true))
        db.listDao().insert(ListEntity("local", "Local"))
        replica.applyPage(page("2.1", listOf(server(Projection(name = "A restored"), 1, "a")), reset = true, more = true))
        assertThat(db.listDao().find("b")).isNotNull()
        replica.applyPage(page("2.2", listOf(server(Projection(name = "C"), 2, "c"))))
        assertThat(db.listDao().all().map { it.id }).containsExactly("a", "c", "new", "local")
        assertThat(db.listDao().find("a")!!.name).isEqualTo("A restored")
        assertThat(db.syncDao().feed()).isEqualTo(FeedEntity(cursor = "2.2", resetting = false))
        db.close()
    }
    @Test fun signingInAgainOffersEveryListForCreationAndSigningOutForgetsTheAccount() = runTest {
        val db = memory()
        val replica = LocalReplica(db, newId = { "fresh" })
        replica.applyPage(page("1.1", listOf(server(Projection(name = "A"), 1, "a"))))
        db.listDao().select(ListSelectionEntity(listId = "a"))
        replica.prepareSignIn()
        assertThat(db.listDao().find("a")!!.created).isFalse()
        assertThat(replica.cursor()).isNull()
        assertThat(replica.outgoing().map { it.id }).containsExactly("a")
        replica.forgetAccount()
        assertThat(db.listDao().all()).containsExactly(ListEntity("fresh", ""))
        assertThat(db.listDao().selected()).isEqualTo("fresh")
        db.close()
    }
    @Test fun bothArchiveFlagsFromTheServerAreKeptAndAnArchivedSelectionMovesToAListThatIsNot() = runTest {
        val db = memory()
        val replica = LocalReplica(db)
        replica.applyPage(page("1.2", listOf(server(Projection(name = "A"), 1, "a"), server(Projection(name = "B"), 2, "b"))))
        db.listDao().select(ListSelectionEntity(listId = "b"))
        replica.applyPage(page("1.3", listOf(server(Projection(name = "B"), 3, "b").copy(archived = true, archivedByOwner = true))))
        assertThat(db.listDao().find("b")!!.let { it.archived to it.archivedByOwner }).isEqualTo(true to true)
        assertThat(db.listDao().selected()).isEqualTo("a")
        replica.accept(server(Projection(name = "B"), 4, "b"))
        assertThat(db.listDao().find("b")!!.let { it.archived to it.archivedByOwner }).isEqualTo(false to false)
        assertThat(db.listDao().selected()).isEqualTo("a")
        db.close()
    }
    @Test fun withEveryListArchivedTheSelectionIsOneFreshLocalListThatTheFeedKeeps() = runTest {
        val db = memory()
        var n = 0
        val replica = LocalReplica(db, newId = { "fresh-${n++}" })
        replica.applyPage(page("1.1", listOf(server(Projection(name = "A"), 1, "a"))))
        db.listDao().select(ListSelectionEntity(listId = "a"))
        replica.applyPage(page("1.2", listOf(server(Projection(name = "A"), 2, "a").copy(archived = true))))
        assertThat(db.listDao().all().map { it.id }).containsExactly("a", "fresh-0").inOrder()
        assertThat(db.listDao().selected()).isEqualTo("fresh-0")
        replica.applyPage(page("1.2"))
        replica.applyPage(page("1.2"))
        assertThat(db.listDao().all().map { it.id }).containsExactly("a", "fresh-0").inOrder()
        assertThat(db.listDao().selected()).isEqualTo("fresh-0")
        replica.drop("fresh-0")
        assertThat(db.listDao().all().map { it.id }).containsExactly("a", "fresh-1").inOrder()
        assertThat(db.listDao().selected()).isEqualTo("fresh-1")
        replica.applyPage(page("1.3", listOf(server(Projection(name = "A"), 3, "a"))))
        assertThat(db.listDao().all().map { it.id }).containsExactly("a")
        assertThat(db.listDao().selected()).isEqualTo("a")
        db.close()
    }
    @Test fun aLocalListIsArchivedAndRestoredOnThePhone() = runTest {
        val db = memory()
        val replica = LocalReplica(db, newId = { "fresh" })
        val lists = ListRepository(db) { "camping" }
        db.listDao().insert(ListEntity("home", "Home"))
        lists.create("Camping")
        replica.archive("camping", true)
        assertThat(db.listDao().find("camping")!!.archived).isTrue()
        assertThat(db.listDao().selected()).isEqualTo("home")
        assertThat(runCatching { lists.select("camping") }.isFailure).isTrue()
        replica.archive("camping", false)
        assertThat(db.listDao().find("camping")!!.archived).isFalse()
        assertThat(db.listDao().selected()).isEqualTo("home")
        replica.archive("camping", true)
        replica.adoptLocalLists()
        assertThat(db.listDao().find("camping")!!.let { it.synced to it.archived }).isEqualTo(true to true)
        assertThat(db.listDao().find(replica.copyAsNew("camping"))!!.archived).isTrue()
        db.close()
    }
    @Test fun renamingAListIsAnIntentThatUpdatesItsName() = runTest {
        val db = memory(); synced(db)
        val replica = LocalReplica(db)
        replica.rename(LOCAL_LIST_ID, "Weekly")
        assertThat(db.listDao().find(LOCAL_LIST_ID)!!.name).isEqualTo("Weekly")
        assertThat(replica.pendingIntents(LOCAL_LIST_ID, 100).single().kind).isEqualTo("list_name")
        db.close()
    }
    @Test fun discardedIntentsStopBeingPendingAndLeaveTheScreen() = runTest {
        val db = memory(); synced(db)
        val replica = LocalReplica(db)
        val items = ItemRepository(db, replica = replica)
        items.add(ParsedItem("Milk"))
        val milk = replica.pendingIntents(LOCAL_LIST_ID, 100).single()
        replica.discard(LOCAL_LIST_ID, listOf(milk.id))
        assertThat(replica.pendingIntents(LOCAL_LIST_ID, 100)).isEmpty()
        assertThat(items.items()).isEmpty()
        db.close()
    }
}

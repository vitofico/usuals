package io.theficos.usuals.di

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.data.local.*
import io.theficos.usuals.model.NORMALIZER_VERSION
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.sync.*
import io.theficos.usuals.ui.list.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone's sync engine against an in-memory server that applies intents with the shared reducer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ListSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).allowMainThreadQueries().build()
    private var ids = 0
    private val replica = LocalReplica(db, newId = { "id-${ids++}" })
    private val lists = ListRepository(db) { "list-${ids++}" }
    private val items = ItemRepository(db, replica = replica)
    private val state = MemoryState()
    private val server = FakeServer()
    /** Logging out never reaches a server here; signing out clears the credential regardless. */
    private val connections = ConnectionClient(state, object : RelayTransport {
        override suspend fun request(origin: String, method: String, path: String, body: ByteArray?, token: String?, maxResponse: Int): WireResponse =
            throw RelayProblem("offline")
    })
    private var forgotten = 0
    private val sync = ListSync(lists, replica, server, connections, state, unnamed = "Home", onForgotten = { forgotten++ })

    @After fun close() = db.close()

    private suspend fun signIn(user: String = "me") {
        server.me = user
        state.state = ClientState(connection = ServerConnection("https://usuals.example.org", InstanceInfo(name = "Usuals", server = ServerInfo(Wire.hash(byteArrayOf(1)), Wire.hash(byteArrayOf(2))),
            accounts = true, ai = false), user, "token-$user-${ids++}", Long.MAX_VALUE))
        sync.connected()
    }
    private suspend fun names(list: String) = db.historyDao().allItems(list).filter { it.deletedAt == null }.map { it.name }.sorted()
    private suspend fun add(list: String, name: String) { lists.select(list); items.add(ParsedItem(name)) }
    private suspend fun pending(list: String) = replica.pendingIntents(list, 1000).size

    @Test fun signingInUploadsEveryLocalListAsItIsShown() = runTest {
        val home = lists.create("Home"); add(home, "Milk")
        val camping = lists.create("Camping"); add(camping, "Tent"); add(camping, "Stove")
        signIn()
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.calls.filter { it.startsWith("PUT") }).containsExactly("PUT $home", "PUT $camping").inOrder()
        assertThat(server.names(camping)).containsExactly("Stove", "Tent")
        assertThat(server.lists.getValue(home).projection).isEqualTo(replica.projection(home))
        assertThat(db.listDao().all().map { it.synced to it.created }.distinct()).containsExactly(true to true)
        assertThat(replica.unsent()).isEqualTo(0)
    }

    @Test fun aListWithoutANameIsUploadedUnderTheNameThePhoneShows() = runTest {
        db.listDao().insert(ListEntity("first", "")); add("first", "Eggs")
        signIn()
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.lists.getValue("first").projection.name).isEqualTo("Home")
        assertThat(db.listDao().find("first")?.name).isEqualTo("Home")
    }

    @Test fun aPhoneWithOnlyItsUnusedStartingListShowsTheAccountsListsAndUploadsNothing() = runTest {
        db.listDao().insert(ListEntity("blank", "")); lists.select("blank")
        val market = server.seed("Market")
        signIn()
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.lists.keys).containsExactly(market)
        assertThat(db.listDao().all().map { it.id }).containsExactly(market)
        assertThat(db.listDao().selected()).isEqualTo(market)
    }

    @Test fun theUnusedStartingListUploadsOnceSomethingIsAddedToIt() = runTest {
        db.listDao().insert(ListEntity("blank", "")); lists.select("blank")
        signIn()
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.lists).isEmpty()
        val waiting = db.syncDao().observeOutgoing().first()
        add("blank", "Eggs")
        assertThat(db.syncDao().observeOutgoing().first()).isNotEqualTo(waiting)
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.names("blank")).containsExactly("Eggs")
    }

    @Test fun aListTheServerCannotTakeDoesNotHoldBackTheListsAfterIt() = runTest {
        val broken = lists.create("Broken"); add(broken, "Milk")
        val home = lists.create("Home"); add(home, "Bread")
        server.invalid = broken
        signIn()
        assertThat(sync.sync()).isEqualTo(SyncStatus.CAPACITY)
        assertThat(server.names(home)).containsExactly("Bread")
        assertThat(server.lists).doesNotContainKey(broken)
    }

    @Test fun pendingChangesStayOnTopOfTheConfirmedListUntilTheServerTakesThem() = runTest {
        val home = lists.create("Home")
        signIn(); sync.sync()
        server.capture(home, "Bread")
        add(home, "Milk")
        assertThat(sync.pull()).isEqualTo(SyncStatus.SYNCED)
        assertThat(names(home)).containsExactly("Bread", "Milk")
        assertThat(pending(home)).isEqualTo(1)
        assertThat(server.names(home)).containsExactly("Bread")
        assertThat(sync.push()).isEqualTo(SyncStatus.SYNCED)
        assertThat(pending(home)).isEqualTo(0)
        assertThat(server.names(home)).containsExactly("Bread", "Milk")
        assertThat(db.syncDao().projection(home, optimistic = true)).isEqualTo(db.syncDao().projection(home, optimistic = false))
        assertThat(db.listDao().find(home)!!.seq).isEqualTo(server.lists.getValue(home).seq)
    }

    @Test fun aListPastThePhonesTwoMegabyteRowLimitSyncsBothWays() = runTest {
        val home = server.seed("Home")
        server.lists.getValue(home).projection = Projection(name = "Home",
            trips = (0 until 300).associate { "trip-$it" to buildJsonObject { put("closedAt", it * 1_000L) } },
            purchases = (0 until 2_500).associate { i -> "purchase-$i" to buildJsonObject {
                put("id", "purchase-$i"); put("tripId", "trip-${i % 300}"); put("key", "café-$i"); put("name", "Café crème 🥐 ".repeat(80) + i)
                put("quantity", JsonNull); put("unit", JsonNull); put("gtin", JsonNull)
                put("boughtAt", i.toLong()); put("fromSuggestion", false); put("normalizerVersion", NORMALIZER_VERSION)
            } })
        signIn()
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        server.capture(home, "Bread")
        add(home, "Milk")
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.names(home)).containsExactly("Bread", "Milk")
        assertThat(names(home)).containsExactly("Bread", "Milk")
        assertThat(replica.projection(home)).isEqualTo(server.lists.getValue(home).projection)
        assertThat(db.historyDao().purchases(home)).hasSize(2_500)
    }

    @Test fun resendingAfterALostAnswerChangesNothingTwice() = runTest {
        val home = lists.create("Home")
        signIn(); sync.sync()
        add(home, "Milk")
        server.loseNextAnswer = true
        assertThat(sync.push()).isEqualTo(SyncStatus.OFFLINE)
        assertThat(pending(home)).isEqualTo(1)
        assertThat(server.names(home)).containsExactly("Milk")
        assertThat(sync.push()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.outcomes.last()).isEqualTo("no_op")
        assertThat(server.names(home)).containsExactly("Milk")
        assertThat(names(home)).containsExactly("Milk")
        assertThat(pending(home)).isEqualTo(0)
    }

    @Test fun aListTheServerNoLongerGivesThisAccountIsDropped() = runTest {
        val home = lists.create("Home"); val other = lists.create("Other")
        signIn(); sync.sync()
        server.lists.getValue(home).members.remove("me")
        add(home, "Milk")
        assertThat(sync.push()).isEqualTo(SyncStatus.SYNCED)
        assertThat(db.listDao().find(home)).isNull()
        assertThat(db.listDao().find(other)).isNotNull()
    }

    @Test fun anIdTakenOnTheServerContinuesAsACopyUnderANewId() = runTest {
        val home = lists.create("Home"); add(home, "Milk")
        server.taken += home
        signIn()
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        val copy = db.listDao().all().single()
        assertThat(copy.id).isNotEqualTo(home)
        assertThat(copy.name).isEqualTo("Home")
        assertThat(copy.created).isTrue()
        assertThat(db.listDao().selected()).isEqualTo(copy.id)
        assertThat(names(copy.id)).containsExactly("Milk")
        assertThat(server.names(copy.id)).containsExactly("Milk")
        assertThat(server.calls.filter { it.startsWith("PUT") }).containsExactly("PUT $home", "PUT ${copy.id}").inOrder()
    }

    @Test fun aResetDropsSyncedListsItDidNotReturnAndKeepsListsNotYetUploaded() = runTest {
        val home = lists.create("Home"); val gone = lists.create("Gone")
        signIn(); sync.sync()
        server.restore(without = gone)
        val fresh = lists.create("Fresh")
        assertThat(sync.pull()).isEqualTo(SyncStatus.SYNCED)
        assertThat(db.listDao().all().map { it.id }).containsExactly(home, fresh)
        assertThat(sync.push()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.lists.keys).containsExactly(home, fresh)
    }

    @Test fun removedEntriesDeleteTheListOnThePhone() = runTest {
        val theirs = server.seed("Camping", owner = "ana", members = listOf("me"))
        signIn(); sync.sync()
        assertThat(db.listDao().find(theirs)!!.role).isEqualTo("editor")
        server.evict(theirs, "me")
        assertThat(sync.pull()).isEqualTo(SyncStatus.SYNCED)
        assertThat(db.listDao().find(theirs)).isNull()
    }

    @Test fun eachFeedPageIsStoredWithItsCursorSoAFailedPageResumesWhereItStopped() = runTest {
        val a = server.seed("A"); val b = server.seed("B"); val c = server.seed("C")
        server.pageSize = 1
        server.failChangesCall = 2
        signIn()
        assertThat(sync.pull()).isEqualTo(SyncStatus.OFFLINE)
        assertThat(db.listDao().all().map { it.id }).containsExactly(a)
        assertThat(replica.cursor()).isEqualTo(server.cursorAfter(a))
        assertThat(sync.pull()).isEqualTo(SyncStatus.SYNCED)
        assertThat(db.listDao().all().map { it.id }).containsExactly(a, b, c).inOrder()
        assertThat(server.cursors.last()).isEqualTo(server.cursorAfter(b))
    }

    @Test fun signingOutWarnsAboutUnsentChangesThenRemovesTheAccountsLists() = runTest {
        val home = lists.create("Home")
        signIn(); sync.sync()
        add(home, "Milk")
        server.offline = true
        assertThat(sync.signOut(force = false)).isEqualTo(1)
        assertThat(forgotten).isEqualTo(0)
        assertThat(db.listDao().find(home)).isNotNull()
        assertThat(connections.current()).isNotNull()
        assertThat(sync.signOut(force = true)).isEqualTo(0)
        assertThat(forgotten).isEqualTo(1)
        assertThat(connections.current()).isNull()
        val left = db.listDao().all().single()
        assertThat(left.id).isNotEqualTo(home)
        assertThat(left.synced).isFalse()
        assertThat(db.listDao().selected()).isEqualTo(left.id)
        assertThat(sync.push()).isEqualTo(SyncStatus.SYNCED)
        assertThat(sync.account.value).isNull()
    }

    @Test fun signingOutWithEverythingSentRemovesTheListsAtOnce() = runTest {
        val home = lists.create("Home"); add(home, "Milk")
        signIn()
        assertThat(sync.signOut(force = false)).isEqualTo(0)
        assertThat(server.names(home)).containsExactly("Milk")
        assertThat(db.listDao().find(home)).isNull()
    }

    @Test fun aRejectedCredentialKeepsEveryListUntilTheAccountSignsInAgain() = runTest {
        val home = lists.create("Home")
        signIn(); sync.sync()
        add(home, "Milk")
        server.unauthenticated = true
        assertThat(sync.push()).isEqualTo(SyncStatus.SIGN_IN)
        assertThat(connections.current()).isNull()
        assertThat(sync.account.value).isNull()
        assertThat(sync.expired()).isTrue()
        assertThat(names(home)).containsExactly("Milk")
        assertThat(pending(home)).isEqualTo(1)
        add(home, "Bread")
        server.unauthenticated = false
        signIn()
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.names(home)).containsExactly("Bread", "Milk")
        assertThat(db.listDao().all().map { it.id }).containsExactly(home)
        assertThat(sync.expired()).isFalse()
    }

    @Test fun aBatchTheServerRefusesIsSplitUntilTheOneBadIntentIsDropped() = runTest {
        val home = lists.create("Home")
        signIn(); sync.sync()
        add(home, "Milk"); add(home, "Bread"); add(home, "Eggs")
        server.refuse = replica.pendingIntents(home, 10)[1].id
        assertThat(sync.push()).isEqualTo(SyncStatus.CAPACITY)
        assertThat(server.names(home)).containsExactly("Eggs", "Milk")
        assertThat(names(home)).containsExactly("Eggs", "Milk")
        assertThat(pending(home)).isEqualTo(0)
    }

    @Test fun removingAListDeletesItWhenOwnedAndLeavesItOtherwise() = runTest {
        val home = lists.create("Home")
        val theirs = server.seed("Camping", owner = "ana", members = listOf("me"))
        signIn(); sync.sync()
        sync.remove(home)
        sync.remove(theirs)
        assertThat(server.calls).containsAtLeast("DELETE $home", "DELETE $theirs/members/me")
        assertThat(server.lists.keys).containsExactly(theirs)
        assertThat(db.listDao().all().map { it.id }).containsNoneOf(home, theirs)
    }

    @Test fun archivingASyncedListAsksTheServerAndKeepsBothFlags() = runTest {
        val home = lists.create("Home"); val camping = lists.create("Camping")
        signIn(); sync.sync()
        lists.select(home)
        sync.archive(home, true)
        assertThat(server.calls).contains("PUT $home/archived true")
        assertThat(db.listDao().find(home)!!.let { it.archived to it.archivedByOwner }).isEqualTo(true to true)
        assertThat(db.listDao().selected()).isEqualTo(camping)
        sync.archive(home, false)
        assertThat(server.calls.last()).isEqualTo("PUT $home/archived false")
        assertThat(db.listDao().find(home)!!.let { it.archived to it.archivedByOwner }).isEqualTo(false to false)
        assertThat(db.listDao().selected()).isEqualTo(camping)
    }

    @Test fun anOwnersArchiveArrivingThroughTheFeedMovesTheSelectionOff() = runTest {
        val home = lists.create("Home")
        val theirs = server.seed("Camping", owner = "ana", members = listOf("me"))
        signIn(); sync.sync()
        lists.select(theirs)
        server.archive(theirs, by = "ana")
        assertThat(sync.pull()).isEqualTo(SyncStatus.SYNCED)
        assertThat(db.listDao().find(theirs)!!.let { it.archived to it.archivedByOwner }).isEqualTo(true to true)
        assertThat(db.listDao().selected()).isEqualTo(home)
    }

    @Test fun archivingTheLastListStartsOneFreshLocalListThatSyncingKeeps() = runTest {
        val home = lists.create("Home"); add(home, "Milk")
        signIn(); sync.sync()
        sync.archive(home, true)
        val fresh = db.listDao().selected()
        assertThat(fresh).isNotEqualTo(home)
        assertThat(db.listDao().find(fresh)!!.synced).isFalse()
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(db.listDao().all().map { it.id }).containsExactly(home, fresh).inOrder()
        assertThat(db.listDao().selected()).isEqualTo(fresh)
        assertThat(server.lists.keys).containsExactly(home)
        assertThat(names(home)).containsExactly("Milk")
    }

    @Test fun anArchivedListTheServerDoesNotHoldYetIsRestoredOnThePhoneAndUploadedAsItIs() = runTest {
        lists.create("Home")
        val camping = lists.create("Camping"); add(camping, "Tent")
        sync.archive(camping, true)
        signIn()
        server.invalid = camping
        assertThat(sync.sync()).isEqualTo(SyncStatus.CAPACITY)
        assertThat(db.listDao().find(camping)!!.let { it.synced to it.created }).isEqualTo(true to false)
        server.offline = true
        sync.archive(camping, false)
        assertThat(server.calls.none { "/archived" in it }).isTrue()
        assertThat(db.listDao().find(camping)!!.archived).isFalse()
        server.offline = false; server.invalid = null
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.calls.none { "/archived" in it }).isTrue()
        assertThat(server.names(camping)).containsExactly("Tent")
    }

    @Test fun anEditorArchivesOnlyForThemselfAndCannotRestoreWhatTheOwnerArchived() = runTest {
        lists.create("Home")
        val theirs = server.seed("Camping", owner = "ana", members = listOf("me"))
        signIn(); sync.sync()
        sync.archive(theirs, true)
        assertThat(db.listDao().find(theirs)!!.let { it.archived to it.archivedByOwner }).isEqualTo(true to false)
        sync.archive(theirs, false)
        assertThat(db.listDao().find(theirs)!!.archived).isFalse()
        server.archive(theirs, by = "ana")
        sync.pull()
        val calls = server.calls.size
        assertThat(runCatching { sync.archive(theirs, false) }.isFailure).isTrue()
        assertThat(server.calls).hasSize(calls)
        assertThat(db.listDao().find(theirs)!!.let { it.archived to it.archivedByOwner }).isEqualTo(true to true)
    }

    @Test fun aLocalListIsArchivedOnThePhoneAndStaysArchivedOnceSignedIn() = runTest {
        val home = lists.create("Home"); add(home, "Milk")
        val camping = lists.create("Camping"); add(camping, "Tent")
        sync.archive(camping, true)
        assertThat(server.calls).isEmpty()
        assertThat(db.listDao().find(camping)!!.archived).isTrue()
        assertThat(db.listDao().selected()).isEqualTo(home)
        signIn()
        assertThat(sync.sync()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.calls).containsAtLeast("PUT $camping", "PUT $camping/archived true").inOrder()
        assertThat(server.names(camping)).containsExactly("Tent")
        assertThat(db.listDao().find(camping)!!.let { it.archived to it.archivedByOwner }).isEqualTo(true to true)
        assertThat(db.listDao().find(home)!!.archived).isFalse()
    }

    @Test fun aListArchivedBeforeItsFirstUploadStaysArchivedWhenTheFeedOvertakesTheUpload() = runTest {
        lists.create("Home")
        val camping = lists.create("Camping"); add(camping, "Tent")
        sync.archive(camping, true)
        signIn()
        server.onCreate = { if (it == camping) sync.pull() }
        assertThat(sync.push()).isEqualTo(SyncStatus.SYNCED)
        assertThat(server.calls).containsAtLeast("PUT $camping", "changes", "PUT $camping/archived true").inOrder()
        assertThat(server.lists.getValue(camping).archivedBy).containsExactly("me")
        assertThat(db.listDao().find(camping)!!.archived).isTrue()
    }

    @Test fun addingSomeoneUploadsANewListFirstAndStoresTheNewMembers() = runTest {
        val home = lists.create("Home")
        signIn()
        sync.addMember(home, "ana")
        assertThat(server.calls.take(2)).containsExactly("PUT $home", "POST $home/members ana").inOrder()
        assertThat(db.listDao().find(home)!!.members).contains("\"ana\"")
        sync.removeMember(home, "ana")
        assertThat(db.listDao().find(home)!!.members).doesNotContain("\"ana\"")
    }
}

private class MemoryState(var state: ClientState = ClientState()) : SyncStateStore {
    override fun read() = state
    override fun update(change: (ClientState) -> ClientState) = change(state).also { state = it }
}

/** Just enough of docs/sync/lists-v2.md: one account at a time, a server-wide seq and a restore epoch. */
private class FakeServer : ListsService {
    /** [archivedBy] holds who archived the list: the owner for everyone, an editor for themself. */
    class Stored(var seq: Long, var projection: Projection, val owner: String, val members: MutableMap<String, String>, val archivedBy: MutableSet<String> = mutableSetOf())
    var me = "me"
    val lists = linkedMapOf<String, Stored>()
    val taken = mutableSetOf<String>()
    val calls = mutableListOf<String>()
    val outcomes = mutableListOf<String>()
    val cursors = mutableListOf<String?>()
    private val removed = mutableListOf<Pair<String, RemovedList>>()
    private var seq = 0L
    private var epoch = 1
    private var changesCalls = 0
    var pageSize = 50
    var failChangesCall = 0
    var loseNextAnswer = false
    var offline = false
    var unauthenticated = false
    var refuse: String? = null
    var invalid: String? = null
    /** Runs after the server stored a new list and before it answers, as a concurrent pull would. */
    var onCreate: (suspend (String) -> Unit)? = null

    fun seed(name: String, owner: String = me, members: List<String> = emptyList()): String {
        val id = "server-${lists.size}"
        lists[id] = Stored(++seq, Projection(name = name), owner, (mapOf(owner to "owner") + members.associateWith { "editor" }).toMutableMap())
        return id
    }
    /** Someone else, on the web, adds an item. */
    fun capture(list: String, name: String) {
        val stored = lists.getValue(list)
        val payload = buildJsonObject { put("proposedId", "web-$name"); put("key", Normalizer.normalize(name)); put("name", name) }
        stored.projection = Reducer(NORMALIZER_VERSION).apply(stored.projection, Intent("web-intent-$seq", list, "capture", payload, normalizer = NORMALIZER_VERSION)).projection
        stored.seq = ++seq
    }
    fun names(list: String) = lists.getValue(list).projection.rows.values.filter { !it.archived }.map { it.facts.getValue("name").jsonPrimitive.content }.sorted()
    /** A restore from backup: the epoch changes, so every cursor handed out before is stale. */
    fun restore(without: String) { lists.remove(without); epoch++ }
    fun evict(list: String, user: String) { lists.getValue(list).members.remove(user); removed += user to RemovedList(list, ++seq) }
    /** Someone archives the list from another device: the owner for everyone, an editor for themself. */
    fun archive(list: String, by: String) { lists.getValue(list).archivedBy += by; lists.getValue(list).seq = ++seq }
    fun cursorAfter(list: String) = "$epoch.${lists.getValue(list).seq}"

    private fun check() {
        if (offline) throw RelayProblem("offline")
        if (unauthenticated) throw RelayProblem("unauthenticated", 401)
    }
    private fun member(list: String) = lists[list]?.takeIf { me in it.members } ?: throw RelayProblem("not_found", 404)
    private fun shared(id: String) = lists.getValue(id).let { stored ->
        SharedList(id, stored.seq, stored.members.getValue(me), Person(stored.owner, stored.owner), stored.members.map { Member(it.key, it.key, it.value) }, stored.projection,
            archived = stored.owner in stored.archivedBy || me in stored.archivedBy, archivedByOwner = stored.owner in stored.archivedBy)
    }

    override suspend fun changes(cursor: String?, wait: Int): ListChanges {
        calls += "changes"; cursors += cursor; check()
        if (++changesCalls == failChangesCall) throw RelayProblem("offline")
        val after = cursor?.takeIf { it.startsWith("$epoch.") }?.substringAfter('.')?.toLong()
        val entries: List<Pair<Long, Any>> = lists.filter { me in it.value.members && it.value.seq > (after ?: 0) }.map { it.value.seq to shared(it.key) } +
            (if (after == null) emptyList() else removed.filter { it.first == me && it.second.seq > after }.map { it.second.seq to it.second })
        val page = entries.sortedBy { it.first }.take(pageSize)
        val next = page.lastOrNull()?.first ?: after ?: seq
        return ListChanges("$epoch.$next", after == null, entries.size > page.size, page.mapNotNull { it.second as? SharedList }, page.mapNotNull { it.second as? RemovedList })
    }
    override suspend fun create(list: String, projection: Projection): SharedList {
        calls += "PUT $list"; check()
        if (list == invalid) throw RelayProblem("invalid_request", 400)
        lists[list]?.let { if (me in it.members) return shared(list) }
        if (list in taken || list in lists) throw RelayProblem("list_exists", 409)
        lists[list] = Stored(++seq, projection, me, mutableMapOf(me to "owner"))
        onCreate?.invoke(list)
        return shared(list)
    }
    override suspend fun intents(list: String, intents: List<Intent>): IntentBatchResult {
        calls += "POST $list ${intents.size}"; check()
        val stored = member(list)
        if (intents.any { it.id == refuse }) throw RelayProblem("too_large", 413)
        val reducer = Reducer(NORMALIZER_VERSION)
        val before = stored.projection
        val results = intents.map { intent ->
            val reduction = reducer.apply(stored.projection, intent)
            stored.projection = reduction.projection
            outcomes += reduction.outcome.name.lowercase()
            IntentResult(intent.id, reduction.outcome.name.lowercase())
        }
        if (stored.projection != before) stored.seq = ++seq
        if (loseNextAnswer) { loseNextAnswer = false; throw RelayProblem("offline") }
        return IntentBatchResult(results, shared(list))
    }
    override suspend fun delete(list: String) {
        calls += "DELETE $list"; check()
        val stored = member(list)
        if (stored.owner != me) throw RelayProblem("forbidden", 403)
        lists.remove(list); taken += list
    }
    override suspend fun archive(list: String, archived: Boolean): SharedList {
        calls += "PUT $list/archived $archived"; check()
        val stored = member(list)
        val changed = if (archived) stored.archivedBy.add(me) else stored.archivedBy.remove(me)
        if (changed) stored.seq = ++seq
        return shared(list)
    }
    override suspend fun people(query: String) = listOf(Person("ana", "Ana", "ana@usuals.example.org")).filter { query in it.name }
    override suspend fun addMember(list: String, user: String): SharedList {
        calls += "POST $list/members $user"; check()
        val stored = member(list); stored.members[user] = "editor"; stored.seq = ++seq
        return shared(list)
    }
    override suspend fun removeMember(list: String, user: String) {
        calls += "DELETE $list/members/$user"; check()
        val stored = member(list)
        if (user == stored.owner) throw RelayProblem("forbidden", 403)
        stored.members.remove(user); stored.seq = ++seq
        removed += user to RemovedList(list, ++seq)
    }
}

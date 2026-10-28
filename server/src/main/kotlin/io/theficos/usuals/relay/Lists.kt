package io.theficos.usuals.relay

import io.theficos.usuals.model.NORMALIZER_VERSION
import io.theficos.usuals.sync.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.sql.Connection

// The wire shapes of SharedList, ListChanges and IntentBatchResult. The projection is the stored JSON passed
// through as is, so a response never decodes a list (up to 7 MB) into objects a second time.
@Serializable internal data class ListView(val id: String, val seq: Long, val role: String, val owner: Person, val members: List<Member>, val projection: JsonElement,
    val archived: Boolean, val archivedByOwner: Boolean)
@Serializable internal data class ListPage(val cursor: String, val reset: Boolean, val hasMore: Boolean, val lists: List<ListView>, val removed: List<RemovedList>)
@Serializable internal data class BatchView(val results: List<IntentResult>, val list: ListView)

/** An authenticated account, and the check that it is still signed in, repeated inside each write transaction. */
internal data class AccountAccess(val user: String, val verify: (Connection) -> Unit)

/**
 * Server-held lists (ADR 0017, docs/sync/lists-v2.md). A change locks the list row first and takes the change
 * counter last, just before commit, so writers to different lists only queue on the counter briefly.
 */
internal class Lists(private val relay: Relay, private val config: WebIdentityConfig) {
    private val db = relay.db
    private val cipher = relay.cipher
    private val reducer = Reducer(NORMALIZER_VERSION)
    val waiters = Waiters()

    fun create(access: AccountAccess, list: String, request: CreateList): Pair<ListView, Boolean> {
        id(list)
        // A client's snapshot: every fact is checked as the reducer would check it.
        val plain = SnapshotCodec.encode(request.projection)
        val (view, created) = db.transaction { c ->
            access.verify(c)
            // Serializes this account's creates, so a retried create and the list limit cannot race.
            c.query("SELECT 1 FROM web_user WHERE id=? FOR UPDATE", id(access.user)) { Unit }
            existing(c, access.user, list)?.let { return@transaction it to false }
            expect(c.query("SELECT count(*) FROM list WHERE owner=? AND deleted_at IS NULL", id(access.user)) { it.getLong(1) }.single() < config.accountLimits.ownedLists(c, access.user), "limit_reached", 409)
            val seq = next(c)
            expect(c.execute("INSERT INTO list(id,owner,projection,seq,created_at) VALUES (?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
                id(list), id(access.user), cipher.seal(list, plain), seq, relay.auth.now()) == 1, "list_exists", 409)
            c.execute("INSERT INTO list_member(list_id,user_id,role) VALUES (?,?,'owner')", id(list), id(access.user))
            view(c, access.user, list, seq, plain) to true
        }
        if (created) waiters.changed(listOf(access.user))
        return view to created
    }
    private fun existing(c: Connection, user: String, list: String): ListView? {
        val (seq, sealed, role) = c.query("SELECT l.seq,l.projection,m.role FROM list l LEFT JOIN list_member m ON m.list_id=l.id AND m.user_id=? AND m.removed IS NULL WHERE l.id=?",
            id(user), id(list)) { Triple(it.getLong(1), it.getBytes(2), it.getString(3)) }.singleOrNull() ?: return null
        expect(sealed != null && role != null, "list_exists", 409)
        return view(c, user, list, seq, cipher.open(list, sealed))
    }

    internal fun requireNoSharedOwned(c: Connection, user: String) {
        expect(c.query("SELECT 1 FROM list l JOIN list_member m ON m.list_id=l.id WHERE l.owner=? AND l.projection IS NOT NULL AND m.user_id<>? AND m.removed IS NULL LIMIT 1", id(user), id(user)) { Unit }.isEmpty(), "shared_owned_lists_require_confirmation", 409)
    }
    /** Called under the account lifecycle's exclusive write fence; keeps every other owner's list. */
    internal fun closeAccount(c: Connection, user: String, now: Long): List<String> {
        val affected = c.query("SELECT l.id,l.owner FROM list l JOIN list_member m ON m.list_id=l.id WHERE m.user_id=? AND m.removed IS NULL AND l.projection IS NOT NULL ORDER BY l.id FOR UPDATE OF l", id(user)) { it.getObject(1).toString() to it.getObject(2).toString() }
        val woken = mutableListOf<String>()
        for ((list, owner) in affected) {
            woken += current(c, list)
            val seq = next(c)
            if (owner == user) {
                c.execute("UPDATE list SET projection=NULL,deleted_at=?,seq=? WHERE id=?", now, seq, id(list))
                c.execute("UPDATE list_member SET removed=? WHERE list_id=? AND removed IS NULL", seq, id(list))
                c.execute("DELETE FROM email_invitation WHERE list_id=?", id(list))
            } else c.execute("UPDATE list SET seq=? WHERE id=?", seq, id(list))
        }
        c.execute("DELETE FROM list_member WHERE user_id=?", id(user))
        return woken
    }

    fun apply(access: AccountAccess, list: String, batch: IntentBatch): BatchView {
        id(list)
        expect(batch.intents.size in 1..100 && batch.intents.all { it.listId == list })
        var members = emptyList<String>()
        val result = db.transaction { c ->
            access.verify(c)
            val locked = lock(c, access.user, list, read = true)
            val text = cipher.open(list, locked.sealed)
            val before = Wire.json.decodeFromString(Projection.serializer(), text.decodeToString())
            var projection = before
            val results = batch.intents.map { intent ->
                reducer.apply(projection, intent).let { projection = it.projection; IntentResult(intent.id, it.outcome.name.lowercase()) }
            }
            // The reducer returns its input unchanged only for resends and intents it cannot read: no new seq.
            if (projection === before) return@transaction BatchView(results, view(c, access.user, list, locked.seq, text))
            // The reducer's output from a stored list needs only the snapshot bounds, not a replay of its facts.
            val plain = SnapshotCodec.encode(projection, replay = false)
            val seq = next(c)
            c.execute("UPDATE list SET projection=?,seq=? WHERE id=?", cipher.seal(list, plain), seq, id(list))
            members = current(c, list)
            BatchView(results, view(c, access.user, list, seq, plain))
        }
        waiters.changed(members)
        return result
    }

    fun delete(access: AccountAccess, list: String) {
        id(list)
        val members = db.transaction { c ->
            access.verify(c)
            expect(lock(c, access.user, list).role == "owner", "forbidden", 403)
            val members = current(c, list)
            val seq = next(c)
            c.execute("UPDATE list SET projection=NULL,deleted_at=?,seq=? WHERE id=?", relay.auth.now(), seq, id(list))
            c.execute("UPDATE list_member SET removed=? WHERE list_id=? AND removed IS NULL", seq, id(list))
            c.execute("UPDATE email_invitation SET state='revoked',delivery='cancelled',mail_cipher=NULL WHERE list_id=? AND state='pending'", id(list))
            members
        }
        waiters.changed(members)
    }

    fun add(access: AccountAccess, list: String, request: AddMember): ListView {
        id(list); id(request.userId)
        var members = emptyList<String>()
        val view = db.transaction { c ->
            access.verify(c)
            expect(config.directory == "all" || collaborators(c, access.user, request.userId), "not_found", 404)
            val result = addMember(c, access.user, list, request.userId, access.user)
            members = current(c, list)
            result
        }
        waiters.changed(members)
        return view
    }

    /** Called only after verified-recipient invitation checks, inside their transaction. */
    internal fun addInvited(c: Connection, sender: String, list: String, recipient: String) = addMember(c, sender, list, recipient, recipient)
    private fun addMember(c: Connection, sender: String, list: String, target: String, viewer: String): ListView {
        val locked = lock(c, sender, list, read = true)
        // A plain read avoids deadlocking two accounts sharing back to each other.
        expect(c.query("SELECT issuer,disabled FROM web_user WHERE id=?", id(target)) { config.accepts(it.getString(1)) && !it.getBoolean(2) }.singleOrNull() == true, "not_found", 404)
        if (role(c, target, list) != null) return view(c, viewer, list, locked.seq, cipher.open(list, locked.sealed))
        val owner = c.query("SELECT owner FROM list WHERE id=?", id(list)) { it.getObject(1).toString() }.single()
        // A policy downgrade preserves existing members. Do not lock the owner's account under this list:
        // their phone may already hold that account row while waiting to edit the same list.
        config.accountLimits.lockMemberPolicy(c, owner)
        expect(current(c, list).size < config.accountLimits.members(c, owner), "limit_reached", 409)
        c.execute("INSERT INTO list_member(list_id,user_id,role) VALUES (?,?,'editor') ON CONFLICT(list_id,user_id) DO UPDATE SET role='editor',removed=NULL,archived=false", id(list), id(target))
        val seq = next(c)
        c.execute("UPDATE list SET seq=? WHERE id=?", seq, id(list))
        return view(c, viewer, list, seq, cipher.open(list, locked.sealed))
    }
    internal fun existingView(c: Connection, user: String, list: String) = existing(c, user, list) ?: reject("not_found", 404)

    fun remove(access: AccountAccess, list: String, user: String) {
        id(list); id(user)
        val members = db.transaction { c ->
            access.verify(c)
            val role = lock(c, access.user, list).role
            val target = role(c, user, list) ?: reject("not_found", 404)
            expect(target != "owner" && (role == "owner" || user == access.user), "forbidden", 403)
            val members = current(c, list)
            val seq = next(c)
            c.execute("UPDATE list_member SET removed=? WHERE list_id=? AND user_id=?", seq, id(list), id(user))
            c.execute("UPDATE email_invitation SET state='revoked',delivery='cancelled',mail_cipher=NULL WHERE list_id=? AND sender=? AND state='pending'", id(list), id(user))
            c.execute("UPDATE list SET seq=? WHERE id=?", seq, id(list))
            members
        }
        waiters.changed(members)
    }

    /** The owner archives or restores the list for every member, an editor only for themself. */
    fun archive(access: AccountAccess, list: String, request: ArchiveList): ListView {
        id(list)
        var woken = emptyList<String>()
        val view = db.transaction { c ->
            access.verify(c)
            val locked = lock(c, access.user, list, read = true)
            val owner = locked.role == "owner"
            val changed = if (owner) c.execute("UPDATE list SET archived=? WHERE id=? AND archived<>?", request.archived, id(list), request.archived)
                else c.execute("UPDATE list_member SET archived=? WHERE list_id=? AND user_id=? AND archived<>?", request.archived, id(list), id(access.user), request.archived)
            if (changed == 0) return@transaction view(c, access.user, list, locked.seq, cipher.open(list, locked.sealed))
            // An editor's flag changes only their own view; the new seq still reaches every member's feed.
            woken = if (owner) current(c, list) else listOf(access.user)
            val seq = next(c)
            c.execute("UPDATE list SET seq=? WHERE id=?", seq, id(list))
            view(c, access.user, list, seq, cipher.open(list, locked.sealed))
        }
        waiters.changed(woken)
        return view
    }

    fun people(access: AccountAccess, q: String): People = db.transaction { c ->
        expect(q.length <= 200)
        access.verify(c)
        People(c.query("SELECT id,$NAME,email FROM web_user u WHERE id<>? AND NOT disabled AND (issuer=? OR issuer=?) " +
            (if (config.directory == "collaborators") "AND EXISTS(SELECT 1 FROM list_member a JOIN list_member b ON a.list_id=b.list_id WHERE a.user_id=? AND b.user_id=u.id AND a.removed IS NULL AND b.removed IS NULL) " else "") +
            "AND (strpos(lower($NAME),lower(?))>0 OR strpos(lower(coalesce(email,'')),lower(?))>0) ORDER BY lower($NAME),id LIMIT 20",
            *(listOf<Any?>(id(access.user), config.oidc?.issuer ?: "", if (config.localEnabled) WebIdentityConfig.LOCAL_ISSUER else "") +
                (if (config.directory == "collaborators") listOf(id(access.user)) else emptyList()) + listOf(q, q)).toTypedArray()) {
            Person(it.getObject(1).toString(), it.getString(2), it.getString(3))
        })
    }
    private fun collaborators(c: Connection, first: String, second: String) = c.query("SELECT 1 FROM list_member a JOIN list_member b ON a.list_id=b.list_id WHERE a.user_id=? AND b.user_id=? AND a.removed IS NULL AND b.removed IS NULL LIMIT 1", id(first), id(second)) { Unit }.isNotEmpty()

    fun changes(access: AccountAccess, cursor: String?, limit: Int): ListPage {
        expect(limit in 1..200)
        val after = cursor?.let {
            val seq = it.substringAfterLast('.', "").toLongOrNull()
            expect(it.substringBeforeLast('.', "").isNotEmpty() && seq != null && seq >= 0)
            it.substringBeforeLast('.') to seq!!
        }
        db.transaction { access.verify(it) }
        // One snapshot: the counter row lock orders commits, so every seq up to the counter is visible here.
        return db.readSnapshot { c ->
            val (epoch, top) = c.query("SELECT fence,(SELECT seq FROM list_counter) FROM relay_meta") { it.getString(1) to it.getLong(2) }.single()
            val reset = after == null || after.first != epoch || after.second > top
            val from = if (reset) 0L else after!!.second
            val entries = c.query("SELECT l.id,l.seq,octet_length(l.projection) FROM list l JOIN list_member m ON m.list_id=l.id WHERE m.user_id=? AND m.removed IS NULL AND l.seq>? " +
                "UNION ALL SELECT list_id,removed,NULL FROM list_member WHERE user_id=? AND removed>? AND NOT ? ORDER BY 2 LIMIT ?",
                id(access.user), from, id(access.user), from, reset, limit + 1) { Entry(it.getObject(1).toString(), it.getLong(2), it.getObject(3) as Int?) }
            // Also bound a page by stored size, keeping at least one entry so a single large list still moves.
            var bytes = 0L
            val page = entries.take(limit).takeWhile { entry -> bytes += entry.size ?: 0; entry === entries.first() || bytes <= PAGE_BYTES }
            val shown = page.filter { it.size != null }
            val sealed = if (shown.isEmpty()) emptyMap() else c.query("SELECT id,projection FROM list WHERE id=ANY(?)", c.createArrayOf("uuid", shown.map { id(it.id) }.toTypedArray())) {
                it.getObject(1).toString() to it.getBytes(2)
            }.toMap()
            val views = views(c, access.user, shown.associate { it.id to (it.seq to cipher.open(it.id, sealed.getValue(it.id))) })
            val hasMore = entries.size > page.size
            ListPage("$epoch.${if (hasMore) page.last().seq else top}", reset, hasMore, shown.map { views.getValue(it.id) },
                page.filter { it.size == null }.map { RemovedList(it.id, it.seq) })
        }
    }
    private data class Entry(val id: String, val seq: Long, val size: Int?)

    private class Locked(val role: String, val seq: Long, val sealed: ByteArray)
    /** Locks the list, then reads membership in a later statement, which sees any change committed while waiting. */
    private fun lock(c: Connection, user: String, list: String, read: Boolean = false): Locked {
        val (seq, sealed) = c.query("SELECT seq,${if (read) "projection" else "''::bytea"} FROM list WHERE id=? AND projection IS NOT NULL FOR UPDATE", id(list)) {
            it.getLong(1) to it.getBytes(2)
        }.singleOrNull() ?: reject("not_found", 404)
        return Locked(role(c, user, list) ?: reject("not_found", 404), seq, sealed)
    }
    private fun role(c: Connection, user: String, list: String) =
        c.query("SELECT role FROM list_member WHERE list_id=? AND user_id=? AND removed IS NULL", id(list), id(user)) { it.getString(1) }.singleOrNull()
    private fun current(c: Connection, list: String) =
        c.query("SELECT user_id FROM list_member WHERE list_id=? AND removed IS NULL", id(list)) { it.getObject(1).toString() }
    private fun next(c: Connection) = c.query("UPDATE list_counter SET seq=seq+1 RETURNING seq") { it.getLong(1) }.single()
    private fun view(c: Connection, user: String, list: String, seq: Long, plain: ByteArray) = views(c, user, mapOf(list to (seq to plain))).getValue(list)
    @OptIn(ExperimentalSerializationApi::class)
    private fun views(c: Connection, user: String, lists: Map<String, Pair<Long, ByteArray>>): Map<String, ListView> {
        if (lists.isEmpty()) return emptyMap()
        val members = c.query("SELECT m.list_id,m.user_id,m.role,$NAME,u.email,m.archived,l.archived FROM list_member m JOIN web_user u ON u.id=m.user_id JOIN list l ON l.id=m.list_id " +
            "WHERE m.list_id=ANY(?) AND m.removed IS NULL ORDER BY m.role<>'owner',lower($NAME),m.user_id", c.createArrayOf("uuid", lists.keys.map(::id).toTypedArray())) {
            MemberRow(it.getObject(1).toString(), it.getObject(2).toString(), it.getString(3), it.getString(4), it.getString(5), it.getBoolean(6), it.getBoolean(7))
        }.groupBy { it.list }
        return lists.mapValues { (list, value) ->
            val rows = members.getValue(list)
            val owner = rows.first { it.role == "owner" }
            val caller = rows.first { it.user == user }
            ListView(list, value.first, caller.role, Person(owner.user, owner.name, owner.email), rows.map { Member(it.user, it.name, it.role) },
                JsonUnquotedLiteral(value.second.decodeToString()), caller.archived || caller.archivedByOwner, caller.archivedByOwner)
        }
    }
    private class MemberRow(val list: String, val user: String, val role: String, val name: String, val email: String?, val archived: Boolean, val archivedByOwner: Boolean)
    private companion object {
        const val NAME = "coalesce(nullif(u.display_name,''),u.local_username,nullif(u.email,''),'')"
        const val PAGE_BYTES = 4L * 1024 * 1024
    }
}

/**
 * Feed requests waiting for a change. One process holds every waiter, so a second server replica would need a
 * shared notifier (such as PostgreSQL LISTEN) instead.
 */
internal class Waiters(private val perAccount: Int = 4, private val total: Int = 256) {
    private val waiting = HashMap<String, MutableSet<CompletableDeferred<Unit>>>()
    private var count = 0
    @Synchronized fun register(user: String): CompletableDeferred<Unit> {
        expect((waiting[user]?.size ?: 0) < perAccount, "rate_limited", 429)
        expect(count < total, "unavailable", 503)
        return CompletableDeferred<Unit>().also { waiting.getOrPut(user) { mutableSetOf() } += it; count++ }
    }
    @Synchronized fun release(user: String, waiter: CompletableDeferred<Unit>) {
        val mine = waiting[user] ?: return
        if (mine.remove(waiter)) count--
        if (mine.isEmpty()) waiting.remove(user)
    }
    /** Called after commit. A wakeup only asks the request to read the feed again. */
    @Synchronized fun changed(users: Collection<String>) { users.forEach { user -> waiting[user]?.forEach { it.complete(Unit) } } }
}

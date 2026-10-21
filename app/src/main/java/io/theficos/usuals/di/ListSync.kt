package io.theficos.usuals.di

import io.theficos.usuals.data.local.ListRepository
import io.theficos.usuals.data.local.LocalReplica
import io.theficos.usuals.sync.*
import io.theficos.usuals.ui.account.AccountSession
import io.theficos.usuals.ui.list.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The phone side of docs/sync/lists-v2.md ("Clients > Phone"). Signed in, every list on the phone is
 * an account list: pushing uploads lists the server has yet to create and sends pending intents,
 * pulling reads the change feed. Every write that follows a server answer first checks, under
 * [writes], that the answer belongs to the account still signed in, so a late answer can never
 * bring back lists that signing out removed.
 */
class ListSync(private val lists: ListRepository, private val replica: LocalReplica, private val service: ListsService,
    private val connections: ConnectionClient, private val store: SyncStateStore) : AccountSession {
    private val pushing = Mutex()
    private val pulling = Mutex()
    private val writes = Mutex()
    private val signedIn = MutableStateFlow(connections.current())
    private val mutable = MutableStateFlow(SyncStatus.SYNCED)
    /** The connected account, or null while signed out or after the server stopped accepting this phone. */
    val account: StateFlow<ServerConnection?> = signedIn.asStateFlow()
    val status: StateFlow<SyncStatus> = mutable.asStateFlow()

    override suspend fun expired() = connections.current() == null && lists.all().any { it.synced }
    override suspend fun name(): String? {
        val me = connections.current()?.userId ?: return null
        return lists.all().firstNotNullOfOrNull { list ->
            list.members?.let { runCatching { Wire.json.decodeFromString<List<Member>>(it) }.getOrNull() }?.firstOrNull { it.id == me }?.name
        }
    }

    /** The connect flow approved this phone: every list on it is offered to the account, the feed starts over. */
    override suspend fun connected() {
        writes.withLock { replica.prepareSignIn() }
        signedIn.value = connections.current()
        mutable.value = SyncStatus.SYNCED
    }

    suspend fun push(): SyncStatus = pushing.withLock { attempt(::pushAll) }
    suspend fun pull(wait: Int = 0): SyncStatus = pulling.withLock { attempt { token -> pullAll(token, wait) } }

    /** Push, then pull unless a pull is already running: that one sees what this push changed. */
    suspend fun sync(): SyncStatus {
        val pushed = push()
        if (pushed == SyncStatus.SIGN_IN || pushed == SyncStatus.UPGRADE || pulling.isLocked) return pushed
        val pulled = pull()
        return if (pulled == SyncStatus.SYNCED) pushed else pulled
    }

    /**
     * Sends what is pending, then forgets the account and removes its lists from the phone. Unless
     * [force], nothing is removed while changes could not be sent: their count is returned instead.
     */
    override suspend fun signOut(force: Boolean): Int {
        if (connections.current() != null) push()
        val unsent = replica.unsent()
        if (unsent > 0 && !force) return unsent
        writes.withLock {
            try { connections.disconnect() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
            replica.forgetAccount()
        }
        signedIn.value = null
        mutable.value = SyncStatus.SYNCED
        return 0
    }

    /** Deletes a list the account owns or leaves one it was added to. A list the server never had just goes. */
    suspend fun remove(list: String) = pushing.withLock {
        val entry = lists.find(list) ?: return@withLock
        if (!entry.synced || !entry.created) return@withLock writes.withLock { replica.drop(list) }
        online { token, saved ->
            try { if (entry.role == "editor") service.removeMember(list, saved.userId) else service.delete(list) }
            catch (problem: RelayProblem) { if (problem.status != 404) throw problem }
            commit(token) { replica.drop(list) }
        }
    }

    suspend fun people(query: String): List<Person> = online { _, _ -> service.people(query) }

    /** Adds someone to a list, uploading the list first if the server does not have it yet. */
    suspend fun addMember(list: String, user: String) {
        if (lists.find(list)?.created != true) push()
        online { token, _ -> service.addMember(list, user).also { commit(token) { replica.accept(it) } } }
    }

    /** The owner removes an editor, or an editor leaves; the feed then brings the list's new members. */
    suspend fun removeMember(list: String, user: String) {
        val leaving = online { token, saved ->
            service.removeMember(list, user)
            if (user == saved.userId) commit(token) { replica.drop(list) }
            user == saved.userId
        }
        if (!leaving) pull()
    }

    private suspend fun attempt(work: suspend (String) -> SyncStatus): SyncStatus {
        val token = connections.current()?.token
        val result = if (token == null) {
            if (lists.all().any { it.synced }) SyncStatus.SIGN_IN else SyncStatus.SYNCED
        } else try { work(token) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: AccountChanged) { return mutable.value }
        catch (problem: RelayProblem) { classify(problem, token) }
        catch (_: Exception) { SyncStatus.OFFLINE }
        mutable.value = result
        return result
    }
    private suspend fun classify(problem: RelayProblem, token: String): SyncStatus = when {
        problem.status == 401 -> { expire(token); SyncStatus.SIGN_IN }
        problem.status == 426 -> SyncStatus.UPGRADE
        problem.code in FULL -> SyncStatus.CAPACITY
        else -> SyncStatus.OFFLINE
    }
    /** Runs a request the person is waiting for; a 401 still keeps every list on the phone. */
    private suspend fun <T> online(block: suspend (String, ServerConnection) -> T): T {
        val saved = connections.current() ?: throw RelayProblem("unauthenticated", 401)
        try { return block(saved.token, saved) }
        catch (problem: RelayProblem) { if (problem.status == 401) { expire(saved.token); mutable.value = SyncStatus.SIGN_IN }; throw problem }
    }
    private suspend fun expire(token: String) {
        writes.withLock { if (connections.current()?.token == token) store.update { it.copy(connection = null) } }
        signedIn.value = connections.current()
    }
    private suspend fun <T> commit(token: String, block: suspend () -> T): T = writes.withLock {
        if (connections.current()?.token != token) throw AccountChanged()
        block()
    }

    private suspend fun pushAll(token: String): SyncStatus {
        commit(token) { replica.adoptLocalLists() }
        var full = false
        for (list in replica.outgoing()) {
            var id = list.id
            try {
                if (!list.created) id = create(token, id)
                full = send(token, id) || full
            } catch (problem: RelayProblem) {
                when {
                    problem.status == 404 -> commit(token) { replica.drop(id) }
                    problem.code in FULL -> full = true
                    else -> throw problem
                }
            }
        }
        return if (full) SyncStatus.CAPACITY else SyncStatus.SYNCED
    }
    /** PUT with the list's current state; an id the server gave to someone else continues once as a copy under a new id. */
    private suspend fun create(token: String, list: String): String {
        var id = list
        while (true) {
            try {
                val created = service.create(id, replica.creation(id))
                commit(token) { replica.accept(created) }
                return id
            } catch (problem: RelayProblem) {
                if (problem.status != 409 || problem.code != "list_exists" || id != list) throw problem
                id = commit(token) { replica.copyAsNew(list) }
            }
        }
    }
    /** Sends pending intents oldest first. A batch the server refuses as a whole is split until the one intent it can never take is dropped. */
    private suspend fun send(token: String, list: String): Boolean {
        var size = BATCH
        var full = false
        while (true) {
            val batch = replica.pendingIntents(list, size)
            if (batch.isEmpty()) return full
            try {
                val result = service.intents(list, batch)
                commit(token) { replica.accept(result.list, batch.map { it.id }) }
                size = BATCH
            } catch (problem: RelayProblem) {
                if (problem.status != 400 && problem.status != 413) throw problem
                if (batch.size > 1) size = batch.size / 2
                else { commit(token) { replica.discard(list, batch.map { it.id }) }; full = full || problem.status == 413 }
            }
        }
    }
    private suspend fun pullAll(token: String, wait: Int): SyncStatus {
        var first = true
        do {
            val page = service.changes(replica.cursor(), if (first) wait else 0)
            commit(token) { replica.applyPage(page) }
            first = false
        } while (page.hasMore)
        return SyncStatus.SYNCED
    }

    private class AccountChanged : Exception()
    override fun toString() = "ListSync([redacted])"
    private companion object {
        const val BATCH = 100
        val FULL = setOf("limit_reached", "too_large")
    }
}

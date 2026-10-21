package io.theficos.usuals.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SharePerson(val id: String, val name: String, val detail: String? = null)
data class ShareMember(val id: String, val name: String, val owner: Boolean)
/** One list as the share sheet sees it. [me] is null while signed out; [members] is empty until the server has the list. */
data class ShareTarget(val name: String, val members: List<ShareMember>, val me: String?)

/** Who can open a list, and the server's people to add. Every change needs the server. */
interface ShareStore {
    /** Null once the list is gone from this phone. */
    fun observe(list: String): Flow<ShareTarget?>
    suspend fun people(query: String): List<SharePerson>
    suspend fun add(list: String, person: String)
    /** Removes an editor, or leaves the list when [person] is this account. */
    suspend fun remove(list: String, person: String)
}

class ShareViewModel(private val list: String, private val store: ShareStore, private val pause: Long = 300,
    private val worker: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val mutable = MutableStateFlow(ShareState())
    val state = mutable.asStateFlow()
    private var search: Job? = null

    init {
        viewModelScope.launch(worker) {
            var first = true
            store.observe(list).collect { target ->
                mutable.update { it.copy(target = target, gone = target == null) }
                if (first && target?.me != null) { first = false; find("") }
            }
        }
    }

    fun query(text: String) {
        mutable.update { it.copy(query = text.take(100)) }
        search?.cancel()
        search = viewModelScope.launch(worker) { delay(pause); find(text.trim()) }
    }
    fun add(person: SharePerson) = work { store.add(list, person.id) }
    /** Only the owner removes someone, and never themself. */
    fun remove(member: ShareMember) {
        val state = state.value
        if (!state.owner || member.owner || member.id == state.target?.me) return
        work { store.remove(list, member.id) }
    }
    /** An editor leaves; the owner deletes the list instead. */
    fun leave() {
        val me = state.value.target?.me ?: return
        if (state.value.owner) return
        work { store.remove(list, me) }
    }

    private suspend fun find(text: String) {
        try { mutable.update { it.copy(people = store.people(text), problem = null) } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (problem: Exception) { mutable.update { it.copy(problem = shareProblem(problem)) } }
    }
    private fun work(block: suspend () -> Unit) {
        if (state.value.busy || state.value.target?.me == null) return
        mutable.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch(worker) {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (problem: Exception) { mutable.update { it.copy(problem = shareProblem(problem)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun shareProblem(problem: Exception): ShareProblem {
        val relay = problem as? io.theficos.usuals.sync.RelayProblem
        return when {
            relay == null -> ShareProblem.OFFLINE
            relay.status == 401 -> ShareProblem.SIGN_IN
            relay.code == "limit_reached" -> ShareProblem.FULL
            relay.code == "offline" || relay.code == "unavailable" || relay.code == "rate_limited" -> ShareProblem.OFFLINE
            else -> ShareProblem.REFUSED
        }
    }
}

/** [people] is what the last search found; [candidates] leaves out who already has access. [gone] is true once the list left this phone. */
data class ShareState(val target: ShareTarget? = null, val query: String = "", val people: List<SharePerson> = emptyList(),
    val busy: Boolean = false, val problem: ShareProblem? = null, val gone: Boolean = false) {
    val signedIn get() = target?.me != null
    /** Whether this account owns the list; a list the server has yet to receive belongs to whoever is signed in. */
    val owner get() = target?.me != null && (target.members.isEmpty() || target.members.any { it.owner && it.id == target.me })
    val candidates get() = people.filter { person -> target?.members?.none { it.id == person.id } ?: true }
}
enum class ShareProblem { OFFLINE, SIGN_IN, FULL, REFUSED }

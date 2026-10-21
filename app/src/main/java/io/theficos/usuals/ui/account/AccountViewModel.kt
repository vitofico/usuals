package io.theficos.usuals.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.usuals.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the account screen needs from the sync engine, so it can be replaced in tests. */
interface AccountSession {
    /** True when lists on this phone belong to an account whose server stopped accepting this phone. */
    suspend fun expired(): Boolean
    /** The signed-in person's name as the server reported it, once a list has synced. */
    suspend fun name(): String?
    suspend fun connected()
    suspend fun signOut(force: Boolean): Int
}

class AccountViewModel(private val connection: ConnectionClient, private val session: AccountSession) : ViewModel() {
    private val mutable = MutableStateFlow(AccountState())
    val state = mutable.asStateFlow()

    fun load() = work { reload() }
    fun field(value: String) = mutable.update { it.copy(origin = value.take(512), problem = null) }

    /** A scanned code or an opened link. Reading it never contacts a server. */
    fun scanned(text: String) = mutable.update { when (val scan = ConnectScan.read(text)) {
        is ConnectScan.Offer -> if (it.connection != null) it.copy(problem = AccountProblem.ALREADY) else it.copy(offer = scan, problem = null)
        is ConnectScan.Server -> if (it.connection != null) it.copy(problem = AccountProblem.ALREADY) else it.copy(origin = scan.origin, problem = null)
        ConnectScan.Outdated -> it.copy(problem = AccountProblem.OUTDATED)
        ConnectScan.Unknown -> it.copy(problem = AccountProblem.NOT_USUALS)
    } }
    fun dismissOffer() = mutable.update { it.copy(offer = null) }

    /** The web app already approved this offer, so the first poll completes the connection. */
    fun connectOffer() {
        val offer = state.value.offer ?: return
        work {
            connection.begin(offer.link)
            mutable.update { it.copy(offer = null) }
            complete()
        }
    }
    fun signInInBrowser() = work { connection.begin(state.value.origin.trim()); reload() }
    /** Polled while a sign-in is waiting; quiet when nothing changed. */
    fun check() = work(quiet = true) { complete() }
    fun cancel() { connection.cancel(); work { reload() } }

    fun signOut(force: Boolean) = work {
        val unsent = session.signOut(force)
        mutable.update { it.copy(unsent = unsent.takeIf { count -> count > 0 }, done = false) }
        reload()
    }
    fun keepSignedIn() = mutable.update { it.copy(unsent = null) }

    fun loadAi() = work(quiet = true) { mutable.update { it.copy(ai = connection.request<AiSettings>("GET", "/v1/ai/settings")) } }
    fun enableAi(enabled: Boolean) { connection.enableAi(enabled, state.value.ai?.revision); work { reload() } }

    private suspend fun complete() {
        if (connection.pending() == null) return
        if (connection.finish() != null) {
            session.connected()
            mutable.update { it.copy(done = true) }
        }
        reload()
    }
    private suspend fun reload() {
        val saved = connection.current()
        mutable.update { it.copy(connection = saved, connecting = connection.pending(), expired = saved == null && session.expired(),
            name = if (saved == null) null else session.name()) }
    }
    private fun work(quiet: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy) return
        if (!quiet) mutable.update { it.copy(busy = true, problem = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (problem: RelayProblem) { if (!quiet) mutable.update { it.copy(problem = when {
                problem.code == "offline" || problem.code == "unavailable" || problem.code == "rate_limited" -> AccountProblem.OFFLINE
                problem.status == 404 -> AccountProblem.EXPIRED_CODE
                problem.status == 426 -> AccountProblem.UPGRADE
                else -> AccountProblem.REFUSED
            }) } }
            catch (_: IllegalArgumentException) { if (!quiet) mutable.update { it.copy(problem = AccountProblem.NOT_USUALS) } }
            catch (_: Exception) { if (!quiet) mutable.update { it.copy(problem = AccountProblem.REFUSED) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}

/**
 * [expired] lists wait for the person to sign in again; [offer] waits for them to confirm the server it
 * names; [done] says a connection just completed; [unsent] is how many changes signing out would lose.
 */
data class AccountState(val origin: String = "", val connection: ServerConnection? = null, val connecting: PendingConnection? = null,
    val expired: Boolean = false, val name: String? = null, val offer: ConnectScan.Offer? = null, val done: Boolean = false, val unsent: Int? = null,
    val ai: AiSettings? = null, val busy: Boolean = false, val problem: AccountProblem? = null) {
    override fun toString() = "AccountState([redacted])"
}
enum class AccountProblem { NOT_USUALS, OUTDATED, ALREADY, OFFLINE, EXPIRED_CODE, UPGRADE, REFUSED }

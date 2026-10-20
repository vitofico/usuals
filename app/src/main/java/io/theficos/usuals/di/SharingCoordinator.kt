package io.theficos.usuals.di

import android.content.Context
import androidx.work.*
import io.theficos.usuals.auth.VaultException
import io.theficos.usuals.data.local.ListRepository
import io.theficos.usuals.data.local.LocalReplica
import io.theficos.usuals.data.local.SyncDao
import io.theficos.usuals.sync.*
import io.theficos.usuals.ui.list.SharingStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** One graph-owned pump. Room owns edits; sockets are disposable wake-up hints. */
class SharingCoordinator(private val context: Context, private val client: HouseholdClient, private val lists: ListRepository,
    private val replica: LocalReplica, private val dao: SyncDao, private val transport: OkHttpRelayTransport) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow<Map<String, SharingStatus>>(emptyMap())
    val status = mutable.asStateFlow()
    private var foreground: Job? = null
    private var started = false
    @Volatile private var visible = false
    @Volatile private var paused = false
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun start() {
        if (started) return
        started = true
        scope.launch { dao.observeQueueIds().distinctUntilChanged().collect { pending ->
            if (pending.isNotEmpty()) { schedule(); wake.trySend(Unit) }
        } }
        scope.launch { lists.observe().collect { current ->
            if (current.any { it.shared }) WorkManager.getInstance(context).enqueueUniquePeriodicWork("shared-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SharingWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
            else { WorkManager.getInstance(context).cancelUniqueWork("shared-periodic"); WorkManager.getInstance(context).cancelUniqueWork("shared-outbox") }
        } }
    }
    private fun schedule() { WorkManager.getInstance(context).enqueueUniqueWork("shared-outbox", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<SharingWorker>().setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()) }
    fun request() { wake.trySend(Unit); schedule() }
    fun foreground(active: Boolean) {
        visible = active
        start()
        if (paused) return
        if (!active) { foreground?.cancel(); foreground = null; return }
        if (foreground != null) return
        foreground = scope.launch {
            val sockets = mutableMapOf<String, Job>()
            try {
                while (isActive) {
                    syncOnce()
                    val households = runCatching { client.households().filter { !it.removed } }.getOrDefault(emptyList())
                    (sockets.keys - households.map { it.id }.toSet()).forEach { sockets.remove(it)?.cancel() }
                    households.forEach { household -> if (sockets[household.id]?.isActive != true) sockets[household.id] = launch {
                        var wait = 1000L
                        while (isActive) {
                            try { transport.hints(client.hintAccess(household.id)) { wake.trySend(Unit) }; wait = 1000 }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { }
                            delay(wait + kotlin.random.Random.nextLong(wait / 4 + 1)); wait = (wait * 2).coerceAtMost(30_000)
                        }
                    } }
                    withTimeoutOrNull(30_000) { wake.receive() }
                    // Coalesce a burst of event hints and local transactions.
                    delay(120)
                }
            } finally { sockets.values.forEach { it.cancel() } }
        }
    }
    suspend fun withTransportPaused(block: suspend () -> Unit) {
        paused = true
        foreground?.cancel(); foreground = null
        try { serial.withLock { block() } }
        finally { paused = false; if (visible) foreground(true) }
    }
    suspend fun syncOnce(): Boolean = serial.withLock {
        var retry = false
        val households = try { client.households() } catch (_: VaultException) {
            mutable.value = lists.all().filter { it.shared }.associate { it.id to SharingStatus.NEEDS_KEY }; return@withLock false
        }
        val known = lists.all().filter { it.shared }
        known.filter { row -> households.none { it.id == row.householdId } }.forEach { mutable.value += it.id to SharingStatus.NEEDS_KEY }
        for (household in households) {
            val affected = known.filter { it.householdId == household.id }.map { it.id }
            try {
                client.resume(household.id)
                val latest = client.households().first { it.id == household.id }
                if (latest.removed) throw RelayProblem("forbidden", 403)
                for (id in latest.lists) {
                    if (lists.all().none { it.id == id }) {
                        val downloaded = client.download(household.id, id)
                        lists.installShared(household.id, id, downloaded.checkpoint, downloaded.epoch, downloaded.manifestHash, replica)
                    }
                    var progress = SyncProgress(0, true)
                    repeat(3) { if (progress.hasPending) progress = client.synchronize(household.id, id, replica) }
                    mutable.value += id to if (progress.hasPending) SharingStatus.PENDING else SharingStatus.SYNCED
                    retry = retry || progress.hasPending
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) {
                val state = when (problem) {
                    is UnsupportedWireVersion -> SharingStatus.UPGRADE
                    is VaultException -> if (problem.problem == io.theficos.usuals.auth.VaultProblem.USAGE_EXHAUSTED) SharingStatus.CAPACITY else SharingStatus.NEEDS_KEY
                    is RelayProblem -> when (problem.code) {
                        "forbidden" -> SharingStatus.REMOVED
                        "recovery_required" -> SharingStatus.RECOVERY
                        "needs_key" -> SharingStatus.NEEDS_KEY
                        "quota_exceeded", "too_large", "payload_too_large", "usage_exhausted" -> SharingStatus.CAPACITY
                        "intent_conflict", "envelope_conflict" -> SharingStatus.CONFLICT
                        "offline", "unavailable", "rate_limited", "epoch_stale", "membership_conflict" -> SharingStatus.OFFLINE
                        else -> SharingStatus.VERIFICATION
                    }
                    is java.io.IOException -> SharingStatus.OFFLINE
                    else -> SharingStatus.VERIFICATION
                }
                affected.forEach { mutable.value += it to state }
                retry = retry || state == SharingStatus.OFFLINE
            }
        }
        retry
    }
    suspend fun share(household: String, source: String, history: Boolean): String {
        val target = client.reserveShare(household, source, history)
        val id = lists.prepareShare(household, source, history, replica, target)
        // Original local scope is retained even if creation or its acknowledgement fails.
        client.resume(household)
        if (id !in client.households().first { it.id == household }.lists) client.createList(household, id)
        lists.select(id)
        client.completeShare(household, id)
        request()
        return id
    }
    suspend fun retryShare(household: String, id: String) {
        require(lists.all().any { it.id == id && it.shared && it.householdId == household })
        client.resume(household)
        if (id !in client.households().first { it.id == household }.lists) client.createList(household, id)
        lists.select(id)
        client.completeShare(household, id)
        request()
    }
    override fun close() { scope.cancel() }
}
class SharingWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        return try { if (ServiceLocator.sharingCoordinator.syncOnce()) Result.retry() else Result.success() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}

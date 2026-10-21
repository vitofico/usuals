package io.theficos.usuals.di

import android.content.Context
import androidx.work.*
import io.theficos.usuals.ui.list.SyncStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit

/**
 * When [ListSync] runs while signed in: 2 s after a local change, in a waiting loop while the app is
 * visible, and every 15 minutes in the background. Failures back off and keep the outbox.
 */
class ListSyncScheduler(private val context: Context, private val sync: ListSync, private val outgoing: Flow<List<String>>) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private var started = false
    private var foreground: Job? = null

    @OptIn(FlowPreview::class)
    @Synchronized fun start() {
        if (started) return
        started = true
        // Work queued by the household relay names a worker that no longer exists.
        WorkManager.getInstance(context).run { cancelUniqueWork("shared-periodic"); cancelUniqueWork("shared-outbox") }
        scope.launch { outgoing.distinctUntilChanged().debounce(2000).collect { if (it.isNotEmpty() && sync.account.value != null) request() } }
        scope.launch { sync.account.map { it != null }.distinctUntilChanged().collect { signedIn ->
            val work = WorkManager.getInstance(context)
            if (signedIn) work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ListSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build())
            else { work.cancelUniqueWork(PERIODIC); work.cancelUniqueWork(NOW) }
        } }
    }
    /** Syncs as soon as the network allows, after any run already queued. */
    fun request() {
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<ListSyncWorker>()
            .setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    @Synchronized fun foreground(visible: Boolean) {
        start()
        if (!visible) { foreground?.cancel(); foreground = null; return }
        if (foreground?.isActive == true) return
        foreground = scope.launch {
            sync.account.map { it?.token }.distinctUntilChanged().collectLatest { token ->
                if (token == null) return@collectLatest
                sync.push()
                var wait = 1000L
                while (isActive) {
                    when (sync.pull(wait = 25)) {
                        SyncStatus.SYNCED, SyncStatus.CAPACITY -> wait = 1000
                        SyncStatus.SIGN_IN, SyncStatus.UPGRADE -> return@collectLatest
                        SyncStatus.OFFLINE -> { delay(wait + kotlin.random.Random.nextLong(wait / 4 + 1)); wait = (wait * 2).coerceAtMost(60_000) }
                    }
                }
            }
        }
    }
    override fun close() { scope.cancel() }

    private companion object {
        const val NOW = "lists-sync"
        const val PERIODIC = "lists-periodic"
    }
}

class ListSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        return if (ServiceLocator.listSync.sync() == SyncStatus.OFFLINE) Result.retry() else Result.success()
    }
}

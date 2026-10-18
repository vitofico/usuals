package io.theficos.comanda.di

import io.theficos.comanda.data.local.ListRepository
import io.theficos.comanda.ui.list.*
import kotlinx.coroutines.flow.*
import io.theficos.comanda.data.local.LocalReplica

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RepositoryListDirectory(private val repository: ListRepository, private val replica: LocalReplica, private val migratePrompts: (String) -> Unit = {}, private val transportStatus: StateFlow<Map<String, SharingStatus>> = MutableStateFlow(emptyMap())) : ListDirectoryStore {
    override fun observe() = repository.observeSelected().flatMapLatest { selected ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { migratePrompts(selected) }
        combine(repository.observe(), replica.observeStatus(selected), transportStatus) { lists, status, transport ->
            ListDirectory(selected, lists.map { NamedList(it.id, it.name, it.shared, it.archiveSource != null) }, status.pending,
                status.conflicts.map { ListConflict(it.id, it.itemId, it.current, it.incoming, it.kind) }, if (status.conflicts.isNotEmpty()) SharingStatus.CONFLICT else if (status.pending > 0 && transport[selected] == SharingStatus.SYNCED) SharingStatus.PENDING else transport[selected])
        }
    }
    override suspend fun dismissConflict(id: String) = replica.dismissConflict(id)
    override suspend fun select(id: String) = repository.select(id)
    override suspend fun create(name: String) { repository.create(name) }
}

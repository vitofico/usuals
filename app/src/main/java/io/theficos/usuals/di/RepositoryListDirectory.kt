package io.theficos.usuals.di

import io.theficos.usuals.data.local.ListEntity
import io.theficos.usuals.data.local.ListRepository
import io.theficos.usuals.data.local.LocalReplica
import io.theficos.usuals.sync.Member
import io.theficos.usuals.sync.Wire
import io.theficos.usuals.ui.list.*
import kotlinx.coroutines.flow.*

/** Without [sync] every list stays on the phone, as it does in tests. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RepositoryListDirectory(private val repository: ListRepository, private val replica: LocalReplica, private val migratePrompts: (String) -> Unit = {},
    private val sync: ListSync? = null) : ListDirectoryStore {
    override fun observe() = repository.observeSelected().flatMapLatest { selected ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { migratePrompts(selected) }
        combine(repository.observe(), replica.observeStatus(selected), sync?.account ?: flowOf(null), sync?.status ?: flowOf(SyncStatus.SYNCED)) { lists, status, account, synced ->
            val current = lists.firstOrNull { it.id == selected }
            ListDirectory(selected, lists.map { NamedList(it.id, it.name, people(it) > 1, it.archiveSource != null, it.role == "editor", it.archived, it.archivedByOwner) }, status.pending,
                status.conflicts.map { ListConflict(it.id, it.itemId, it.current, it.incoming, it.kind) },
                if (current?.synced != true) null else if (account == null) SyncStatus.SIGN_IN else synced, current?.let { replica.unused(it) } == true)
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    override suspend fun dismissConflict(id: String) = replica.dismissConflict(id)
    override suspend fun select(id: String) = repository.select(id)
    override suspend fun create(name: String) { repository.create(name) }
    override suspend fun rename(id: String, name: String) { replica.rename(id, name) }
    override suspend fun remove(id: String) { sync?.remove(id) ?: replica.drop(id) }
    override suspend fun archive(id: String, archived: Boolean) { sync?.archive(id, archived) ?: replica.archive(id, archived) }

    private fun people(list: ListEntity) = list.members?.let { runCatching { Wire.json.decodeFromString<List<Member>>(it).size }.getOrNull() } ?: 0
}

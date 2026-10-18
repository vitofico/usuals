package io.theficos.comanda.ui.list

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class NamedList(val id: String, val name: String, val shared: Boolean, val imported: Boolean = false)
data class ListDirectory(val selected: String = "", val lists: List<NamedList> = emptyList(), val pending: Int = 0, val conflicts: List<ListConflict> = emptyList(), val status: SharingStatus? = null)
data class ListConflict(val id: String, val itemId: String?, val current: Map<String, String?>, val incoming: Map<String, String?>, val kind: String)
interface ListDirectoryStore {
    suspend fun dismissConflict(id: String) = Unit
    fun observe(): Flow<ListDirectory>
    suspend fun select(id: String)
    suspend fun create(name: String)
}
object NoListDirectory : ListDirectoryStore {
    override fun observe() = flowOf(ListDirectory())
    override suspend fun select(id: String) = Unit
    override suspend fun create(name: String) = Unit
}

enum class SharingStatus { SYNCED, PENDING, OFFLINE, CONFLICT, NEEDS_KEY, REMOVED, UPGRADE, CAPACITY, VERIFICATION, RECOVERY }

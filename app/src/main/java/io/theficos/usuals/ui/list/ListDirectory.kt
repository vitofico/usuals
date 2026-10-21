package io.theficos.usuals.ui.list

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** [shared] means other people can open the list; [editor] means someone else owns it, so this phone can leave it but not delete it. */
data class NamedList(val id: String, val name: String, val shared: Boolean, val imported: Boolean = false, val editor: Boolean = false)
data class ListDirectory(val selected: String = "", val lists: List<NamedList> = emptyList(), val pending: Int = 0, val conflicts: List<ListConflict> = emptyList(), val status: SyncStatus? = null)
data class ListConflict(val id: String, val itemId: String?, val current: Map<String, String?>, val incoming: Map<String, String?>, val kind: String)
interface ListDirectoryStore {
    suspend fun dismissConflict(id: String) = Unit
    fun observe(): Flow<ListDirectory>
    suspend fun select(id: String)
    suspend fun create(name: String)
    suspend fun rename(id: String, name: String) = Unit
    /** Deletes a list this phone's account owns, or leaves one it was added to. */
    suspend fun remove(id: String) = Unit
}
object NoListDirectory : ListDirectoryStore {
    override fun observe() = flowOf(ListDirectory())
    override suspend fun select(id: String) = Unit
    override suspend fun create(name: String) = Unit
}

/** How the account's lists last synced. [SIGN_IN] means the server no longer accepts this phone's credential. */
enum class SyncStatus { SYNCED, OFFLINE, SIGN_IN, UPGRADE, CAPACITY }

package io.theficos.usuals.ui.list

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * [shared] means other people can open the list; [editor] means someone else owns it, so this phone can leave it but not delete it.
 * An [archived] list sits in the picker's Archived section; [archivedByOwner] means only the owner can restore it.
 */
data class NamedList(val id: String, val name: String, val shared: Boolean, val imported: Boolean = false, val editor: Boolean = false,
    val archived: Boolean = false, val archivedByOwner: Boolean = false,
    val color: io.theficos.usuals.model.ListColor? = null, val colorRevision: String? = null)
/** [unused] means the selected list is the blank starting list nothing was added to: archiving it would only start another. */
data class ListDirectory(val selected: String = "", val lists: List<NamedList> = emptyList(), val pending: Int = 0, val conflicts: List<ListConflict> = emptyList(), val status: SyncStatus? = null,
    val unused: Boolean = false)
data class ListConflict(val id: String, val itemId: String?, val current: Map<String, String?>, val incoming: Map<String, String?>, val kind: String)
interface ListDirectoryStore {
    suspend fun dismissConflict(id: String) = Unit
    fun observe(): Flow<ListDirectory>
    suspend fun select(id: String)
    suspend fun create(name: String)
    suspend fun rename(id: String, name: String) = Unit
    suspend fun setColor(id: String, color: io.theficos.usuals.model.ListColor?, expected: String?) = Unit
    /** Deletes a list this phone's account owns, or leaves one it was added to. */
    suspend fun remove(id: String) = Unit
    /** Archives a list, for everyone when this account owns it, or restores it. */
    suspend fun archive(id: String, archived: Boolean) = Unit
}
object NoListDirectory : ListDirectoryStore {
    override fun observe() = flowOf(ListDirectory())
    override suspend fun select(id: String) = Unit
    override suspend fun create(name: String) = Unit
}

/** How the account's lists last synced. [SIGN_IN] means the server no longer accepts this phone's credential. */
enum class SyncStatus { SYNCED, OFFLINE, SIGN_IN, UPGRADE, CAPACITY }

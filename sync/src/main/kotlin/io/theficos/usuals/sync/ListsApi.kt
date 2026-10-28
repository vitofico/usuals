package io.theficos.usuals.sync

import kotlinx.serialization.Serializable

/** Wire types for server-held lists. See docs/sync/lists-v2.md. Roles are "owner" or "editor". */
@Serializable data class Person(val id: String, val name: String, val detail: String? = null)
@Serializable data class Member(val id: String, val name: String, val role: String)
/**
 * [archived] says the list is archived for the caller: the owner archived it for everyone ([archivedByOwner]), or
 * the caller, an editor, archived it for themself. Only the owner restores a list the owner archived.
 */
@Serializable data class ListActivity(val id: String, val kind: String, val count: Int, val at: Long)
@Serializable data class SharedList(val id: String, val seq: Long, val role: String, val owner: Person, val members: List<Member>, val projection: Projection,
    val archived: Boolean = false, val archivedByOwner: Boolean = false, val activity: List<ListActivity> = emptyList())
@Serializable data class RemovedList(val id: String, val seq: Long)
@Serializable data class ListChanges(val cursor: String, val reset: Boolean, val hasMore: Boolean, val lists: List<SharedList>, val removed: List<RemovedList>)
@Serializable data class CreateList(val projection: Projection)
@Serializable data class IntentBatch(val intents: List<Intent>)
/** Outcome is the lowercase [Outcome] name. */
@Serializable data class IntentResult(val id: String, val outcome: String)
@Serializable data class IntentBatchResult(val results: List<IntentResult>, val list: SharedList)
@Serializable data class AddMember(val userId: String)
@Serializable data class ArchiveList(val archived: Boolean)
@Serializable data class People(val people: List<Person>)

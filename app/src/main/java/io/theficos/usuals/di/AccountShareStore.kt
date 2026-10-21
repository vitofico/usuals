package io.theficos.usuals.di

import io.theficos.usuals.data.local.ListRepository
import io.theficos.usuals.sync.Member
import io.theficos.usuals.sync.Person
import io.theficos.usuals.sync.Wire
import io.theficos.usuals.ui.share.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** The share sheet over the members the server last reported for a list. */
class AccountShareStore(private val lists: ListRepository, private val sync: ListSync) : ShareStore {
    override fun observe(list: String): Flow<ShareTarget?> = combine(lists.observe(list), sync.account) { entry, account ->
        entry?.let { ShareTarget(it.name, roster(it.owner, it.members), account?.userId) }
    }
    override suspend fun people(query: String) = sync.people(query).map { SharePerson(it.id, it.name, it.detail) }
    override suspend fun add(list: String, person: String) = sync.addMember(list, person)
    override suspend fun remove(list: String, person: String) = sync.removeMember(list, person)

    /** The owner first, whether or not the server lists them among the members. */
    private fun roster(owner: String?, members: String?): List<ShareMember> {
        val listed = members?.let { runCatching { Wire.json.decodeFromString<List<Member>>(it) }.getOrNull() }.orEmpty().map { ShareMember(it.id, it.name, it.role == "owner") }
        val head = owner?.let { runCatching { Wire.json.decodeFromString<Person>(it) }.getOrNull() }?.takeIf { person -> listed.none { it.id == person.id } }
        return listOfNotNull(head?.let { ShareMember(it.id, it.name, owner = true) }) + listed.sortedByDescending { it.owner }
    }
}

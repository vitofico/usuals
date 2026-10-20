package io.theficos.usuals.data.local

import androidx.room.withTransaction
import java.util.UUID

class ListRepository(private val database: UsualsDatabase, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    fun observe() = database.listDao().observe()
    fun observeSelected() = database.listDao().observeSelected()
    suspend fun selected() = database.listDao().selected()
    suspend fun create(name: String): String = database.withTransaction {
        require(name.trim().isNotEmpty() && name.length <= 100)
        val id = newId()
        val household = database.listDao().localHousehold() ?: HouseholdEntity(newId()).also { database.listDao().household(it) }
        database.listDao().insert(ListEntity(id, household.id, name.trim()))
        database.listDao().select(ListSelectionEntity(listId = id))
        id
    }
    suspend fun prepareShare(household: String, source: String, includeHistory: Boolean, replica: LocalReplica, target: String = newId()): String = database.withTransaction {
        database.listDao().find(target)?.let { existing ->
            require(existing.shared && existing.householdId == household)
            return@withTransaction target
        }
        val original = requireNotNull(database.listDao().find(source))
        require(!original.shared && !original.archived)
        val id = target
        database.listDao().household(HouseholdEntity(household, shared = true))
        database.listDao().insert(ListEntity(id, household, original.name, shared = true))
        replica.copyTo(source, id, includeHistory)
        id
    }
    suspend fun installShared(household: String, id: String, checkpoint: io.theficos.usuals.sync.ListCheckpoint,
        epoch: Long, manifest: String, replica: LocalReplica) = database.withTransaction {
        val initial = io.theficos.usuals.sync.SnapshotCodec.decode(checkpoint.payload)
        database.listDao().household(HouseholdEntity(household, shared = true))
        val existing = database.listDao().find(id)
        require(existing == null || existing.shared && existing.householdId == household)
        if (existing == null) database.listDao().insert(ListEntity(id, household, initial.name, shared = true))
        replica.confirm(id, checkpoint, epoch, manifest)
    }
    suspend fun importFrozen(copy: io.theficos.usuals.sync.FrozenCopy, name: String, replica: LocalReplica): String = database.withTransaction {
        val id = create(name)
        replica.installLocalFacts(id, copy)
        id
    }
    suspend fun retainCopy(source: String, replica: LocalReplica, name: String? = null): String = database.withTransaction {
        val original = requireNotNull(database.listDao().find(source)); require(original.shared)
        val household = database.listDao().localHousehold() ?: HouseholdEntity(newId()).also { database.listDao().household(it) }
        require(name == null || name.isNotBlank() && name.length <= 100)
        val id = newId()
        database.listDao().insert(ListEntity(id, household.id, name ?: original.name, archiveSource = source))
        replica.copyTo(source, id, includeHistory = true)
        database.historyDao().saveLocalPreferences(database.historyDao().preferences(source).map { LocalPreferenceEntity(id, it.key, it.muted, it.snoozedUntil) })
        database.listDao().select(ListSelectionEntity(listId = id))
        id
    }
    suspend fun removeLocalCopy(id: String) = database.withTransaction {
        require(database.listDao().find(id)?.shared == true)
        val sql = database.openHelper.writableDatabase
        for (table in listOf("items", "trip", "purchase", "item_pref", "local_item_pref", "item_alias", "sync_projection", "sync_outbox", "sync_received", "sync_cursor", "conflict_dismissal"))
            sql.execSQL("DELETE FROM `$table` WHERE listId = ?", arrayOf(id))
        sql.execSQL("DELETE FROM list_scope WHERE id = ?", arrayOf(id))
        if (database.listDao().selected() == id) {
            val target = database.listDao().all().firstOrNull { !it.archived } ?: run {
                val household = database.listDao().localHousehold() ?: HouseholdEntity(newId()).also { database.listDao().household(it) }
                ListEntity(newId(), household.id, "").also { database.listDao().insert(it) }
            }
            database.listDao().select(ListSelectionEntity(listId = target.id))
        }
    }
    suspend fun all() = database.listDao().all()
    suspend fun select(id: String) = database.withTransaction {
        require(database.listDao().find(id)?.archived == false)
        database.listDao().select(ListSelectionEntity(listId = id))
    }
}

package io.theficos.usuals.data.local

import androidx.room.withTransaction
import java.util.UUID

class ListRepository(private val database: UsualsDatabase, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    fun observe() = database.listDao().observe()
    fun observe(id: String) = database.listDao().observe(id)
    fun observeSelected() = database.listDao().observeSelected()
    suspend fun selected() = database.listDao().selected()
    suspend fun create(name: String): String = database.withTransaction {
        require(name.trim().isNotEmpty() && name.length <= 100)
        val id = newId()
        database.listDao().insert(ListEntity(id, name.trim()))
        database.listDao().select(ListSelectionEntity(listId = id))
        id
    }
    suspend fun find(id: String) = database.listDao().find(id)
    suspend fun all() = database.listDao().all()
    suspend fun select(id: String) = database.withTransaction {
        requireNotNull(database.listDao().find(id))
        database.listDao().select(ListSelectionEntity(listId = id))
    }
}

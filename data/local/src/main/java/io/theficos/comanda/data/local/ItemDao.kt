package io.theficos.comanda.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Query("SELECT * FROM items WHERE listId = :listId AND deletedAt IS NULL ORDER BY position, createdAt, id")
    fun observeActive(listId: String = LOCAL_LIST_ID): Flow<List<ItemEntity>>
    @Query("SELECT * FROM items WHERE listId = :listId AND deletedAt IS NULL ORDER BY position, createdAt, id")
    suspend fun active(listId: String = LOCAL_LIST_ID): List<ItemEntity>
    @Query("SELECT * FROM items WHERE listId = :listId AND id = :id")
    suspend fun find(id: String, listId: String = LOCAL_LIST_ID): ItemEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ItemEntity)
    @Query("DELETE FROM items WHERE listId = :listId AND id = :id")
    suspend fun removeProjectionRow(id: String, listId: String)
    @Query("SELECT MAX(position) FROM items WHERE listId = :listId")
    suspend fun maxPosition(listId: String = LOCAL_LIST_ID): Double?
    @Query("DELETE FROM items WHERE listId = :listId AND listId NOT IN (SELECT id FROM list_scope WHERE shared = 1) AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun reapTombstones(cutoff: Long, listId: String = LOCAL_LIST_ID): Int
}

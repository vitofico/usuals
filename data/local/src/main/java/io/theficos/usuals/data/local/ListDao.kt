package io.theficos.usuals.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {
    @Query("SELECT * FROM list_scope ORDER BY rowid")
    fun observe(): Flow<List<ListEntity>>
    @Query("SELECT * FROM list_scope ORDER BY rowid")
    suspend fun all(): List<ListEntity>
    @Query("SELECT * FROM list_scope WHERE id = :id")
    suspend fun find(id: String): ListEntity?
    @Query("SELECT * FROM list_scope WHERE id = :id")
    fun observe(id: String): Flow<ListEntity?>
    @Query("SELECT COALESCE((SELECT listId FROM list_selection WHERE singleton = 1), '00000000-0000-4000-8000-000000000001')")
    suspend fun selected(): String
    @Query("SELECT COALESCE((SELECT listId FROM list_selection WHERE singleton = 1), '00000000-0000-4000-8000-000000000001')")
    fun observeSelected(): Flow<String>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(list: ListEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun select(selection: ListSelectionEntity)
    @Update
    suspend fun update(list: ListEntity)
}

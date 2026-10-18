package io.theficos.comanda.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ListDao {
    @Query("SELECT * FROM list_scope WHERE archived = 0 ORDER BY rowid")
    fun observe(): Flow<List<ListEntity>>
    @Query("SELECT * FROM list_scope ORDER BY rowid")
    suspend fun all(): List<ListEntity>
    @Query("SELECT * FROM list_scope WHERE id = :id")
    suspend fun find(id: String): ListEntity?
    @Query("SELECT COALESCE((SELECT listId FROM list_selection WHERE singleton = 1), '00000000-0000-4000-8000-000000000001')")
    suspend fun selected(): String
    @Query("SELECT COALESCE((SELECT listId FROM list_selection WHERE singleton = 1), '00000000-0000-4000-8000-000000000001')")
    fun observeSelected(): Flow<String>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(list: ListEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun select(selection: ListSelectionEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun household(household: HouseholdEntity)
    @Query("SELECT * FROM household WHERE shared = 0 ORDER BY rowid LIMIT 1")
    suspend fun localHousehold(): HouseholdEntity?
    @Update
    suspend fun update(list: ListEntity)
}

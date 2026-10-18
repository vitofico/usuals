package io.theficos.usuals.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @RawQuery(observedEntities = [ListSelectionEntity::class, ListEntity::class, ItemEntity::class,
        ProjectionEntity::class, TripEntity::class, PurchaseEntity::class, SharedPreferenceEntity::class, LocalPreferenceEntity::class, ItemAliasEntity::class])
    fun observeShoppingInvalidations(query: SupportSQLiteQuery): Flow<Int>

    @Query("SELECT * FROM trip WHERE listId = :listId ORDER BY closedAt DESC, id ASC")
    suspend fun trips(listId: String = LOCAL_LIST_ID): List<TripEntity>
    @Query("SELECT * FROM purchase WHERE listId = :listId ORDER BY boughtAt DESC, id ASC")
    suspend fun purchases(listId: String = LOCAL_LIST_ID): List<PurchaseEntity>
    @Query("SELECT k.`key`, p.category, p.repeatEveryDays, k.listId, COALESCE(l.muted, 0) AS muted, l.snoozedUntil FROM (SELECT listId, `key` FROM item_pref UNION SELECT listId, `key` FROM local_item_pref) k LEFT JOIN item_pref p ON p.listId = k.listId AND p.`key` = k.`key` LEFT JOIN local_item_pref l ON l.listId = k.listId AND l.`key` = k.`key` WHERE k.listId = :listId ORDER BY k.`key`")
    suspend fun preferences(listId: String = LOCAL_LIST_ID): List<ItemPreferenceEntity>
    @Query("SELECT * FROM item_alias WHERE listId = :listId ORDER BY fromKey")
    suspend fun aliases(listId: String = LOCAL_LIST_ID): List<ItemAliasEntity>
    @Query("SELECT * FROM items WHERE listId = :listId ORDER BY position, createdAt, id")
    suspend fun allItems(listId: String = LOCAL_LIST_ID): List<ItemEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrip(trip: TripEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPurchases(purchases: List<PurchaseEntity>)
    @Query("SELECT * FROM item_pref WHERE listId = :listId ORDER BY `key`")
    suspend fun sharedPreferences(listId: String): List<SharedPreferenceEntity>
    @Query("DELETE FROM item_pref WHERE listId = :listId AND `key` = :key")
    suspend fun removeSharedPreference(key: String, listId: String)
    @Query("DELETE FROM item_alias WHERE listId = :listId AND fromKey = :key")
    suspend fun removeAlias(key: String, listId: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSharedPreferences(preferences: List<SharedPreferenceEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLocalPreferences(preferences: List<LocalPreferenceEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAliases(aliases: List<ItemAliasEntity>)

    @Query("DELETE FROM purchase WHERE listId = :listId AND id = :id")
    suspend fun removePurchase(id: String, listId: String = LOCAL_LIST_ID)
    @Query("DELETE FROM trip WHERE listId = :listId AND id = :id")
    suspend fun removeTrip(id: String, listId: String = LOCAL_LIST_ID): Int
}

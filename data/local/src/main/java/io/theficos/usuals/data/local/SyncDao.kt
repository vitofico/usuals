package io.theficos.usuals.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    /** Everything the server has yet to receive: lists to create and pending intents. */
    @Query("SELECT id FROM list_scope WHERE synced = 0 OR created = 0 UNION ALL SELECT o.listId || ':' || o.intentId FROM sync_outbox o JOIN list_scope l ON l.id=o.listId WHERE l.synced=1 AND o.acknowledged=0 AND NOT EXISTS (SELECT 1 FROM conflict_dismissal d WHERE d.listId=o.listId AND d.intentId=o.intentId)")
    fun observeOutgoing(): Flow<List<String>>
    @Query("SELECT DISTINCT o.listId FROM sync_outbox o WHERE o.acknowledged=0 AND NOT EXISTS (SELECT 1 FROM conflict_dismissal d WHERE d.listId=o.listId AND d.intentId=o.intentId)")
    suspend fun pendingLists(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun dismiss(conflict: ConflictDismissalEntity)
    @Query("SELECT intentId FROM conflict_dismissal WHERE listId = :listId")
    fun observeDismissals(listId: String): Flow<List<String>>
    @Query("SELECT intentId FROM conflict_dismissal WHERE listId = :listId")
    suspend fun dismissals(listId: String): List<String>

    @Query("SELECT * FROM sync_projection WHERE listId = :listId")
    suspend fun projection(listId: String): ProjectionEntity?
    @Query("SELECT * FROM sync_projection WHERE listId = :listId")
    fun observeProjection(listId: String): Flow<ProjectionEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProjection(projection: ProjectionEntity)
    @Query("SELECT * FROM sync_outbox WHERE listId = :listId ORDER BY ordinal")
    suspend fun pending(listId: String): List<OutboxEntity>
    @Query("SELECT * FROM sync_outbox WHERE listId = :listId ORDER BY ordinal")
    fun observePending(listId: String): Flow<List<OutboxEntity>>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(intent: OutboxEntity)
    @Update
    suspend fun update(intent: OutboxEntity)
    @Query("UPDATE sync_outbox SET acknowledged = 1 WHERE listId = :listId AND intentId IN (:ids)")
    suspend fun acknowledge(listId: String, ids: Collection<String>)
    @Query("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM sync_outbox WHERE listId = :listId")
    suspend fun nextOrdinal(listId: String): Long

    @Query("SELECT * FROM sync_feed WHERE singleton = 1")
    suspend fun feed(): FeedEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFeed(feed: FeedEntity)
}

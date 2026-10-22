package io.theficos.usuals.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    /** Everything the server has yet to receive: lists to create, with how much a local list holds, and pending intents. */
    @Query("SELECT l.id || ':' || (SELECT COUNT(*) FROM sync_outbox o WHERE o.listId = l.id) FROM list_scope l WHERE l.synced = 0 OR l.created = 0 UNION ALL SELECT o.listId || ':' || o.intentId FROM sync_outbox o JOIN list_scope l ON l.id=o.listId WHERE l.synced=1 AND o.acknowledged=0 AND NOT EXISTS (SELECT 1 FROM conflict_dismissal d WHERE d.listId=o.listId AND d.intentId=o.intentId)")
    fun observeOutgoing(): Flow<List<String>>
    @Query("SELECT DISTINCT o.listId FROM sync_outbox o WHERE o.acknowledged=0 AND NOT EXISTS (SELECT 1 FROM conflict_dismissal d WHERE d.listId=o.listId AND d.intentId=o.intentId)")
    suspend fun pendingLists(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun dismiss(conflict: ConflictDismissalEntity)
    @Query("SELECT intentId FROM conflict_dismissal WHERE listId = :listId")
    fun observeDismissals(listId: String): Flow<List<String>>
    @Query("SELECT intentId FROM conflict_dismissal WHERE listId = :listId")
    suspend fun dismissals(listId: String): List<String>

    /** A copy's parts in order. Android re-runs a query for each cursor window it fills; the transaction keeps them on one save. */
    @Transaction
    @Query("SELECT text FROM sync_projection WHERE listId = :listId AND optimistic = :optimistic ORDER BY part")
    suspend fun projection(listId: String, optimistic: Boolean): List<String>
    @Transaction
    @Query("SELECT text FROM sync_projection WHERE listId = :listId AND optimistic = 1 ORDER BY part")
    fun observeOptimistic(listId: String): Flow<List<String>>
    @Query("SELECT EXISTS(SELECT 1 FROM sync_projection WHERE listId = :listId AND optimistic = 1)")
    suspend fun hasProjection(listId: String): Boolean
    @Query("DELETE FROM sync_projection WHERE listId = :listId AND optimistic = :optimistic")
    suspend fun clearProjection(listId: String, optimistic: Boolean)
    @Insert
    suspend fun insertProjection(parts: List<ProjectionPartEntity>)
    @Query("SELECT * FROM sync_outbox WHERE listId = :listId ORDER BY ordinal")
    suspend fun pending(listId: String): List<OutboxEntity>
    @Query("SELECT * FROM sync_outbox WHERE listId = :listId AND acknowledged = 0 ORDER BY ordinal")
    suspend fun unacknowledged(listId: String): List<OutboxEntity>
    @Query("SELECT intentId, acknowledged FROM sync_outbox WHERE listId = :listId")
    fun observeOutbox(listId: String): Flow<List<OutboxState>>
    @Query("SELECT * FROM sync_outbox WHERE listId = :listId AND intentId IN (:ids)")
    suspend fun intents(listId: String, ids: Collection<String>): List<OutboxEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(intent: OutboxEntity)
    @Update
    suspend fun update(intent: OutboxEntity)
    @Query("UPDATE sync_outbox SET acknowledged = 1 WHERE listId = :listId AND intentId IN (:ids)")
    suspend fun acknowledge(listId: String, ids: Collection<String>)
    /** Acknowledged rows older than the newest [horizon] ordinals, except [keep]. */
    @Query("DELETE FROM sync_outbox WHERE listId = :listId AND acknowledged = 1 AND intentId NOT IN (:keep) AND ordinal <= (SELECT MAX(ordinal) FROM sync_outbox WHERE listId = :listId) - :horizon")
    suspend fun prune(listId: String, keep: Collection<String>, horizon: Int)
    @Query("SELECT COALESCE(MAX(ordinal), 0) + 1 FROM sync_outbox WHERE listId = :listId")
    suspend fun nextOrdinal(listId: String): Long

    @Query("SELECT * FROM sync_feed WHERE singleton = 1")
    suspend fun feed(): FeedEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFeed(feed: FeedEntity)
}

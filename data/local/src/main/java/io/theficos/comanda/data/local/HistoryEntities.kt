package io.theficos.comanda.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import io.theficos.comanda.model.*
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "trip", primaryKeys = ["listId", "id"], indices = [Index("listId", "closedAt")])
data class TripEntity(val id: String, val closedAt: Long, val listId: String = LOCAL_LIST_ID)

@Serializable
@Entity(
    tableName = "purchase",
    primaryKeys = ["listId", "id"],
    foreignKeys = [ForeignKey(
        entity = TripEntity::class, parentColumns = ["listId", "id"], childColumns = ["listId", "tripId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("listId", "tripId"), Index("listId", "key"), Index("listId", "boughtAt")],
)
data class PurchaseEntity(
    val id: String,
    val tripId: String,
    val key: String,
    val name: String,
    val amount: Double? = null,
    val unit: String? = null,
    val gtin: String? = null,
    val boughtAt: Long,
    val fromSuggestion: Boolean = false,
    val normalizerVersion: Int = NORMALIZER_VERSION,
    val listId: String = LOCAL_LIST_ID,
)

@Serializable
data class ItemPreferenceEntity(
    val key: String,
    val category: String? = null,
    val muted: Boolean = false,
    val snoozedUntil: Long? = null,
    val repeatEveryDays: Int? = null,
    val listId: String = LOCAL_LIST_ID,
)

@Serializable
@Entity(tableName = "item_alias", primaryKeys = ["listId", "fromKey"])
data class ItemAliasEntity(val fromKey: String, val toKey: String, val listId: String = LOCAL_LIST_ID)

internal fun TripEntity.domain() = Trip(id, closedAt)
internal fun PurchaseEntity.domain() = Purchase(
    ItemId(id), tripId, key, name,
    amount?.let { amount -> enumOrNull<MeasureUnit>(unit)?.let { Quantity(amount, it) } },
    gtin?.let(Gtin::parse), boughtAt, fromSuggestion, normalizerVersion,
)
internal fun ItemPreferenceEntity.domain() = ItemPreference(key, category, muted, snoozedUntil, repeatEveryDays)
internal fun ItemAliasEntity.domain() = ItemAlias(fromKey, toKey)

internal fun ItemPreference.entity() = ItemPreferenceEntity(key, category, muted, snoozedUntil, repeatEveryDays)

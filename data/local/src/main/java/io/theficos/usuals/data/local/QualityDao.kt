package io.theficos.usuals.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Local product-cache reads and replacement writes. This table contains no user
 * decisions: confirmed names and categories belong to shopping items/preferences.
 * Callers read one answer explicitly; cache updates do not rename live list rows.
 */
@Dao
interface QualityDao {

    /** What is stored for this barcode, whatever state it is in. */
    @Query("SELECT * FROM product_quality WHERE gtin = :gtin LIMIT 1")
    suspend fun find(gtin: String): QualityEntity?

    /** A later answer replaces an earlier one. See the class KDoc for why that is safe. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: QualityEntity)

    /**
     * Empties the cache.
     *
     * Every row here is derived and re-fetchable, so this is the one table in
     * the database that can be thrown away without losing anything: a wipe
     * costs a few HTTP requests and no data. That makes it the safe answer to
     * "the quality data looks wrong" and to a person who would rather the app
     * kept nothing from Open Food Facts on their phone.
     */
    @Query("DELETE FROM product_quality")
    suspend fun clear(): Int

    /** How many barcodes are known about, answers and confirmed absences alike. */
    @Query("SELECT COUNT(*) FROM product_quality")
    suspend fun size(): Int
}

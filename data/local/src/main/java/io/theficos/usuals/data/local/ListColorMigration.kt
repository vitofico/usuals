package io.theficos.usuals.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.theficos.usuals.sync.*

/** Cache both values for the picker without loading every list's shopping projection. */
val MIGRATION_9_10: Migration = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE list_scope ADD COLUMN color TEXT")
        db.execSQL("ALTER TABLE list_scope ADD COLUMN colorRevision TEXT")
        // An older client may already have received shared metadata from a newer phone.
        db.query("SELECT id FROM list_scope").use { lists ->
            while (lists.moveToNext()) {
                val id = lists.getString(0)
                val text = buildString {
                    db.query("SELECT text FROM sync_projection WHERE listId = ? AND optimistic = 1 ORDER BY part", arrayOf(id)).use { parts ->
                        while (parts.moveToNext()) append(parts.getString(0))
                    }
                }
                if (text.isNotEmpty()) {
                    val projection = Wire.json.decodeFromString<Projection>(text)
                    db.execSQL("UPDATE list_scope SET color = ?, colorRevision = ? WHERE id = ?",
                        arrayOf(ListAppearance.color(projection)?.id, ListAppearance.revision(projection), id))
                }
            }
        }
        db.execSQL("DELETE FROM item_pref WHERE `key` = ?", arrayOf(ListAppearance.KEY))
    }
}

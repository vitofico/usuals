package io.theficos.usuals.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Each projection copy moves to its own rows of [PROJECTION_PART] characters. The split runs inside SQLite: a row past
 * the 2 MB cursor window, which is what this migration exists for, can be copied there but never read out.
 */
val MIGRATION_7_8: Migration = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE `sync_projection_v8` (`listId` TEXT NOT NULL, `optimistic` INTEGER NOT NULL, `part` INTEGER NOT NULL, `text` TEXT NOT NULL, PRIMARY KEY(`listId`, `optimistic`, `part`))")
        db.execSQL("""INSERT INTO `sync_projection_v8` (`listId`, `optimistic`, `part`, `text`)
            WITH RECURSIVE parts(listId, optimistic, part, rest) AS (
                SELECT `listId`, 0, 0, `confirmed` FROM `sync_projection`
                UNION ALL SELECT `listId`, 1, 0, `optimistic` FROM `sync_projection`
                UNION ALL SELECT listId, optimistic, part + 1, substr(rest, $PROJECTION_PART + 1) FROM parts WHERE length(rest) > $PROJECTION_PART)
            SELECT listId, optimistic, part, substr(rest, 1, $PROJECTION_PART) FROM parts""")
        db.execSQL("DROP TABLE `sync_projection`")
        db.execSQL("ALTER TABLE `sync_projection_v8` RENAME TO `sync_projection`")
    }
}

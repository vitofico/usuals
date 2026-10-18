package io.theficos.usuals.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive cache-only migration. Shopping rows and confirmed aliases are unchanged. */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (column in listOf("brands", "packageSize", "ingredients", "traces")) {
            db.execSQL("ALTER TABLE product_quality ADD COLUMN `$column` TEXT")
        }
        db.execSQL("ALTER TABLE product_quality ADD COLUMN sourceModifiedAtEpochSeconds INTEGER")
    }
}

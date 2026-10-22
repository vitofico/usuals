package io.theficos.usuals.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema 9 records two facts the product cache used to drop: that Nutri-Score
 * does not apply to a product, and that a barcode belongs to one of Open Food
 * Facts' non-food sister databases. Both columns are nullable and added empty,
 * so every cached row keeps its meaning and only a refresh fills them in.
 */
val MIGRATION_8_9: Migration = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE product_quality ADD COLUMN nutriScoreNotApplicable INTEGER")
        db.execSQL("ALTER TABLE product_quality ADD COLUMN productType TEXT")
    }
}

package io.theficos.usuals.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Households are gone (ADR 0017). Every list, a household one included, becomes a local list that
 * keeps exactly what its screen showed: its pending household intents are folded into its state, so
 * signing in uploads it whole. Shopping rows, history and preferences are untouched.
 */
val MIGRATION_6_7: Migration = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE `list_scope_v7` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `archiveSource` TEXT, `synced` INTEGER NOT NULL, `created` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `role` TEXT, `owner` TEXT, `members` TEXT, `stale` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("INSERT INTO `list_scope_v7` (`id`, `name`, `archiveSource`, `synced`, `created`, `seq`, `stale`) SELECT `id`, `name`, `archiveSource`, 0, 0, 0, 0 FROM `list_scope` ORDER BY rowid")
        db.execSQL("DROP TABLE `list_scope`")
        db.execSQL("ALTER TABLE `list_scope_v7` RENAME TO `list_scope`")
        db.execSQL("UPDATE `sync_projection` SET `confirmed` = `optimistic`")
        db.execSQL("CREATE TABLE `sync_outbox_v7` (`listId` TEXT NOT NULL, `intentId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `command` TEXT NOT NULL, `acknowledged` INTEGER NOT NULL, PRIMARY KEY(`listId`, `intentId`))")
        db.execSQL("INSERT INTO `sync_outbox_v7` (`listId`, `intentId`, `ordinal`, `command`, `acknowledged`) SELECT `listId`, `intentId`, `ordinal`, `command`, 1 FROM `sync_outbox`")
        db.execSQL("DROP TABLE `sync_outbox`")
        db.execSQL("ALTER TABLE `sync_outbox_v7` RENAME TO `sync_outbox`")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_listId_ordinal` ON `sync_outbox` (`listId`, `ordinal`)")
        for (table in listOf("household", "member_ref", "device_ref", "sync_received", "sync_cursor")) db.execSQL("DROP TABLE IF EXISTS `$table`")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_feed` (`singleton` INTEGER NOT NULL, `cursor` TEXT, `resetting` INTEGER NOT NULL, PRIMARY KEY(`singleton`))")
    }
}

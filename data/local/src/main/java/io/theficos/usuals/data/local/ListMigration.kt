package io.theficos.usuals.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

internal fun initializeLocalList(db: SupportSQLiteDatabase, listId: String = UUID.randomUUID().toString()) {
    // Reopen must preserve a valid selection, including a synced list.
    db.query("SELECT list_scope.id FROM list_selection JOIN list_scope ON list_scope.id = list_selection.listId WHERE singleton = 1").use {
        if (it.moveToFirst()) return
    }
    val selected = db.query("SELECT id FROM list_scope ORDER BY rowid LIMIT 1").use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: listId.also { db.execSQL("INSERT INTO list_scope (id, name, synced, created, seq, stale) VALUES (?, '', 0, 0, 0, 0)", arrayOf(it)) }
    db.execSQL("INSERT OR REPLACE INTO list_selection (singleton, listId) VALUES (1, ?)", arrayOf(selected))
}

/** Room wraps this entire copy/swap and its version update in one SQLite transaction. */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val householdId = UUID.randomUUID().toString()
        val listId = UUID.randomUUID().toString()
        db.execSQL("""DROP INDEX IF EXISTS `index_items_normalizedName`""")
        db.execSQL("""DROP INDEX IF EXISTS `index_items_deletedAt`""")
        db.execSQL("""DROP INDEX IF EXISTS `index_trip_closedAt`""")
        db.execSQL("""DROP INDEX IF EXISTS `index_purchase_tripId`""")
        db.execSQL("""DROP INDEX IF EXISTS `index_purchase_key`""")
        db.execSQL("""DROP INDEX IF EXISTS `index_purchase_boughtAt`""")
        db.execSQL("""ALTER TABLE `items` RENAME TO `items_v4`""")
        db.execSQL("""ALTER TABLE `trip` RENAME TO `trip_v4`""")
        db.execSQL("""ALTER TABLE `purchase` RENAME TO `purchase_v4`""")
        db.execSQL("""ALTER TABLE `item_pref` RENAME TO `item_pref_v4`""")
        db.execSQL("""ALTER TABLE `item_alias` RENAME TO `item_alias_v4`""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `items` (`id` TEXT NOT NULL, `rawText` TEXT NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `quantityAmount` REAL, `quantityUnit` TEXT, `note` TEXT, `gtin` TEXT, `checked` INTEGER NOT NULL, `checkedAt` INTEGER, `fromSuggestion` INTEGER NOT NULL, `store` TEXT, `position` REAL NOT NULL, `addedBy` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, `listId` TEXT NOT NULL, PRIMARY KEY(`listId`, `id`))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_items_listId_normalizedName` ON `items` (`listId`, `normalizedName`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_items_listId_deletedAt` ON `items` (`listId`, `deletedAt`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `trip` (`id` TEXT NOT NULL, `closedAt` INTEGER NOT NULL, `listId` TEXT NOT NULL, PRIMARY KEY(`listId`, `id`))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_trip_listId_closedAt` ON `trip` (`listId`, `closedAt`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `purchase` (`id` TEXT NOT NULL, `tripId` TEXT NOT NULL, `key` TEXT NOT NULL, `name` TEXT NOT NULL, `amount` REAL, `unit` TEXT, `gtin` TEXT, `boughtAt` INTEGER NOT NULL, `fromSuggestion` INTEGER NOT NULL, `normalizerVersion` INTEGER NOT NULL, `listId` TEXT NOT NULL, PRIMARY KEY(`listId`, `id`), FOREIGN KEY(`listId`, `tripId`) REFERENCES `trip`(`listId`, `id`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_purchase_listId_tripId` ON `purchase` (`listId`, `tripId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_purchase_listId_key` ON `purchase` (`listId`, `key`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_purchase_listId_boughtAt` ON `purchase` (`listId`, `boughtAt`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `item_pref` (`listId` TEXT NOT NULL, `key` TEXT NOT NULL, `category` TEXT, `repeatEveryDays` INTEGER, PRIMARY KEY(`listId`, `key`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `local_item_pref` (`listId` TEXT NOT NULL, `key` TEXT NOT NULL, `muted` INTEGER NOT NULL, `snoozedUntil` INTEGER, PRIMARY KEY(`listId`, `key`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `item_alias` (`fromKey` TEXT NOT NULL, `toKey` TEXT NOT NULL, `listId` TEXT NOT NULL, PRIMARY KEY(`listId`, `fromKey`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `household` (`id` TEXT NOT NULL, `shared` INTEGER NOT NULL, `routing` TEXT, PRIMARY KEY(`id`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `list_scope` (`id` TEXT NOT NULL, `householdId` TEXT NOT NULL, `name` TEXT NOT NULL, `shared` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `archiveSource` TEXT, PRIMARY KEY(`id`))""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_list_scope_householdId` ON `list_scope` (`householdId`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `list_selection` (`singleton` INTEGER NOT NULL, `listId` TEXT NOT NULL, PRIMARY KEY(`singleton`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `sync_projection` (`listId` TEXT NOT NULL, `confirmed` TEXT NOT NULL, `optimistic` TEXT NOT NULL, PRIMARY KEY(`listId`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `sync_outbox` (`listId` TEXT NOT NULL, `intentId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, `command` TEXT NOT NULL, `envelope` BLOB, `acknowledged` INTEGER NOT NULL, PRIMARY KEY(`listId`, `intentId`))""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_listId_ordinal` ON `sync_outbox` (`listId`, `ordinal`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `sync_received` (`listId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `intentId` TEXT NOT NULL, `envelopeId` TEXT NOT NULL, `digest` TEXT NOT NULL, `head` TEXT NOT NULL, PRIMARY KEY(`listId`, `seq`))""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_received_listId_intentId` ON `sync_received` (`listId`, `intentId`)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_received_listId_envelopeId` ON `sync_received` (`listId`, `envelopeId`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `sync_cursor` (`listId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `head` TEXT NOT NULL, `epoch` INTEGER NOT NULL, `manifestHash` TEXT NOT NULL, PRIMARY KEY(`listId`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `member_ref` (`householdId` TEXT NOT NULL, `id` TEXT NOT NULL, `proof` TEXT NOT NULL, PRIMARY KEY(`householdId`, `id`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `device_ref` (`householdId` TEXT NOT NULL, `id` TEXT NOT NULL, `memberId` TEXT NOT NULL, `proof` TEXT NOT NULL, PRIMARY KEY(`householdId`, `id`))""")
        db.execSQL("""INSERT INTO `items` (`id`, `rawText`, `name`, `normalizedName`, `quantityAmount`, `quantityUnit`, `note`, `gtin`, `checked`, `checkedAt`, `fromSuggestion`, `store`, `position`, `addedBy`, `createdAt`, `updatedAt`, `deletedAt`, `listId`) SELECT `id`, `rawText`, `name`, `normalizedName`, `quantityAmount`, `quantityUnit`, `note`, `gtin`, `checked`, `checkedAt`, `fromSuggestion`, `store`, `position`, `addedBy`, `createdAt`, `updatedAt`, `deletedAt`, ? FROM `items_v4`""", arrayOf(listId))
        db.execSQL("""INSERT INTO `trip` (`id`, `closedAt`, `listId`) SELECT `id`, `closedAt`, ? FROM `trip_v4`""", arrayOf(listId))
        db.execSQL("""INSERT INTO `purchase` (`id`, `tripId`, `key`, `name`, `amount`, `unit`, `gtin`, `boughtAt`, `fromSuggestion`, `normalizerVersion`, `listId`) SELECT `id`, `tripId`, `key`, `name`, `amount`, `unit`, `gtin`, `boughtAt`, `fromSuggestion`, `normalizerVersion`, ? FROM `purchase_v4`""", arrayOf(listId))
        db.execSQL("""INSERT INTO `item_pref` (`listId`, `key`, `category`, `repeatEveryDays`) SELECT ?, `key`, `category`, `repeatEveryDays` FROM `item_pref_v4`""", arrayOf(listId))
        db.execSQL("""INSERT INTO `local_item_pref` (`listId`, `key`, `muted`, `snoozedUntil`) SELECT ?, `key`, `muted`, `snoozedUntil` FROM `item_pref_v4`""", arrayOf(listId))
        db.execSQL("""INSERT INTO `item_alias` (`fromKey`, `toKey`, `listId`) SELECT `fromKey`, `toKey`, ? FROM `item_alias_v4`""", arrayOf(listId))
        db.execSQL("""DROP TABLE `purchase_v4`""")
        db.execSQL("""DROP TABLE `trip_v4`""")
        db.execSQL("""DROP TABLE `items_v4`""")
        db.execSQL("""DROP TABLE `item_pref_v4`""")
        db.execSQL("""DROP TABLE `item_alias_v4`""")
        db.execSQL("CREATE TABLE IF NOT EXISTS conflict_dismissal (listId TEXT NOT NULL, intentId TEXT NOT NULL, PRIMARY KEY(listId, intentId))")
        db.execSQL("INSERT INTO household (id, shared, routing) VALUES (?, 0, NULL)", arrayOf(householdId))
        db.execSQL("INSERT INTO list_scope (id, householdId, name, shared, archived, archiveSource) VALUES (?, ?, '', 0, 0, NULL)", arrayOf(listId, householdId))
        db.execSQL("INSERT OR REPLACE INTO list_selection (singleton, listId) VALUES (1, ?)", arrayOf(listId))
    }
}

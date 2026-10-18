package io.theficos.comanda.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The device's copy of everything.
 *
 * - `items` is the list. It is the authoritative copy, and the UI reads it
 *   without ever waiting on a network.
 * - `product_quality` is what Open Food Facts said about a scanned barcode. It
 *   never leaves this file, because it is not ours: every row is derived from
 *   Open Food Facts under the ODbL. See `QualityEntity`.
 *
 * `exportSchema = true`, and the emitted JSON under `data/local/schemas` is
 * committed. It is what a future migration is written against.
 *
 * **Version 4 is a fresh start.** Versions 1 to 3 belonged to the retailer
 * build, which was never released: they held an alias cache, a resolution log
 * and an outbox that this app no longer has. A development install on one of
 * them is wiped once on upgrade, and that is the only destructive step this
 * database will ever take. From 4 on, a schema change ships a migration and a
 * test, because a missing migration should fail loudly in development rather
 * than delete somebody's shopping history in a kitchen.
 */
@Database(
    entities = [
        ItemEntity::class,
        QualityEntity::class,
        TripEntity::class,
        PurchaseEntity::class,
        SharedPreferenceEntity::class,
        LocalPreferenceEntity::class,
        ItemAliasEntity::class,
        HouseholdEntity::class, ListEntity::class, ListSelectionEntity::class,
        ProjectionEntity::class, OutboxEntity::class, ReceivedEntity::class, CursorEntity::class,
        MemberEntity::class, DeviceEntity::class, ConflictDismissalEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class ComandaDatabase : RoomDatabase() {

    abstract fun itemDao(): ItemDao

    abstract fun qualityDao(): QualityDao

    abstract fun historyDao(): HistoryDao
    abstract fun listDao(): ListDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val NAME: String = "comanda.db"

        /** The unreleased retailer schemas. See the class KDoc. */
        private val RETIRED_VERSIONS: IntArray = intArrayOf(1, 2, 3)

        fun build(context: Context, name: String = NAME): ComandaDatabase =
            Room.databaseBuilder(context.applicationContext, ComandaDatabase::class.java, name)
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigrationFrom(*RETIRED_VERSIONS)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) { initializeLocalList(db) }
                    override fun onOpen(db: SupportSQLiteDatabase) { initializeLocalList(db) }
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        // Room 2.6 only drops tables in the new schema, so removed
                        // entities otherwise survive the unreleased-schema reset.
                        for (table in listOf("household_alias", "resolution_log", "outbox")) {
                            db.execSQL("DROP TABLE IF EXISTS `$table`")
                        }
                    }
                })
                .build()
    }
}

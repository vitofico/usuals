package io.theficos.usuals.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ListMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun oldDatabase(): SQLiteDatabase {
        context.deleteDatabase(UsualsDatabase.NAME)
        val file = context.getDatabasePath(UsualsDatabase.NAME).also { it.parentFile!!.mkdirs() }
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        val schema = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("io.theficos.usuals.data.local.UsualsDatabase/4.json")!!.bufferedReader().readText()).jsonObject.getValue("database").jsonObject
        for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
            val table = entity.getValue("tableName").jsonPrimitive.content
            old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            for (index in entity.getValue("indices").jsonArray.map { it.jsonObject }) old.execSQL(index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
        }
        old.version = 4
        return old
    }
    @Test fun migrationPreservesAllFactsAndReopensWithScopedForeignKeys() = runTest {
        oldDatabase().use { old ->
            old.execSQL("INSERT INTO items (id,rawText,name,normalizedName,quantityAmount,quantityUnit,note,gtin,checked,checkedAt,fromSuggestion,store,position,addedBy,createdAt,updatedAt,deletedAt) VALUES ('milk','raw milk','Milk','milk',0.125,'LITRE','note','4006381333931',1,12,1,'market',2.25,'member',1,2,NULL)")
            old.execSQL("INSERT INTO items (id,rawText,name,normalizedName,checked,fromSuggestion,position,createdAt,updatedAt,deletedAt) VALUES ('gone','Gone','Gone','gone',0,0,3,1,2,99)")
            old.execSQL("INSERT INTO trip VALUES ('trip',20)")
            old.execSQL("INSERT INTO purchase VALUES ('bought','trip','milk','Milk',0.5,'LITRE',NULL,12,1,1)")
            old.execSQL("INSERT INTO item_pref VALUES ('milk','DAIRY',1,900,21)")
            old.execSQL("INSERT INTO item_alias VALUES ('gtin:4006381333931','milk')")
        }
        var db = UsualsDatabase.build(context)
        val scope = db.listDao().selected()
        assertThat(scope).isNotEqualTo(LOCAL_LIST_ID)
        val items = db.historyDao().allItems(scope)
        assertThat(items).hasSize(2)
        assertThat(items.first { it.id == "milk" }).isEqualTo(ItemEntity("milk", "raw milk", "Milk", "milk", .125, "LITRE", "note", "4006381333931", true, 12, true, "market", 2.25, "member", 1, 2, null, scope))
        assertThat(items.first { it.id == "gone" }.deletedAt).isEqualTo(99)
        assertThat(db.historyDao().preferences(scope)).containsExactly(ItemPreferenceEntity("milk", "DAIRY", true, 900, 21, scope))
        assertThat(db.historyDao().purchases(scope).single().amount).isEqualTo(.5)
        assertThat(db.historyDao().aliases(scope)).containsExactly(ItemAliasEntity("gtin:4006381333931", "milk", scope))
        assertThat(db.listDao().selected()).isEqualTo(scope)
        assertThat(db.listDao().all().single().synced).isFalse()
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
        db.close()
        db = UsualsDatabase.build(context)
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(10)
        assertThat(db.historyDao().allItems(scope)).containsExactlyElementsIn(items)
        db.listDao().insert(ListEntity("camping", "Camping"))
        db.itemDao().insert(items.first().copy(listId = "camping"))
        assertThat(db.itemDao().active(listId = "camping")).hasSize(1)
        assertThat(db.itemDao().active(scope)).hasSize(1)
        var denied = false
        try { db.historyDao().insertPurchases(listOf(PurchaseEntity("bad", "trip", "milk", "Milk", boughtAt = 1, listId = "camping"))) }
        catch (_: android.database.sqlite.SQLiteConstraintException) { denied = true }
        assertThat(denied).isTrue()
        db.close()
    }
    @Test fun emptySchemaFourMigratesWithoutInventingShoppingFacts() = runTest {
        oldDatabase().close()
        val db = UsualsDatabase.build(context)
        assertThat(db.itemDao().active()).isEmpty()
        assertThat(db.historyDao().purchases()).isEmpty()
        assertThat(db.listDao().all()).hasSize(1)
        db.close()
    }
    @Test fun allUnitsNullableFactsAndScopedIndexesSurviveCopy() = runTest {
        val expected = mutableListOf<List<String?>>()
        oldDatabase().use { old ->
            for ((index, unit) in io.theficos.usuals.model.MeasureUnit.entries.withIndex()) {
                old.execSQL("INSERT INTO items (id,rawText,name,normalizedName,quantityAmount,quantityUnit,checked,fromSuggestion,position,createdAt,updatedAt,deletedAt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf("unit-$index", "raw-$index", "Name $index", "key-$index", index + .125, unit.name, index % 2, index % 2, index + .5, index.toLong(), index + 1L, if (index % 2 == 0) null else 90L))
            }
            old.execSQL("INSERT INTO item_alias VALUES ('a','b')")
            old.execSQL("INSERT INTO item_alias VALUES ('b','c')")
            old.rawQuery("SELECT * FROM items ORDER BY id", null).use { cursor ->
                while (cursor.moveToNext()) expected += (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }
            }
        }
        val db = UsualsDatabase.build(context)
        val actual = mutableListOf<List<String?>>()
        db.openHelper.writableDatabase.query("SELECT id,rawText,name,normalizedName,quantityAmount,quantityUnit,note,gtin,checked,checkedAt,fromSuggestion,store,position,addedBy,createdAt,updatedAt,deletedAt FROM items ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) actual += (0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }
        }
        assertThat(actual).containsExactlyElementsIn(expected).inOrder()
        assertThat(db.historyDao().aliases(db.listDao().selected())).containsExactly(ItemAliasEntity("a", "b", db.listDao().selected()), ItemAliasEntity("b", "c", db.listDao().selected()))
        for ((query, index) in listOf(
            "SELECT * FROM items WHERE listId = '${db.listDao().selected()}' AND deletedAt IS NULL" to "index_items_listId_deletedAt",
            "SELECT * FROM purchase WHERE listId = '${db.listDao().selected()}' AND `key` = 'milk'" to "index_purchase_listId_key")) {
            db.openHelper.writableDatabase.query("EXPLAIN QUERY PLAN $query").use { cursor ->
                cursor.moveToFirst(); assertThat(cursor.getString(3)).contains(index)
            }
        }
        db.close()
    }
    @Test fun interruptionAfterCopyRollsBackToAnIntactSchemaFour() {
        oldDatabase().use { it.execSQL("INSERT INTO item_alias VALUES ('a','b')") }
        val db = androidx.room.Room.databaseBuilder(context, UsualsDatabase::class.java, UsualsDatabase.NAME)
            .addMigrations(object : androidx.room.migration.Migration(4, 5) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    MIGRATION_4_5.migrate(db)
                    error("simulated interruption before migration commit")
                }
            }, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
        assertThat(runCatching { db.openHelper.writableDatabase }.isFailure).isTrue()
        db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(UsualsDatabase.NAME).path, null, SQLiteDatabase.OPEN_READONLY).use { old ->
            assertThat(old.version).isEqualTo(4)
            old.rawQuery("SELECT fromKey,toKey FROM item_alias", null).use { row ->
                assertThat(row.moveToFirst()).isTrue(); assertThat(row.getString(0)).isEqualTo("a"); assertThat(row.getString(1)).isEqualTo("b")
            }
            old.rawQuery("SELECT name FROM sqlite_master WHERE name = 'list_scope'", null).use { assertThat(it.count).isEqualTo(0) }
        }
    }

}

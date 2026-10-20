package io.theficos.usuals.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RetiredSchemaUpgradeTest {
    @Test
    fun `upgrading unreleased schemas deletes retired tables and creates a usable list`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 1..3) {
            context.deleteDatabase(UsualsDatabase.NAME)
            val file = context.getDatabasePath(UsualsDatabase.NAME)
            file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                old.execSQL("CREATE TABLE items (id TEXT PRIMARY KEY, name TEXT)")
                old.execSQL("INSERT INTO items VALUES ('old', 'discarded')")
                for (table in listOf("household_alias", "resolution_log", "outbox")) {
                    old.execSQL("CREATE TABLE $table (value TEXT)")
                    old.execSQL("INSERT INTO $table VALUES ('retired')")
                }
                old.version = version
            }
            val database = UsualsDatabase.build(context)
            try {
                val upgraded = database.openHelper.writableDatabase
                assertThat(upgraded.version).isEqualTo(6)
                val tables = upgraded.query("SELECT name FROM sqlite_master WHERE type = 'table'").use {
                    buildList { while (it.moveToNext()) add(it.getString(0)) }
                }
                assertThat(tables).containsAtLeast("items", "product_quality", "room_master_table")
                assertThat(tables).containsNoneOf("household_alias", "resolution_log", "outbox")
                upgraded.query("SELECT COUNT(*) FROM items").use {
                    it.moveToFirst()
                    assertThat(it.getInt(0)).isEqualTo(0)
                }
            } finally {
                database.close()
            }
            context.deleteDatabase(UsualsDatabase.NAME)
        }
    }
}

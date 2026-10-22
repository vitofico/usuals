package io.theficos.usuals.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.Projection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectionPartsMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { encodeDefaults = true }

    private fun schemaSeven(name: String, rows: List<Triple<String, String, String>>) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("io.theficos.usuals.data.local.UsualsDatabase/7.json")!!.bufferedReader().readText()).jsonObject.getValue("database").jsonObject
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in entity.getValue("indices").jsonArray.map { it.jsonObject }) old.execSQL(index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO list_scope (id, name, synced, created, seq, stale) VALUES ('busy','Home',1,1,4,0), ('small','Camping',0,0,0,0)")
            old.execSQL("INSERT INTO list_selection VALUES (1,'busy')")
            for ((list, confirmed, optimistic) in rows) old.execSQL("INSERT INTO sync_projection VALUES (?,?,?)", arrayOf(list, confirmed, optimistic))
            old.version = 7
        }
    }

    @Test fun eachCopyIsSplitIntoPartsThatFitTheCursorWindowEvenFromARowThatDidNot() = runTest {
        val name = "projection-parts-migration.db"
        val confirmed = largeProjection(1_100)
        val shown = confirmed.copy(name = "Kitchen", nameRevision = "intent-1")
        val small = Projection(name = "Camping")
        val rows = listOf(Triple("busy", json.encodeToString(confirmed), json.encodeToString(shown)),
            Triple("small", json.encodeToString(small), json.encodeToString(small)))
        // The busy list's row is past the 2 MB window, so schema 7 could store it but not read it back.
        assertThat(rows.first().let { (_, a, b) -> a.encodeToByteArray().size + b.encodeToByteArray().size }).isGreaterThan(2 * 1024 * 1024)
        schemaSeven(name, rows)

        val db = UsualsDatabase.build(context, name)
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(9)
        assertThat(db.syncDao().projection("busy", optimistic = false).size).isGreaterThan(1)
        db.openHelper.writableDatabase.query("SELECT MAX(length(CAST(text AS BLOB))) FROM sync_projection").use {
            it.moveToFirst(); assertThat(it.getLong(0)).isLessThan(1_000_000)
        }
        val replica = LocalReplica(db)
        assertThat(replica.creation("busy")).isEqualTo(confirmed)
        assertThat(replica.projection("busy")).isEqualTo(shown)
        assertThat(replica.creation("small")).isEqualTo(small)
        assertThat(replica.projection("small")).isEqualTo(small)
        db.close()
        context.deleteDatabase(name)
    }
}

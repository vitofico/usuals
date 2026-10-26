package io.theficos.usuals.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.ListColor
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.sync.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ListColorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun migrationPreservesListsAndSelectionAndColorsSurviveReopen() = runTest {
        val name = "list-color-migration.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("io.theficos.usuals.data.local.UsualsDatabase/9.json")!!.bufferedReader().readText()).jsonObject.getValue("database").jsonObject
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in entity.getValue("indices").jsonArray.map { it.jsonObject }) old.execSQL(index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO list_scope (id,name,synced,created,seq,stale,archived,archivedByOwner) VALUES ('home','Groceries',0,0,0,0,0,0)")
            old.execSQL("INSERT INTO list_selection(singleton,listId) VALUES(1,'home')")
            val metadata = ListAppearance.withColor(Projection(name = "Groceries"), ListColor.SAGE, "older-client")
            old.execSQL("INSERT INTO sync_projection(listId,optimistic,part,text) VALUES(?,1,0,?)", arrayOf("home", Wire.json.encodeToString(Projection.serializer(), metadata)))
            old.version = 9
        }
        var db = UsualsDatabase.build(context, name)
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(10)
        assertThat(db.listDao().selected()).isEqualTo("home")
        assertThat(db.listDao().find("home")!!.name).isEqualTo("Groceries")
        assertThat(db.listDao().find("home")!!.color).isEqualTo("sage")
        assertThat(db.listDao().find("home")!!.colorRevision).isEqualTo("older-client")
        LocalReplica(db).setColor("home", ListColor.OCHRE, "older-client")
        db.close()
        db = UsualsDatabase.build(context, name)
        assertThat(db.listDao().find("home")!!.color).isEqualTo("ochre")
        assertThat(ListAppearance.color(LocalReplica(db).projection("home"))).isEqualTo(ListColor.OCHRE)
        db.close(); context.deleteDatabase(name)
    }
    @Test fun remoteColorRebasesPendingChoicesAndRemainsOutsideShoppingPreferences() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
        try {
            val replica = LocalReplica(db)
            val remote = SharedList("shared", 1, "owner", Person("owner", "Owner"), emptyList(), ListAppearance.withColor(Projection(name = "Shared"), ListColor.SAGE, "server-one"))
            replica.accept(remote)
            replica.setColor("shared", ListColor.ROSE, "server-one")
            assertThat(db.listDao().find("shared")!!.color).isEqualTo("rose")
            val newer = remote.copy(seq = 2, projection = ListAppearance.withColor(Projection(name = "Shared"), ListColor.TEAL, "server-two"))
            replica.accept(newer)
            assertThat(db.listDao().find("shared")!!.color).isEqualTo("teal")
            assertThat(replica.projection("shared").conflicts).isNotEmpty()
            assertThat(db.historyDao().preferences("shared")).isEmpty()
        } finally { db.close() }
    }
    @Test fun archiveAndCopiedListPreserveTheAccentWithFreshRevisionLineage() = runTest {
        val first = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
        val second = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).build()
        try {
            first.listDao().insert(ListEntity("home", "Home")); first.listDao().select(ListSelectionEntity(listId = "home"))
            val replica = LocalReplica(first)
            replica.setColor("home", ListColor.SLATE, null)
            val archive = HistoryRepository(first, ItemRepository(first)).exportJson()
            HistoryRepository(second, ItemRepository(second)).importJson(archive)
            assertThat(second.listDao().find("home")!!.color).isEqualTo("slate")
            assertThat(ListAppearance.color(LocalReplica(second).projection("home"))).isEqualTo(ListColor.SLATE)
            val copied = replica.copyAsNew("home")
            assertThat(first.listDao().find(copied)!!.color).isEqualTo("slate")
            assertThat(ListAppearance.revision(replica.projection(copied))).isNull()
        } finally { first.close(); second.close() }
    }
}

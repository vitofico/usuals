package io.theficos.usuals.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.NORMALIZER_VERSION
import io.theficos.usuals.sync.Intent
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
class ServerListsMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val json = Json { encodeDefaults = true }

    private fun schemaSix(name: String) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("io.theficos.usuals.data.local.UsualsDatabase/6.json")!!.bufferedReader().readText()).jsonObject.getValue("database").jsonObject
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in entity.getValue("indices").jsonArray.map { it.jsonObject }) old.execSQL(index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
            val confirmed = json.encodeToString(Projection(name = "Shared"))
            val shown = json.encodeToString(Projection(name = "Groceries", nameRevision = "intent-1"))
            val rename = json.encodeToString(Intent("intent-1", "shared-list", "list_name", buildJsonObject { put("expected", JsonNull); put("name", "Groceries") }, normalizer = NORMALIZER_VERSION))
            old.execSQL("INSERT INTO household VALUES ('home',1,'routing'), ('mine',0,NULL)")
            old.execSQL("INSERT INTO list_scope VALUES ('shared-list','home','Groceries',1,0,NULL), ('camping','mine','Camping',0,0,'archive-1')")
            old.execSQL("INSERT INTO list_selection VALUES (1,'shared-list')")
            old.execSQL("INSERT INTO items (id,rawText,name,normalizedName,checked,fromSuggestion,position,createdAt,updatedAt,listId) VALUES ('milk','Milk','Milk','milk',0,0,1,1,2,'shared-list'), ('tent','Tent','Tent','tent',1,0,1,1,2,'camping')")
            old.execSQL("INSERT INTO trip VALUES ('t1',20,'camping')")
            old.execSQL("INSERT INTO purchase VALUES ('p1','t1','tent','Tent',NULL,NULL,NULL,20,0,$NORMALIZER_VERSION,'camping')")
            old.execSQL("INSERT INTO item_pref VALUES ('camping','tent','GEAR',30)")
            old.execSQL("INSERT INTO local_item_pref VALUES ('camping','tent',1,NULL)")
            old.execSQL("INSERT INTO item_alias VALUES ('tents','tent','camping')")
            old.execSQL("INSERT INTO sync_projection VALUES ('shared-list',?,?)", arrayOf(confirmed, shown))
            old.execSQL("INSERT INTO sync_outbox VALUES ('shared-list','intent-0',1,?,NULL,1), ('shared-list','intent-1',2,?,X'0102',0)", arrayOf(rename.replace("intent-1", "intent-0"), rename))
            old.execSQL("INSERT INTO sync_received VALUES ('shared-list',1,'intent-0','envelope','digest','head')")
            old.execSQL("INSERT INTO sync_cursor VALUES ('shared-list',1,'head',0,'manifest')")
            old.execSQL("INSERT INTO member_ref VALUES ('home','member','proof')")
            old.execSQL("INSERT INTO device_ref VALUES ('home','device','member','proof')")
            old.execSQL("INSERT INTO conflict_dismissal VALUES ('shared-list','intent-9')")
            old.version = 6
        }
    }

    @Test fun householdListsBecomeLocalListsThatKeepWhatTheyShowed() = runTest {
        val name = "server-lists-migration.db"
        schemaSix(name)
        var db = UsualsDatabase.build(context, name)
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(7)
        assertThat(db.listDao().all()).containsExactly(ListEntity("shared-list", "Groceries"), ListEntity("camping", "Camping", "archive-1")).inOrder()
        assertThat(db.listDao().selected()).isEqualTo("shared-list")
        assertThat(db.historyDao().allItems("shared-list").single().name).isEqualTo("Milk")
        assertThat(db.historyDao().allItems("camping").single().checked).isTrue()
        assertThat(db.historyDao().purchases("camping").single().name).isEqualTo("Tent")
        assertThat(db.historyDao().preferences("camping")).containsExactly(ItemPreferenceEntity("tent", "GEAR", true, null, 30, "camping"))
        assertThat(db.historyDao().aliases("camping")).containsExactly(ItemAliasEntity("tents", "tent", "camping"))
        val shown = json.encodeToString(Projection(name = "Groceries", nameRevision = "intent-1"))
        assertThat(db.syncDao().projection("shared-list")).isEqualTo(ProjectionEntity("shared-list", shown, shown))
        assertThat(db.syncDao().pending("shared-list").map { it.intentId to it.acknowledged }).containsExactly("intent-0" to true, "intent-1" to true).inOrder()
        assertThat(db.syncDao().feed()).isNull()
        db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE name IN ('household','member_ref','device_ref','sync_received','sync_cursor')").use { assertThat(it.count).isEqualTo(0) }
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }
        val replica = LocalReplica(db)
        assertThat(replica.pendingIntents("shared-list", 100)).isEmpty()
        assertThat(replica.unsent()).isEqualTo(0)
        db.close()

        db = UsualsDatabase.build(context, name)
        val again = LocalReplica(db)
        again.adoptLocalLists()
        assertThat(again.creation("shared-list")).isEqualTo(Projection(name = "Groceries", nameRevision = "intent-1"))
        assertThat(again.outgoing().map { it.id }).containsExactly("shared-list", "camping").inOrder()
        assertThat(db.historyDao().allItems("shared-list").single().name).isEqualTo("Milk")
        db.close()
        context.deleteDatabase(name)
    }
}

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
class ProductDetailsMigrationTest {
    @Test fun schemaFiveRetainsExistingProductFactsAndAddsUnknownDetails() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "off-migration.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("io.theficos.usuals.data.local.UsualsDatabase/5.json")!!.bufferedReader().readText()).jsonObject.getValue("database").jsonObject
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in entity.getValue("indices").jsonArray.map { it.jsonObject }) old.execSQL(index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
            old.execSQL("INSERT INTO product_quality (gtin,productName,allergens,confirmedMissing,fetchedAt) VALUES ('4006381333931','Saved product','',0,123)")
            old.version = 5
        }
        var db = UsualsDatabase.build(context, name)
        val row = db.qualityDao().find("4006381333931")!!
        assertThat(row.productName).isEqualTo("Saved product")
        assertThat(row.allergens).isEmpty()
        assertThat(row.traces).isNull()
        assertThat(row.ingredients).isNull()
        assertThat(row.brands).isNull()
        assertThat(row.fetchedAt).isEqualTo(123)
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(6)
        db.close()
        db = UsualsDatabase.build(context, name)
        assertThat(db.qualityDao().find(row.gtin)).isEqualTo(row)
        db.close()
        context.deleteDatabase(name)
    }
}

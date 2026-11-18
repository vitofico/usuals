package io.theficos.usuals.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.GreenScore
import io.theficos.usuals.enrich.NutriScore
import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.enrich.ProductType
import io.theficos.usuals.enrich.QualityLookup
import io.theficos.usuals.model.Gtin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProductTypeMigrationTest {
    @Test fun schemaEightRowsKeepTheirGradesAndReadAsFoodWithNothingClaimedAboutApplicability() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "product-type-migration.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = Json.parseToJsonElement(javaClass.classLoader!!.getResourceAsStream("io.theficos.usuals.data.local.UsualsDatabase/8.json")!!.bufferedReader().readText()).jsonObject.getValue("database").jsonObject
            for (entity in schema.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                for (index in entity.getValue("indices").jsonArray.map { it.jsonObject }) old.execSQL(index.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
            // An Eco-Score era row: its stored letter must still read as the same Green-Score.
            old.execSQL("INSERT INTO product_quality (gtin,nutriScore,ecoScore,productName,confirmedMissing,fetchedAt) VALUES ('4006381333931','B','E','Saved product',0,123)")
            old.version = 8
        }
        var db = UsualsDatabase.build(context, name)
        assertThat(db.openHelper.writableDatabase.version).isEqualTo(10)
        val row = db.qualityDao().find("4006381333931")!!
        assertThat(row.nutriScoreNotApplicable).isNull()
        assertThat(row.productType).isNull()
        val cache = RoomQualityCache(db.qualityDao()) { 0L }
        val quality = cache.get(Gtin.parse("4006381333931")!!)!!.quality!!
        assertThat(quality.productName).isEqualTo("Saved product")
        assertThat(quality.nutriScore).isEqualTo(NutriScore.B)
        assertThat(quality.greenScore).isEqualTo(GreenScore.E)
        assertThat(quality.nutriScoreNotApplicable).isFalse()
        assertThat(quality.productType).isEqualTo(ProductType.FOOD)

        // The new columns are writable and survive a reopen at version 9.
        val shampoo = Gtin.parse("8410757001090")!!
        cache.put(QualityLookup.Found(shampoo, ProductQuality(productType = ProductType.BEAUTY, nutriScoreNotApplicable = true, productName = "Hand cream")), 456)
        db.close()
        db = UsualsDatabase.build(context, name)
        val reopened = RoomQualityCache(db.qualityDao()) { 0L }.get(shampoo)!!.quality!!
        assertThat(reopened.productType).isEqualTo(ProductType.BEAUTY)
        assertThat(reopened.nutriScoreNotApplicable).isTrue()
        assertThat(reopened.productName).isEqualTo("Hand cream")
        db.close()
        context.deleteDatabase(name)
    }
}

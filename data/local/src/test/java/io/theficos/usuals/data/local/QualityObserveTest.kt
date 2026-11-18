package io.theficos.usuals.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.NutriScore
import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.enrich.QualityLookup
import io.theficos.usuals.model.Gtin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** List rows watch the product cache: a saved answer reaches them without a list change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QualityObserveTest {

    private lateinit var db: UsualsDatabase
    private lateinit var cache: RoomQualityCache
    private val milk = Gtin.parse("8400000001236")!!
    private val bread = Gtin.parse("8400000004565")!!
    private val eggs = Gtin.parse("8400000007894")!!

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, UsualsDatabase::class.java).allowMainThreadQueries().build()
        cache = RoomQualityCache(db.qualityDao())
    }

    @After fun tearDown() = db.close()

    @Test fun `answers refresh and only reach requested barcodes`() = runTest {
        cache.put(QualityLookup.Missing(bread), 1)
        cache.observe(setOf(milk, bread)).test {
            assertThat(awaitItem()).isEmpty()
            cache.put(QualityLookup.Found(milk, ProductQuality(nutriScore = NutriScore.C)), 2)
            assertThat(awaitItem()).containsExactly(milk, ProductQuality(nutriScore = NutriScore.C))
            cache.put(QualityLookup.Found(eggs, ProductQuality(nutriScore = NutriScore.B)), 3)
            assertThat(awaitItem().keys).containsExactly(milk)
            cache.put(QualityLookup.Found(milk, ProductQuality(nutriScore = NutriScore.A)), 4)
            assertThat(awaitItem()).containsExactly(milk, ProductQuality(nutriScore = NutriScore.A))
            cancelAndIgnoreRemainingEvents()
        }
    }
}

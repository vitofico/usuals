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
import kotlinx.coroutines.flow.first
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
        cache = RoomQualityCache(db.qualityDao(), clock = { 1L })
    }

    @After fun tearDown() = db.close()

    @Test fun `the dao emits again after a write and only for the barcodes asked about`() = runTest {
        db.qualityDao().observe(listOf(milk.value, bread.value)).test {
            assertThat(awaitItem()).isEmpty()
            cache.put(QualityLookup.Found(milk, ProductQuality(nutriScore = NutriScore.B)))
            assertThat(awaitItem().map { it.gtin }).containsExactly(milk.value)
            cache.put(QualityLookup.Found(eggs, ProductQuality(nutriScore = NutriScore.A)))
            assertThat(awaitItem().map { it.gtin }).containsExactly(milk.value)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `confirmed absences read as no record and a refresh replaces the grade`() = runTest {
        cache.put(QualityLookup.Missing(bread))
        cache.observe(setOf(milk, bread)).test {
            assertThat(awaitItem()).isEmpty()
            cache.put(QualityLookup.Found(milk, ProductQuality(nutriScore = NutriScore.C)))
            assertThat(awaitItem().getValue(milk).nutriScore).isEqualTo(NutriScore.C)
            cache.put(QualityLookup.Found(milk, ProductQuality(nutriScore = NutriScore.A)))
            assertThat(awaitItem().getValue(milk).nutriScore).isEqualTo(NutriScore.A)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `more barcodes than SQLite binds in one query still answer`() = runTest {
        cache.put(QualityLookup.Found(milk, ProductQuality(nutriScore = NutriScore.B)))
        val many = (1..1_200).mapNotNull { Gtin.parse(withCheckDigit("400%09d".format(it))) }.toSet() + milk
        assertThat(cache.observe(many).first().keys).containsExactly(milk)
    }

    private fun withCheckDigit(twelve: String): String {
        val sum = twelve.mapIndexed { i, c -> (c - '0') * if (i % 2 == 0) 1 else 3 }.sum()
        return twelve + ((10 - sum % 10) % 10)
    }
}

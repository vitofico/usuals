package io.theficos.usuals.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.enrich.FailureReason
import io.theficos.usuals.enrich.GreenScore
import io.theficos.usuals.enrich.NovaGroup
import io.theficos.usuals.enrich.NutriScore
import io.theficos.usuals.enrich.ProductQuality
import io.theficos.usuals.enrich.ProductType
import io.theficos.usuals.enrich.QualityLookup
import io.theficos.usuals.enrich.SkipReason
import io.theficos.usuals.model.Gtin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The quality cache on disk, and the two failures that would never show up in a
 * crash report.
 *
 * **An outage written down as an absence.** A timeout stored as "Open Food
 * Facts has nothing about this" is silent, it is indistinguishable from the
 * truth at every point where it is read, and unlike the in-memory cache's
 * version of the same mistake it survives a restart and sits there until the
 * freshness policy lets go of it a week later.
 *
 * **A tag list that loses the difference between "nobody said" and "there are
 * none".** Null and empty are different facts, and the one that matters is the
 * one with somebody's allergy behind it: an empty allergen list means OFF
 * looked, and a null means nobody filled in the form. If the round trip through
 * SQLite collapses them, the app eventually tells a person a product contains
 * no peanuts on the strength of a form nobody completed. The round trip is
 * therefore asserted at the column, not only through the mapper.
 *
 * Nothing here reaches the network and the clock is a field, so ages measured
 * in weeks are exact and the suite still runs in milliseconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoomQualityCacheTest {

    private lateinit var db: UsualsDatabase
    private lateinit var cache: RoomQualityCache

    private var now = 1_700_000_000_000L

    private val leche = gtin("8400000001236")
    private val pan = gtin("8400000004565")
    private val huevos = gtin("8400000007894")
    private val aceite = gtin("8410123456783")

    private val stated = ProductQuality(
        nutriScore = NutriScore.B,
        novaGroup = NovaGroup.PROCESSED,
        greenScore = GreenScore.C,
        allergens = listOf("en:milk", "en:gluten"),
        additives = listOf("en:e322"),
        productName = "Test milk",
        categories = listOf("en:dairies", "en:milks"),
        brands = "Example",
        packageSize = "1 litre",
        ingredients = "Milk",
        traces = listOf("en:nuts"),
        sourceModifiedAtEpochSeconds = 1700000000L,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            UsualsDatabase::class.java,
        )
            .allowMainThreadQueries()
            // Room runs suspend queries and its invalidation bookkeeping on
            // these executors. Left on Room's own background pool, a query that
            // is still finishing when runTest sweeps for stragglers fails the
            // suite as UncompletedCoroutinesError, on whichever test happened
            // to lose the race that day. Running them on the calling thread
            // makes every read complete before the assertion after it.
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        cache = RoomQualityCache(db.qualityDao(), clock = { now })
    }

    @After
    fun tearDown() = db.close()

    // region what the cache refuses to write down

    @Test
    fun `an outage is never written down as an absence`() = runTest {
        cache.put(QualityLookup.Failed(leche, FailureReason.TIMEOUT), now)
        cache.put(QualityLookup.Failed(pan, FailureReason.RATE_LIMITED), now)
        cache.put(QualityLookup.Skipped(huevos, SkipReason.RESTRICTED_CIRCULATION), now)

        assertThat(cache.get(leche)).isNull()
        assertThat(cache.get(pan)).isNull()
        assertThat(cache.get(huevos)).isNull()
        // Not merely unreadable: no row was written at all.
        assertThat(db.qualityDao().size()).isEqualTo(0)
    }

    @Test
    fun `a failure never overwrites what is already known`() = runTest {
        cache.put(QualityLookup.Found(leche, stated), now)

        now += 30L * 24 * 60 * 60 * 1000
        cache.put(QualityLookup.Failed(leche, FailureReason.SERVER_ERROR), now)

        val entry = cache.get(leche)!!
        assertThat(entry.quality).isEqualTo(stated)
        assertThat(entry.fetchedAtEpochMillis).isEqualTo(1_700_000_000_000L)
    }

    // endregion

    // region the three states of knowing

    @Test
    fun `a record, a confirmed absence and a barcode never asked about stay distinct`() = runTest {
        cache.put(QualityLookup.Found(leche, stated), now)
        cache.put(QualityLookup.Missing(pan), now)

        val found = cache.get(leche)!!
        assertThat(found.quality).isEqualTo(stated)
        assertThat(found.confirmedMissing).isFalse()

        val missing = cache.get(pan)!!
        assertThat(missing.quality).isNull()
        assertThat(missing.confirmedMissing).isTrue()

        assertThat(cache.get(huevos)).isNull()
    }

    @Test
    fun `a record that says nothing is not the same as no record`() = runTest {
        cache.put(QualityLookup.Found(leche, ProductQuality.EMPTY), now)
        cache.put(QualityLookup.Missing(pan), now)

        val blank = cache.get(leche)!!
        assertThat(blank.quality).isEqualTo(ProductQuality.EMPTY)
        assertThat(blank.quality!!.isBlank).isTrue()
        assertThat(blank.confirmedMissing).isFalse()
        assertThat(cache.get(pan)!!.confirmedMissing).isTrue()
    }

    @Test
    fun `every facet comes back exactly as it was stored`() = runTest {
        cache.put(QualityLookup.Found(leche, stated), now)

        val quality = cache.get(leche)!!.quality!!
        assertThat(quality).isEqualTo(stated)
        assertThat(quality.nutriScore).isEqualTo(NutriScore.B)
        assertThat(quality.novaGroup).isEqualTo(NovaGroup.PROCESSED)
        assertThat(quality.greenScore).isEqualTo(GreenScore.C)
        // Order is part of the value: a list read back shuffled is a different
        // list, and equality would not have caught a set-shaped encoding.
        assertThat(quality.allergens).containsExactly("en:milk", "en:gluten").inOrder()
        assertThat(quality.additives).containsExactly("en:e322").inOrder()
    }

    @Test
    fun `the Green-Score grades beyond the old Eco-Score scale round trip, and old letters still read`() = runTest {
        cache.put(QualityLookup.Found(leche, ProductQuality(greenScore = GreenScore.A_PLUS)), now)
        cache.put(QualityLookup.Found(pan, ProductQuality(greenScore = GreenScore.F)), now)
        // What an Eco-Score era build wrote: the bare letter, which must keep its meaning.
        db.qualityDao().upsert(QualityEntity(gtin = huevos.value, ecoScore = "A", fetchedAt = now))

        assertThat(cache.get(leche)!!.quality!!.greenScore).isEqualTo(GreenScore.A_PLUS)
        assertThat(cache.get(pan)!!.quality!!.greenScore).isEqualTo(GreenScore.F)
        assertThat(cache.get(huevos)!!.quality!!.greenScore).isEqualTo(GreenScore.A)
    }

    @Test
    fun `does not apply and not stated stay different facts on disk`() = runTest {
        cache.put(QualityLookup.Found(leche, ProductQuality(nutriScoreNotApplicable = true)), now)
        cache.put(QualityLookup.Found(pan, ProductQuality.EMPTY), now)

        assertThat(db.qualityDao().find(leche.value)!!.nutriScoreNotApplicable).isTrue()
        assertThat(db.qualityDao().find(pan.value)!!.nutriScoreNotApplicable).isFalse()
        assertThat(cache.get(leche)!!.quality!!.nutriScoreNotApplicable).isTrue()
        assertThat(cache.get(pan)!!.quality!!.nutriScoreNotApplicable).isFalse()
    }

    /**
     * The non-food answer used to arrive as a failure, so nothing was written
     * and every rescan of a shampoo spent a permit to be told the same thing.
     */
    @Test
    fun `a non-food answer is kept with its type, and food rows store no type`() = runTest {
        val shampoo = ProductQuality(productType = ProductType.BEAUTY, nutriScoreNotApplicable = true, productName = "Hand cream")
        cache.put(QualityLookup.Found(aceite, shampoo), now)
        cache.put(QualityLookup.Found(leche, stated), now)

        val entry = cache.get(aceite)!!
        assertThat(entry.confirmedMissing).isFalse()
        assertThat(entry.quality).isEqualTo(shampoo)
        assertThat(db.qualityDao().find(aceite.value)!!.productType).isEqualTo("BEAUTY")
        assertThat(db.qualityDao().find(leche.value)!!.productType).isNull()
        assertThat(cache.get(leche)!!.quality!!.productType).isEqualTo(ProductType.FOOD)
    }

    // endregion

    // region null is not empty

    /**
     * The one with an allergy behind it. Three products: one OFF says nothing
     * about, one OFF has checked and found no allergens in, and one with a
     * list. All three have to come back different, and the column has to hold
     * the difference rather than the mapper guessing at it.
     */
    @Test
    fun `an unstated allergen list and an empty one stay different facts on disk`() = runTest {
        cache.put(QualityLookup.Found(leche, ProductQuality(allergens = null)), now)
        cache.put(QualityLookup.Found(pan, ProductQuality(allergens = emptyList())), now)
        cache.put(QualityLookup.Found(huevos, ProductQuality(allergens = listOf("en:eggs"))), now)

        assertThat(cache.get(leche)!!.quality!!.allergens).isNull()
        assertThat(cache.get(pan)!!.quality!!.allergens).isEmpty()
        assertThat(cache.get(huevos)!!.quality!!.allergens).containsExactly("en:eggs")

        // At the column: NULL and the empty string, which is the distinction
        // the whole encoding exists to keep.
        assertThat(db.qualityDao().find(leche.value)!!.allergens).isNull()
        assertThat(db.qualityDao().find(pan.value)!!.allergens).isEmpty()
    }

    @Test
    fun `an unstated additive list and an empty one stay different facts on disk`() = runTest {
        cache.put(QualityLookup.Found(leche, ProductQuality(additives = null)), now)
        cache.put(QualityLookup.Found(pan, ProductQuality(additives = emptyList())), now)

        assertThat(cache.get(leche)!!.quality!!.additives).isNull()
        assertThat(cache.get(pan)!!.quality!!.additives).isEmpty()
        assertThat(db.qualityDao().find(leche.value)!!.additives).isNull()
        assertThat(db.qualityDao().find(pan.value)!!.additives).isEmpty()
    }

    // endregion

    // region keys, time and replacement

    @Test
    fun `a UPC-A and the EAN-13 it widens into land on one row`() = runTest {
        val upc = gtin("036000291452")
        val ean = gtin("0036000291452")

        cache.put(QualityLookup.Found(upc, stated), now)

        assertThat(cache.get(ean)!!.quality).isEqualTo(stated)
        assertThat(db.qualityDao().size()).isEqualTo(1)
    }

    @Test
    fun `an entry is stamped with the instant the request went out`() = runTest {
        val requestedAt = now
        // The answer lands ten seconds later. The row still dates from the
        // question, so a slow response cannot make a record look fresher than
        // the data inside it.
        now += 10_000
        cache.put(QualityLookup.Found(leche, stated), requestedAt)

        assertThat(cache.get(leche)!!.fetchedAtEpochMillis).isEqualTo(requestedAt)
    }

    @Test
    fun `put without an instant stamps the injected clock, never the wall clock`() = runTest {
        cache.put(QualityLookup.Found(leche, stated))

        assertThat(cache.get(leche)!!.fetchedAtEpochMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `a later answer replaces the one before it`() = runTest {
        cache.put(QualityLookup.Missing(aceite), now)

        now += 7L * 24 * 60 * 60 * 1000
        // Somebody added the product to OFF in the meantime, which is the
        // ordinary life of a confirmed miss and the reason misses are re-asked
        // about sooner than records.
        cache.put(QualityLookup.Found(aceite, stated), now)

        val entry = cache.get(aceite)!!
        assertThat(entry.confirmedMissing).isFalse()
        assertThat(entry.quality).isEqualTo(stated)
        assertThat(entry.fetchedAtEpochMillis).isEqualTo(now)
        assertThat(db.qualityDao().size()).isEqualTo(1)
    }

    @Test
    fun `the cache can be emptied, because every row in it is re-fetchable`() = runTest {
        cache.put(QualityLookup.Found(leche, stated), now)
        cache.put(QualityLookup.Missing(pan), now)

        assertThat(db.qualityDao().clear()).isEqualTo(2)

        assertThat(cache.get(leche)).isNull()
        assertThat(cache.get(pan)).isNull()
        assertThat(db.qualityDao().size()).isEqualTo(0)
    }

    // endregion

    private fun gtin(raw: String): Gtin =
        requireNotNull(Gtin.parse(raw)) { "fixture is not a valid GTIN: $raw" }
}

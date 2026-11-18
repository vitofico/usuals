package io.theficos.usuals.di

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.data.local.*
import io.theficos.usuals.enrich.*
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.*
import io.theficos.usuals.smart.*
import io.theficos.usuals.ui.scan.ScanNameProblem
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryScanStoreTest {
    private lateinit var db: UsualsDatabase
    private lateinit var items: ItemRepository
    private lateinit var history: HistoryRepository
    private lateinit var smart: RepositorySmartStore
    private val classifier = CategoryClassifier(InputLanguages.available)
    private val code = Gtin.parse("4006381333931")!!
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        items = ItemRepository(db); history = HistoryRepository(db, items)
        smart = RepositorySmartStore(items, history, classifier, HistoryRanker(), RepeatPlanner())
    }
    @After fun close() = db.close()
    private fun store(enabled: () -> Boolean, enricher: () -> ProductEnricher) =
        RepositoryScanStore(items, history, enabled, enricher, BarcodeCategories(classifier))

    @Test fun `off means the network client is never constructed and a confirmed name adds the product`() = runTest {
        var constructed = 0
        val scans = store({ false }) { constructed++; error("network must stay disconnected") }
        val offer = scans.lookup(code)
        assertThat(constructed).isEqualTo(0)
        assertThat(offer.name).isNull()
        assertThat(offer.problem).isEqualTo(ScanNameProblem.LOOKUP_DISABLED)
        assertThat(items.items()).isEmpty()
        val id = scans.add(offer, " washing liquid ")
        assertThat(items.find(id)!!.name).isEqualTo("washing liquid")
        assertThat(items.find(id)!!.gtin).isEqualTo(code)
        assertThat(history.snapshot().aliases).containsExactly(ItemAlias("gtin:${code.value}", "washing liquid"))
    }

    @Test fun `a barcode alone is never added`() = runTest {
        val scans = store({ false }) { error("off") }
        val offer = scans.lookup(code)
        for (name in listOf("", "  ", code.value)) {
            assertThat(runCatching { scans.add(offer, name) }.isFailure).isTrue()
        }
        assertThat(items.items()).isEmpty()
    }

    @Test fun `confirmed names name later scans without a lookup, after Finish and removal`() = runTest {
        val scans = store({ false }) { error("network must stay disconnected") }
        val id = scans.add(scans.lookup(code), "Washing Liquid")
        items.setChecked(id, true)
        history.finish()
        val remembered = scans.lookup(code)
        assertThat(remembered.name).isEqualTo("Washing Liquid")
        assertThat(remembered.problem).isNull()
        val next = scans.add(remembered, remembered.name!!)
        assertThat(items.find(next)!!.name).isEqualTo("Washing Liquid")
        items.delete(next)
        assertThat(scans.lookup(code).name).isEqualTo("Washing Liquid")
        assertThat(history.snapshot().purchases).hasSize(1)
    }

    @Test fun `renaming a remembered barcode moves its alias`() = runTest {
        val scans = store({ false }) { error("off") }
        items.delete(scans.add(scans.lookup(code), "washing liquid"))
        scans.add(scans.lookup(code), "dish soap")
        assertThat(history.snapshot().aliases).containsExactly(ItemAlias("gtin:${code.value}", "dish soap"))
        assertThat(scans.lookup(code).name).isEqualTo("dish soap")
    }

    @Test fun `a confirmed scan of a product already listed joins its row`() = runTest {
        val scans = store({ false }) { error("off") }
        val milk = items.add(ParsedItem("milk"))
        assertThat(scans.add(scans.lookup(code), "Milk")).isEqualTo(milk)
        assertThat(items.items()).hasSize(1)
    }

    @Test fun `lookup distinguishes absent names from failures and store codes`() = runTest {
        val outcomes = listOf(
            QualityLookup.Missing(code) to ScanNameProblem.NOT_FOUND,
            QualityLookup.Found(code, ProductQuality.EMPTY) to ScanNameProblem.NOT_FOUND,
            QualityLookup.Failed(code, FailureReason.UNREACHABLE) to ScanNameProblem.UNAVAILABLE,
            QualityLookup.Skipped(code, SkipReason.RESTRICTED_CIRCULATION) to ScanNameProblem.RESTRICTED,
            QualityLookup.Skipped(code, SkipReason.OPTED_OUT) to ScanNameProblem.LOOKUP_DISABLED,
        )
        for ((outcome, problem) in outcomes) {
            val scans = store({ true }) { object : ProductEnricher {
                override suspend fun lookup(gtin: Gtin) = outcome
            } }
            assertThat(scans.lookup(code).problem).isEqualTo(problem)
        }
    }

    @Test fun `non-food answers file the scan by their database and say so plainly when unnamed`() = runTest {
        fun answering(quality: ProductQuality) = store({ true }) { object : ProductEnricher {
            override suspend fun lookup(gtin: Gtin) = QualityLookup.Found(gtin, quality)
        } }
        val unnamed = answering(ProductQuality(productType = ProductType.BEAUTY, nutriScoreNotApplicable = true)).lookup(code)
        assertThat(unnamed.problem).isEqualTo(ScanNameProblem.NOT_FOOD)
        assertThat(unnamed.name).isNull()
        assertThat(unnamed.category).isEqualTo(Category.PERSONAL_CARE)

        val named = answering(ProductQuality(productType = ProductType.PRODUCT, nutriScoreNotApplicable = true,
            productName = "Lavavajillas")).lookup(code)
        assertThat(named.problem).isNull()
        assertThat(named.name).isEqualTo("Lavavajillas")
        assertThat(named.category).isEqualTo(Category.HOUSEHOLD)

        // The name alone would read as a snack; the database says pet food, and there is no pet aisle.
        val pet = answering(ProductQuality(productType = ProductType.PET_FOOD, nutriScoreNotApplicable = true,
            productName = "dog biscuits", categories = listOf("en:biscuits"))).lookup(code)
        assertThat(pet.category).isEqualTo(Category.HOUSEHOLD)

        assertThat(answering(ProductQuality.EMPTY.copy(productType = ProductType.BEAUTY)).lookup(code).problem)
            .isEqualTo(ScanNameProblem.NOT_FOOD)
        assertThat(answering(ProductQuality.EMPTY).lookup(code).problem).isEqualTo(ScanNameProblem.NOT_FOUND)

        val id = answering(ProductQuality(productType = ProductType.BEAUTY)).add(unnamed, "hand cream")
        assertThat(smart.present(items.items(), history.snapshot())[id]!!.category).isEqualTo(Category.PERSONAL_CARE)
    }

    @Test fun `opt out while lookup waits discards the name and explains the disabled gate`() = runTest {
        var enabled = true
        val gate = CompletableDeferred<Unit>()
        val scans = store({ enabled }) { object : ProductEnricher {
            override suspend fun lookup(gtin: Gtin): QualityLookup {
                gate.await()
                return QualityLookup.Found(gtin, ProductQuality(productName = "test milk"))
            }
        } }
        val result = async { scans.lookup(code) }
        runCurrent()
        enabled = false
        gate.complete(Unit)
        val offer = result.await()
        assertThat(offer.name).isNull()
        assertThat(offer.problem).isEqualTo(ScanNameProblem.LOOKUP_DISABLED)
    }

    @Test fun `slow lookups add nothing and only confirmed names reach the list`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val enricher = object : ProductEnricher {
            override suspend fun lookup(gtin: Gtin): QualityLookup {
                gate.await(); return QualityLookup.Found(gtin, ProductQuality(productName = "test milk", categories = listOf("en:milks")))
            }
        }
        val scans = store({ true }) { enricher }
        val codes = (1..10).map { i ->
            val base = "4000000000" + i.toString().padStart(2, '0')
            val sum = base.mapIndexed { n, digit -> digit.digitToInt() * if (n % 2 == 0) 1 else 3 }.sum()
            Gtin.parse(base + ((10 - sum % 10) % 10))!!
        }
        val lookups = codes.map { async { scans.lookup(it) } }
        runCurrent()
        assertThat(lookups.all { !it.isCompleted }).isTrue()
        assertThat(items.items()).isEmpty()
        gate.complete(Unit)
        val offers = lookups.awaitAll()
        assertThat(items.items()).isEmpty()
        val id = scans.add(offers.first(), "test milk")
        assertThat(smart.present(items.items(), history.snapshot())[id]!!.category).isEqualTo(Category.DAIRY)
        assertThat(items.items().map { it.name }).containsExactly("test milk")
    }

    @Test fun `a category the person chose outranks the lookup's guess`() = runTest {
        val scans = store({ false }) { error("off") }
        history.savePreference(ItemPreference("soap", category = Category.HOUSEHOLD.name))
        val id = scans.add(scans.lookup(code).copy(category = Category.DAIRY), "soap")
        assertThat(smart.present(items.items(), history.snapshot())[id]!!.category).isEqualTo(Category.HOUSEHOLD)
    }
}

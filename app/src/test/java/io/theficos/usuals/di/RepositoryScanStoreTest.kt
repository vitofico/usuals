package io.theficos.usuals.di

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.data.local.*
import io.theficos.usuals.enrich.*
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.*
import io.theficos.usuals.smart.*
import io.theficos.usuals.ui.list.ItemEdit
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
        RepositoryScanStore(items, history, smart, enabled, enricher, BarcodeCategories(classifier))

    @Test fun `off means the network client is never constructed and a name can be supplied offline`() = runTest {
        var constructed = 0
        val scans = store({ false }) { constructed++; error("network must stay disconnected") }
        val id = scans.add(code)
        items.edit(id, code.value, null, emptyList(), note = "two packs")
        val offer = scans.lookup(id, code)!!
        assertThat(constructed).isEqualTo(0)
        assertThat(offer.name).isNull()
        assertThat(offer.problem).isEqualTo(ScanNameProblem.LOOKUP_DISABLED)
        assertThat(items.find(id)!!.name).isEqualTo(code.value)
        assertThat(scans.confirm(offer, "washing liquid")).isTrue()
        assertThat(items.find(id)!!.name).isEqualTo("washing liquid")
        assertThat(items.find(id)!!.note).isEqualTo("two packs")
        assertThat(history.snapshot().aliases).containsExactly(ItemAlias("gtin:${code.value}", "washing liquid"))
    }

    @Test fun `confirmed names survive Finish and removal while lookup stays off`() = runTest {
        val scans = store({ false }) { error("network must stay disconnected") }
        val id = scans.add(code)
        scans.confirm(scans.lookup(id, code)!!, "Washing Liquid")
        items.setChecked(id, true)
        history.finish()
        val next = scans.add(code)
        assertThat(items.find(next)!!.name).isEqualTo("Washing Liquid")
        assertThat(scans.lookup(next, code)).isNull()
        items.delete(next)
        val afterRemoval = scans.add(code)
        assertThat(items.find(afterRemoval)!!.name).isEqualTo("Washing Liquid")
        assertThat(history.snapshot().purchases).hasSize(1)
    }

    @Test fun `manual aliases still name a rescan without purchase history`() = runTest {
        val scans = store({ false }) { error("off") }
        val id = scans.add(code)
        scans.confirm(scans.lookup(id, code)!!, "washing liquid")
        items.delete(id)
        assertThat(items.find(scans.add(code))!!.name).isEqualTo("washing liquid")
    }

    @Test fun `lookup distinguishes absent names from failures and store codes`() = runTest {
        val id = items.add(ParsedItem(code.value), gtin = code)
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
            assertThat(scans.lookup(id, code)!!.problem).isEqualTo(problem)
        }
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
        val id = scans.add(code)
        val result = async { scans.lookup(id, code) }
        runCurrent()
        enabled = false
        gate.complete(Unit)
        val offer = result.await()!!
        assertThat(offer.name).isNull()
        assertThat(offer.problem).isEqualTo(ScanNameProblem.LOOKUP_DISABLED)
    }

    @Test fun `ten scans exist before slow lookup completes and names change only on confirmation`() = runTest {
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
        val ids = codes.map { scans.add(it) }
        val lookups = ids.zip(codes).map { (id, code) -> async { scans.lookup(id, code) } }
        runCurrent()
        assertThat(lookups.all { !it.isCompleted }).isTrue()
        assertThat(items.items()).hasSize(10)
        assertThat(items.items().map { it.name }).containsExactlyElementsIn(codes.map { it.value })
        assertThat(items.items().all { it.quantity == null }).isTrue()
        gate.complete(Unit)
        val offers = lookups.awaitAll().filterNotNull()
        assertThat(offers).hasSize(10)
        assertThat(items.items().map { it.name }).containsExactlyElementsIn(codes.map { it.value })
        scans.confirm(offers.first(), "test milk")
        assertThat(smart.present(items.items(), history.snapshot())[ids.first()]!!.category).isEqualTo(Category.DAIRY)
        assertThat(items.items().count { it.name == "test milk" }).isEqualTo(1)
    }

    @Test fun `a manual category and store survive a later barcode name confirmation`() = runTest {
        val scans = store({ false }) { error("off") }
        val id = scans.add(code)
        smart.edit(ItemEdit(id, code.value, null, Category.HOUSEHOLD, null, "market"))
        val offer = scans.lookup(id, code)!!
        scans.confirm(offer.copy(category = Category.DAIRY), "soap")
        assertThat(items.find(id)!!.store).isEqualTo("market")
        assertThat(smart.present(items.items(), history.snapshot())[id]!!.category).isEqualTo(Category.HOUSEHOLD)
    }    @Test fun `scan naming preserves newer quantity and refuses a stale renamed source merge`() = runTest {
        val scans = store({ false }) { error("off") }
        val id = scans.add(code)
        val offer = scans.lookup(id, code)!!
        items.setQuantity(id, Quantity(2.0, MeasureUnit.LITRE))
        assertThat(scans.confirm(offer, "milk")).isTrue()
        assertThat(items.find(id)!!.quantity!!.amount).isEqualTo(2.0)
        items.add(ParsedItem("bread"))
        assertThat(scans.mergePreview(offer, "bread")).isNull()
        assertThat(scans.confirm(offer, "bread")).isFalse()
        assertThat(items.find(id)!!.name).isEqualTo("milk")
    }

}

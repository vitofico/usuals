package io.theficos.usuals.ui.list

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.data.local.HistoryRepository
import io.theficos.usuals.data.local.ItemRepository
import io.theficos.usuals.data.local.UsualsDatabase
import io.theficos.usuals.di.RepositoryHistoryStore
import io.theficos.usuals.di.RepositoryItemStore
import io.theficos.usuals.di.RepositorySmartStore
import io.theficos.usuals.enrich.CachedQuality
import io.theficos.usuals.enrich.QualityLookup
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.ItemPreference
import io.theficos.usuals.model.ShoppingSnapshot
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.smart.CategoryClassifier
import io.theficos.usuals.smart.HistoryRanker
import io.theficos.usuals.smart.RepeatPlanner
import io.theficos.usuals.ui.history.ScopedHistoryStore
import io.theficos.usuals.ui.product.ProductInfoStore
import io.theficos.usuals.ui.product.ProductProvenance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Barcode memory end to end over Room: the rows the app re-adds and the rows a
 * person types show the household's barcode, and Finish still records none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BarcodeMemoryListTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var db: UsualsDatabase
    private lateinit var items: ItemRepository
    private lateinit var history: HistoryRepository
    private lateinit var smart: RepositorySmartStore
    private var now = 1_700_000_000_000L
    private var ids = 0
    private val milk = Gtin.parse("4006381333931")!!
    private val coffee = Gtin.parse("4000000000013")!!
    private val pesto = Gtin.parse("4000000000020")!!

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        items = ItemRepository(db, { now }, { "memory-${++ids}" })
        history = HistoryRepository(db, items, { now }, { "memory-${++ids}" })
        smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
    }
    @After fun tearDown() = db.close()

    @Test fun `suggested, repeated and typed rows show the remembered barcode and Finish records none`() = runTest(main.dispatcher) {
        val scannedAt = now
        for ((name, code) in listOf("milk" to milk, "coffee" to coffee, "pesto" to pesto)) {
            items.setChecked(items.add(ParsedItem(name), gtin = code), true)
        }
        now += 1_000
        history.finish()

        now += 8 * RepeatPlanner.DAY_MILLIS
        smart.addReviewed("milk", items.currentListId(), now)
        history.savePreference(ItemPreference("coffee", repeatEveryDays = 7))
        smart.maintainRepeats(now, items.items(), history.snapshot())
        items.add(ParsedItem("pesto"))
        val added = items.items()
        assertThat(added.map { it.name to it.fromSuggestion }).containsExactly("milk" to true, "coffee" to true, "pesto" to false)
        assertThat(added.map { it.gtin }).containsExactly(null, null, null)
        added.forEach { items.setChecked(it.id, true) }

        // Room's invalidation flow cannot run on the inline executors this test
        // needs, so the list is handed the same snapshot Room would emit.
        val shopping = MutableStateFlow(ShoppingSnapshot(items.currentListId(), items.items(), history.snapshot()))
        val scoped = object : ScopedHistoryStore by RepositoryHistoryStore(history, LocalUtteranceParser(), smart) {
            override fun observeShopping() = shopping
        }
        val vm = ListViewModel(RepositoryItemStore(items), LocalUtteranceParser(), FakeSpeechSource(false), clock = { now },
            historyStore = scoped, smartStore = smart, productInfoStore = closedLookup, compute = main.dispatcher)
        val rows = vm.state.value.checked.associateBy { it.rawName }
        for ((name, code) in listOf("milk" to milk, "coffee" to coffee, "pesto" to pesto)) {
            val row = rows.getValue(name)
            assertThat(row.gtin).isNull()
            assertThat(row.productGtin).isEqualTo(code)
            assertThat(row.provenance).isEqualTo(ProductProvenance.Remembered(scannedAt))
        }

        vm.onProductInfo(rows.getValue("pesto"))
        assertThat(vm.state.value.productInfo!!.gtin).isEqualTo(pesto)
        assertThat(vm.state.value.productInfo!!.provenance).isEqualTo(ProductProvenance.Remembered(scannedAt))
        vm.onDismissProduct()

        now += 1_000
        vm.onFinishShop()
        val latest = history.snapshot().trips.maxBy { it.closedAt }
        val recorded = history.snapshot().purchases.filter { it.tripId == latest.id }
        assertThat(recorded.map { it.name }).containsExactly("milk", "coffee", "pesto")
        assertThat(recorded.map { it.gtin }).containsExactly(null, null, null)
    }

    private val closedLookup = object : ProductInfoStore {
        override fun enabled() = false
        override suspend fun cached(gtin: Gtin): CachedQuality? = null
        override suspend fun refresh(gtin: Gtin): QualityLookup = error("Lookup is off")
    }
}

package io.theficos.usuals.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.ParsedItem
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryRepositoryTest {
    private lateinit var database: UsualsDatabase
    private lateinit var items: ItemRepository
    private lateinit var history: HistoryRepository
    private var now = 1_700_000_000_000L
    private val ids = AtomicInteger()
    private fun id() = "id-${ids.incrementAndGet()}"
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java,
    ).allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()

    @Before fun setUp() {
        database = database()
        items = ItemRepository(database, { now }, ::id)
        history = HistoryRepository(database, items, { now }, ::id)
    }
    @After fun tearDown() = database.close()

    @Test fun `finish records final state once and preserves each check date`() = runTest {
        val milk = items.add(ParsedItem("milk", Quantity(1.0, MeasureUnit.LITRE)))
        val bread = items.add(ParsedItem("bread"))
        items.setChecked(milk, true)
        val checkedAt = now
        now += 86_400_000L
        items.setChecked(milk, true)
        items.rename(milk, "oat milk")
        items.setQuantity(milk, Quantity(2.0, MeasureUnit.LITRE))
        items.setChecked(bread, true)
        val breadAt = now
        now += 1_000
        val finished = history.finish()!!
        assertThat(finished.count).isEqualTo(2)
        assertThat(history.finish()).isNull()
        val purchases = history.snapshot().purchases.associateBy { it.id }
        assertThat(purchases.keys).containsExactly(milk, bread)
        assertThat(purchases[milk]!!.name).isEqualTo("oat milk")
        assertThat(purchases[milk]!!.quantity).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))
        assertThat(purchases[milk]!!.boughtAt).isEqualTo(checkedAt)
        assertThat(purchases[bread]!!.boughtAt).isEqualTo(breadAt)
        assertThat(history.snapshot().trips).hasSize(1)
        assertThat(items.items()).isEmpty()
    }

    @Test fun `undo restores the exact list and removes the trip and its purchases`() = runTest {
        val milk = items.add(ParsedItem("milk"))
        items.add(ParsedItem("bread"))
        items.setChecked(milk, true)
        val before = items.items()
        now += 1000
        val finished = history.finish()!!
        assertThat(items.items().map { it.name }).containsExactly("bread")
        assertThat(history.undo(finished.trip.id)).isTrue()
        assertThat(history.undo(finished.trip.id)).isFalse()
        assertThat(items.items()).containsExactlyElementsIn(before).inOrder()
        assertThat(history.snapshot().purchases).isEmpty()
        assertThat(history.snapshot().trips).isEmpty()
    }

    @Test fun `unchecking clears the timestamp and rechecking starts a new one`() = runTest {
        val milk = items.add(ParsedItem("milk"))
        items.setChecked(milk, true)
        now += 1000
        items.setChecked(milk, false)
        assertThat(items.find(milk)!!.checkedAt).isNull()
        now += 1000
        items.setChecked(milk, true)
        assertThat(items.find(milk)!!.checkedAt).isEqualTo(now)
    }

    @Test fun `removal records no purchase and finish reaps old tombstones`() = runTest {
        val deleted = items.add(ParsedItem("deleted"))
        items.delete(deleted)
        now += ItemRepository.FORTNIGHT_MILLIS + 1
        val milk = items.add(ParsedItem("milk"))
        items.setChecked(milk, true)
        history.finish()
        assertThat(database.itemDao().find(deleted.value)).isNull()
        assertThat(history.snapshot().purchases.map { it.name }).containsExactly("milk")
    }

    @Test fun `a dated pasted list is history and never adds living rows`() = runTest {
        val date = now - 30 * 86_400_000L
        history.importPast(listOf(ParsedItem("milk", Quantity.pieces(2)), ParsedItem("bread")), date)
        assertThat(items.items()).isEmpty()
        assertThat(history.snapshot().purchases.map { it.boughtAt }).containsExactly(date, date)
        assertThat(history.snapshot().trips.single().closedAt).isEqualTo(date)
    }

    @Test fun `all shopping data round trips to a separate database and reimport is idempotent`() = runTest {
        val milk = items.add(ParsedItem("milk"), gtin = Gtin.parse("4006381333931"))
        items.setChecked(milk, true)
        history.finish()
        items.add(ParsedItem("bread"))
        history.savePreference(ItemPreference("milk", "DAIRY", repeatEveryDays = 21))
        history.saveAlias(ItemAlias("whole milk", "milk"))
        val exported = history.exportJson()
        assertThat(Json.parseToJsonElement(exported).jsonObject.keys).containsExactly(
            "formatVersion", "lists", "selectedListId", "items", "trips", "purchases", "preferences", "aliases",
        )
        val second = database()
        try {
            val secondItems = ItemRepository(second, { now }, ::id)
            val secondHistory = HistoryRepository(second, secondItems, { now }, ::id)
            secondHistory.importJson(exported)
            secondHistory.importJson(exported)
            assertThat(secondHistory.snapshot()).isEqualTo(history.snapshot())
            assertThat(secondItems.items()).isEqualTo(items.items())
            assertThat(second.historyDao().allItems()).isEqualTo(database.historyDao().allItems())
            assertThat(second.qualityDao().size()).isEqualTo(0)
        } finally { second.close() }
    }

    @Test fun `an old live-list export cannot resurrect a purchase after its tombstone was reaped`() = runTest {
        val milk = items.add(ParsedItem("milk"))
        val oldExport = history.exportJson()
        items.setChecked(milk, true)
        history.finish()
        now += ItemRepository.FORTNIGHT_MILLIS + 1
        items.reap()
        history.importJson(oldExport)
        assertThat(items.items()).isEmpty()
        assertThat(history.snapshot().purchases).hasSize(1)
    }

    @Test fun `invalid imported data makes no partial writes`() = runTest {
        val milk = items.add(ParsedItem("milk"), gtin = Gtin.parse("4006381333931"))
        items.setChecked(milk, true)
        history.finish()
        val exported = history.exportJson()
        val before = history.snapshot()
        val failed = runCatching { history.importJson(exported.replace("4006381333931", "bad-code")) }
        assertThat(failed.isFailure).isTrue()
        assertThat(history.snapshot()).isEqualTo(before)
        assertThat(database.historyDao().allItems()).hasSize(1)
    }

    @Test fun `closing only stale rows leaves recent checked rows on the list`() = runTest {
        val old = items.add(ParsedItem("old"))
        items.setChecked(old, true)
        now += 13 * 60 * 60 * 1000
        val recent = items.add(ParsedItem("recent"))
        items.setChecked(recent, true)
        history.finish(setOf(old))
        assertThat(items.items().map { it.id }).containsExactly(recent)
        assertThat(history.snapshot().purchases.map { it.id }).containsExactly(old)
    }
    @Test fun `concurrent capture and Finish preserve quantities without resurrecting purchased ids`() = runTest {
        items.add(ParsedItem("milk", Quantity.pieces(1)))
        val additions = (1..20).map {
            async(Dispatchers.Default) { items.add(ParsedItem("milk", Quantity.pieces(1))) }
        }
        val closing = async(Dispatchers.Default) {
            repeat(10) {
                for (item in items.items()) items.setChecked(item.id, true)
                history.finish()
                yield()
            }
        }
        additions.awaitAll()
        closing.await()
        val live = items.items()
        val purchases = history.snapshot().purchases
        assertThat(live.map { it.id }.intersect(purchases.map { it.id }.toSet())).isEmpty()
        assertThat(live.sumOf { it.quantity?.amount ?: 0.0 } + purchases.sumOf { it.quantity?.amount ?: 0.0 })
            .isEqualTo(21.0)
        val open = live.filterNot { it.checked }
        assertThat(open.map { it.normalizedName }.distinct()).hasSize(open.size)
    }

    @Test fun `an imported alias cannot create a cycle with existing answers`() = runTest {
        history.saveAlias(ItemAlias("a", "b"))
        val archive = """{"formatVersion":1,"aliases":[{"fromKey":"b","toKey":"a"}]}"""
        assertThat(runCatching { history.importJson(archive) }.isFailure).isTrue()
        assertThat(history.snapshot().aliases).containsExactly(ItemAlias("a", "b"))
    }

}

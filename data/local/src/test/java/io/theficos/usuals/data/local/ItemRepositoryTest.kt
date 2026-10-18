package io.theficos.usuals.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.Item
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.MemberId
import io.theficos.usuals.model.Quantity
import app.cash.turbine.test
import io.theficos.usuals.parse.ParsedItem
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The list, exercised through the boundary the UI actually uses.
 *
 * Time and ids are injected, so nothing here depends on the wall clock, on how
 * fast the machine is, or on the order a hash map felt like today.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ItemRepositoryTest {

    private lateinit var db: UsualsDatabase
    private lateinit var repo: ItemRepository
    private lateinit var queries: ExecutorService

    /** Fixed instant, moved by hand in the tests that care. */
    private var now = 1_700_000_000_000L
    private var ids = 0

    private val marta = MemberId("marta")

    @Before
    fun setUp() {
        queries = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            UsualsDatabase::class.java,
        )
            .allowMainThreadQueries()
            // A Flow query must not run on the thread-local context of a
            // suspending write transaction. Drain this executor before closing.
            .setQueryExecutor(queries)
            .setTransactionExecutor { it.run() }
            .build()
        repo = ItemRepository(db, clock = { now }, newId = { "item-${++ids}" })
    }

    @After
    fun tearDown() {
        queries.submit {}.get(5, TimeUnit.SECONDS)
        db.close()
        queries.shutdown()
        check(queries.awaitTermination(5, TimeUnit.SECONDS))
    }

    /**
     * A direct read, not a one-shot collection of the observable query.
     *
     * These tests assert what the list contains, and the flow is a different
     * question with a different failure mode. `observeItems` has its own test
     * below, where the subscription is opened and closed deliberately.
     */
    private suspend fun visible(): List<Item> = repo.items()

    private suspend fun names(): List<String> = visible().map { it.name }

    @Test
    fun `items come back in list order, newest at the end`() = runTest {
        repo.add(ParsedItem("Leche entera"), marta)
        repo.add(ParsedItem("Tomates"), marta)
        repo.add(ParsedItem("Pan de payés"), marta)

        assertThat(names()).containsExactly("Leche entera", "Tomates", "Pan de payés").inOrder()
        assertThat(visible().map { it.position }).isInOrder()
        assertThat(visible().first().addedBy).isEqualTo(marta)
    }

    @Test
    fun `case and accents fold onto one row`() = runTest {
        repo.add(ParsedItem("Leche entera"))
        repo.add(ParsedItem("leche ENTERA"))
        repo.add(ParsedItem("Plátanos"))
        repo.add(ParsedItem("platano"))

        // The display name stays as it was first written. A merge is not a rename.
        assertThat(names()).containsExactly("Leche entera", "Plátanos").inOrder()
    }

    @Test
    fun `a plural merges into the singular already on the list`() = runTest {
        val first = repo.add(ParsedItem("Tomates"))
        val second = repo.add(ParsedItem("tomate"))

        assertThat(second).isEqualTo(first)
        assertThat(names()).containsExactly("Tomates")
        assertThat(visible().single().normalizedName).isEqualTo("tomate")
    }

    @Test
    fun `quantities in the same unit are summed rather than stacked`() = runTest {
        repo.add(ParsedItem("Leche", Quantity(1.0, MeasureUnit.LITRE)))
        repo.add(ParsedItem("leche", Quantity(2.0, MeasureUnit.LITRE)))

        assertThat(visible().single().quantity).isEqualTo(Quantity(3.0, MeasureUnit.LITRE))
    }

    @Test
    fun `a comparable unit is converted into the unit the row already uses`() = runTest {
        repo.add(ParsedItem("Tomates", Quantity(0.5, MeasureUnit.KILOGRAM)))
        repo.add(ParsedItem("tomate", Quantity(500.0, MeasureUnit.GRAM)))

        assertThat(visible().single().quantity).isEqualTo(Quantity(1.0, MeasureUnit.KILOGRAM))
    }

    @Test
    fun `quantities that cannot be added leave the existing row alone`() = runTest {
        repo.add(ParsedItem("Leche", Quantity(2.0, MeasureUnit.LITRE)))
        repo.add(ParsedItem("leche", Quantity(500.0, MeasureUnit.GRAM)))

        val merged = visible().single()
        assertThat(merged.quantity).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))
    }

    @Test
    fun `a count never merges into a pack`() = runTest {
        repo.add(ParsedItem("Cervezas", Quantity(3.0, MeasureUnit.PACK)))
        repo.add(ParsedItem("cerveza", Quantity(2.0, MeasureUnit.PIECE)))

        assertThat(visible().single().quantity).isEqualTo(Quantity(3.0, MeasureUnit.PACK))
    }

    @Test
    fun `a merge fills the gaps the existing row has`() = runTest {
        val id = repo.add(ParsedItem("Leche"))
        now += 1_000

        repo.add(
            ParsedItem("leche", Quantity(1.0, MeasureUnit.LITRE), note = "para el café"),
            gtin = Gtin.parse("8410123456783"),
        )

        val merged = visible().single()
        assertThat(merged.id).isEqualTo(id)
        assertThat(merged.quantity).isEqualTo(Quantity(1.0, MeasureUnit.LITRE))
        assertThat(merged.note).isEqualTo("para el café")
        assertThat(merged.gtin).isEqualTo(Gtin.parse("8410123456783"))
        assertThat(merged.updatedAt).isEqualTo(now)
    }

    @Test
    fun `a merge never overwrites what the row already says`() = runTest {
        repo.add(
            ParsedItem("Leche", Quantity(1.0, MeasureUnit.LITRE), note = "entera"),
            gtin = Gtin.parse("8410123456783"),
        )
        repo.add(
            ParsedItem("leche", note = "desnatada"),
            gtin = Gtin.parse("8410987654325"),
        )

        val merged = visible().single()
        assertThat(merged.note).isEqualTo("entera")
        assertThat(merged.gtin).isEqualTo(Gtin.parse("8410123456783"))
    }

    @Test
    fun `a duplicate that says nothing new does not touch the row`() = runTest {
        val id = repo.add(ParsedItem("Leche", Quantity(1.0, MeasureUnit.LITRE)))
        val before = db.itemDao().find(id.value)

        now += 60_000
        repo.add(ParsedItem("LECHE"))

        // updatedAt is a last-write-wins register. Bumping it for a write that
        // changes nothing hands a concurrent edit on another phone a fight it
        // should not have to have.
        assertThat(db.itemDao().find(id.value)).isEqualTo(before)
    }

    @Test
    fun `a checked row does not absorb a new request`() = runTest {
        val first = repo.add(ParsedItem("Leche"))
        repo.setChecked(first, true)

        val second = repo.add(ParsedItem("leche"))

        assertThat(second).isNotEqualTo(first)
        assertThat(visible()).hasSize(2)
        assertThat(visible().map { it.checked }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `delete hides the row and keeps the tombstone`() = runTest {
        val id = repo.add(ParsedItem("Tomates"))
        repo.add(ParsedItem("Leche"))

        now += 5_000
        repo.delete(id)

        assertThat(names()).containsExactly("Leche")
        val tombstone = db.itemDao().find(id.value)
        assertThat(tombstone).isNotNull()
        assertThat(tombstone!!.deletedAt).isEqualTo(now)
    }

    @Test
    fun `a tombstoned name does not swallow a new row`() = runTest {
        val first = repo.add(ParsedItem("Leche"))
        repo.delete(first)

        val second = repo.add(ParsedItem("leche"))

        assertThat(second).isNotEqualTo(first)
        assertThat(names()).containsExactly("leche")
    }

    @Test
    fun `reap drops tombstones past the fortnight and keeps everything else`() = runTest {
        val day = 24L * 60 * 60 * 1000
        val old = repo.add(ParsedItem("Tomates"))
        repo.delete(old)

        now += 13 * day
        val recent = repo.add(ParsedItem("Pan"))
        repo.delete(recent)

        now += 2 * day
        repo.add(ParsedItem("Leche"))

        val reaped = repo.reap()

        assertThat(reaped).isEqualTo(1)
        assertThat(db.itemDao().find(old.value)).isNull()
        assertThat(db.itemDao().find(recent.value)).isNotNull()
        assertThat(names()).containsExactly("Leche")
    }

    @Test
    fun `reap takes a window, and a shorter one takes more`() = runTest {
        val id = repo.add(ParsedItem("Tomates"))
        repo.delete(id)
        now += 60_000

        assertThat(repo.reap(olderThanMillis = 120_000)).isEqualTo(0)
        assertThat(repo.reap(olderThanMillis = 30_000)).isEqualTo(1)
    }

    @Test
    fun `setChecked, setQuantity and rename edit the row and stamp the clock`() = runTest {
        val id = repo.add(ParsedItem("Leche"))

        now += 1_000
        repo.setChecked(id, true)
        assertThat(visible().single().checked).isTrue()
        assertThat(visible().single().updatedAt).isEqualTo(now)

        now += 1_000
        repo.setQuantity(id, Quantity(2.0, MeasureUnit.LITRE))
        assertThat(visible().single().quantity).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))

        now += 1_000
        repo.setQuantity(id, null)
        assertThat(visible().single().quantity).isNull()

        now += 1_000
        repo.rename(id, "  Leche entera  ")
        assertThat(visible().single().name).isEqualTo("Leche entera")
        assertThat(visible().single().updatedAt).isEqualTo(now)
    }

    @Test
    fun `rename re-stamps the dedup key`() = runTest {
        val id = repo.add(ParsedItem("Leche"))
        repo.rename(id, "Tomates")

        val merged = repo.add(ParsedItem("tomate"))

        assertThat(merged).isEqualTo(id)
        assertThat(visible()).hasSize(1)
    }

    @Test
    fun `edits do not touch a deleted row`() = runTest {
        val id = repo.add(ParsedItem("Leche"))
        repo.delete(id)
        val tombstoned = db.itemDao().find(id.value)

        repo.setChecked(id, true)
        repo.rename(id, "Pan")

        assertThat(db.itemDao().find(id.value)).isEqualTo(tombstoned)
    }

    @Test
    fun `positions are never reused, tombstones included`() = runTest {
        val first = repo.add(ParsedItem("Leche"))
        val firstPosition = db.itemDao().find(first.value)!!.position
        repo.delete(first)

        val next = repo.nextPosition()
        repo.add(ParsedItem("Tomates"))

        assertThat(next).isGreaterThan(firstPosition)
        assertThat(visible().single().position).isEqualTo(next)
    }

    @Test
    fun `rows sharing a position and an instant still have one order`() = runTest {
        // Straight through the DAO: two rows that arrived from two phones in
        // the same millisecond, before any of them had a position of its own.
        db.itemDao().insert(row(id = "b-row", name = "Tomates"))
        db.itemDao().insert(row(id = "a-row", name = "Leche"))

        assertThat(names()).containsExactly("Leche", "Tomates").inOrder()
    }

    private fun row(id: String, name: String) = ItemEntity(
        id = id,
        rawText = name,
        name = name,
        normalizedName = name.lowercase(),
        position = 1.0,
        createdAt = now,
        updatedAt = now,
    )
    /**
     * The observable query the list screen actually collects.
     *
     * Worth its own test because it is a different code path from the direct
     * read the rest of this class uses: the UI never polls, it subscribes, and
     * a write that does not invalidate the query would leave the list looking
     * correct in every other test here and stale on the phone.
     */
    @Test
    fun `observing the list emits again after a write`() = runTest {
        repo.add(ParsedItem("Leche"))

        repo.observeItems().test {
            assertThat(awaitItem().map { it.name }).containsExactly("Leche")

            repo.add(ParsedItem("Tomates"))
            assertThat(awaitItem().map { it.name }).containsExactly("Leche", "Tomates").inOrder()

            cancelAndIgnoreRemainingEvents()
        }
    }

}

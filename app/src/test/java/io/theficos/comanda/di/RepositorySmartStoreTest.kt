package io.theficos.comanda.di

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.data.local.*
import io.theficos.comanda.model.*
import io.theficos.comanda.parse.InputLanguages
import io.theficos.comanda.parse.ParsedItem
import io.theficos.comanda.smart.*
import io.theficos.comanda.ui.list.ItemEdit
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepositorySmartStoreTest {
    private lateinit var db: ComandaDatabase
    private lateinit var items: ItemRepository
    private lateinit var history: HistoryRepository
    private lateinit var smart: RepositorySmartStore
    private var now = 1_700_000_000_000L
    private var ids = 0
    private fun id() = "smart-${++ids}"
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ComandaDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        items = ItemRepository(db, { now }, ::id)
        history = HistoryRepository(db, items, { now }, ::id)
        smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
    }
    @After fun tearDown() = db.close()

    @Test fun `archive three restores private favorites without sharing or erasing local choices`() = runTest {
        val tools = io.theficos.comanda.ui.shopping.ShoppingToolsStore()
        val favorite = io.theficos.comanda.ui.shopping.SavedShoppingItem("favorite", "milk", 2.0, "LITRE")
        tools.pin(favorite)
        items.add(ParsedItem("bread"))
        val adapter = RepositoryHistoryStore(history, io.theficos.comanda.parse.LocalUtteranceParser(), smart, tools)
        val archive = adapter.exportJson()
        assertThat(archive).contains("\"formatVersion\":3")
        tools.unpin("favorite")
        adapter.importJson(archive); adapter.importJson(archive)
        assertThat(tools.state.value.portable.favorites).containsExactly(favorite)
        assertThat(items.items()).hasSize(1)
        assertThat(history.snapshot().purchases).isEmpty()
        var rejected = false
        try { adapter.importJson(archive.replace("LITRE", "INVALID")) } catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
        assertThat(items.items()).hasSize(1)
        assertThat(tools.state.value.portable.favorites).containsExactly(favorite)
    }

    @Test fun `rebuy is idempotent keeps quantities and refuses a stale list scope`() = runTest {
        val scope = items.currentListId()
        val milk = items.add(ParsedItem("milk", Quantity(1.0, MeasureUnit.LITRE)))
        val source = listOf(io.theficos.comanda.ui.shopping.SavedShoppingItem("old-milk", "milk", 2.0, "LITRE"),
            io.theficos.comanda.ui.shopping.SavedShoppingItem("old-bread", "bread"))
        assertThat(smart.addAgain(source, scope)).isEqualTo(1)
        assertThat(smart.addAgain(source, scope)).isEqualTo(0)
        assertThat(items.find(milk)!!.quantity).isEqualTo(Quantity(1.0, MeasureUnit.LITRE))
        assertThat(items.items()).hasSize(2)
        assertThat(history.snapshot().purchases).isEmpty()
        var rejected = false
        try { smart.addAgain(source, "other-list") } catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
        assertThat(items.items()).hasSize(2)
    }

    @Test fun `a Move override sticks after finishing and adding the item again`() = runTest {
        val milk = items.add(ParsedItem("milk"))
        assertThat(smart.present(items.items(), history.snapshot())[milk]!!.category).isEqualTo(Category.DAIRY)
        assertThat(smart.edit(ItemEdit(milk, "milk", Quantity(1.0, MeasureUnit.LITRE), Category.HOUSEHOLD, null))).isTrue()
        assertThat(items.find(milk)!!.quantity).isEqualTo(Quantity(1.0, MeasureUnit.LITRE))
        items.setChecked(milk, true)
        history.finish()
        now += 1000
        val again = items.add(ParsedItem("milk"))
        val presentation = smart.present(items.items(), history.snapshot())[again]!!
        assertThat(presentation.category).isEqualTo(Category.HOUSEHOLD)
        assertThat(presentation.override).isEqualTo(Category.HOUSEHOLD)
    }

    @Test fun `day 21 adds one marked row and leaves quantity unknown`() = runTest {
        val milk = items.add(ParsedItem("milk"))
        smart.edit(ItemEdit(milk, "milk", Quantity(2.0, MeasureUnit.LITRE), null, 21))
        items.setChecked(milk, true)
        history.finish()
        now += 21 * RepeatPlanner.DAY_MILLIS - 1
        smart.maintainRepeats(now, items.items(), history.snapshot())
        assertThat(items.items()).isEmpty()
        now += 1
        smart.maintainRepeats(now, emptyList(), history.snapshot())
        smart.maintainRepeats(now, emptyList(), history.snapshot())
        val added = items.items().single()
        assertThat(added.name).isEqualTo("milk")
        assertThat(added.fromSuggestion).isTrue()
        assertThat(added.quantity).isNull()
        assertThat(added.id).isNotEqualTo(milk)
    }

    @Test fun `swiping an automatic repeat stays removed until the next shop`() = runTest {
        history.savePreference(ItemPreference("milk", repeatEveryDays = 21))
        smart.maintainRepeats(now, emptyList(), history.snapshot())
        items.delete(items.items().single().id)
        smart.maintainRepeats(now, emptyList(), history.snapshot())
        assertThat(items.items()).isEmpty()
        assertThat(history.snapshot().purchases).isEmpty()
        val bread = items.add(ParsedItem("bread"))
        items.setChecked(bread, true)
        now += 1000
        history.finish()
        smart.maintainRepeats(now, emptyList(), history.snapshot())
        assertThat(items.items().single().name).isEqualTo("milk")
        assertThat(history.snapshot().purchases.map { it.name }).containsExactly("bread")
    }

    @Test fun `a confirmed name alias deduplicates capture without another prompt`() = runTest {
        history.saveAlias(ItemAlias("whole milk", "milk"))
        items.add(ParsedItem("milk", Quantity.pieces(1)))
        items.add(ParsedItem("whole milk", Quantity.pieces(2)))
        assertThat(items.items()).hasSize(1)
        assertThat(items.items().single().quantity).isEqualTo(Quantity.pieces(3))
    }
    @Test fun `Undo retracts only automatic rows caused by closing the shop`() = runTest {
        history.savePreference(ItemPreference("milk", repeatEveryDays = 21))
        smart.maintainRepeats(now, emptyList(), history.snapshot())
        items.delete(items.items().single().id)
        val bread = items.add(ParsedItem("bread"))
        items.setChecked(bread, true)
        val before = items.items()
        now += 1000
        val finished = smart.finish(null)!!
        assertThat(items.items().single().name).isEqualTo("milk")
        smart.undo(finished.trip.id)
        assertThat(items.items()).containsExactlyElementsIn(before).inOrder()
        smart.maintainRepeats(now, items.items(), history.snapshot())
        assertThat(items.items()).containsExactlyElementsIn(before).inOrder()
    }

    @Test fun `Undo preserves a subsequent edit to an automatic row`() = runTest {
        history.savePreference(ItemPreference("milk", repeatEveryDays = 21))
        smart.maintainRepeats(now, emptyList(), history.snapshot())
        items.delete(items.items().single().id)
        val bread = items.add(ParsedItem("bread"))
        items.setChecked(bread, true)
        now += 1000
        val finished = smart.finish(null)!!
        val milk = items.items().single()
        items.setQuantity(milk.id, Quantity.pieces(2))
        smart.undo(finished.trip.id)
        assertThat(items.find(milk.id)!!.quantity).isEqualTo(Quantity.pieces(2))
        assertThat(items.items().map { it.name }).containsExactly("milk", "bread")
    }

    @Test fun `a second explicit scan rename replaces the old barcode alias`() = runTest {
        val code = Gtin.parse("4006381333931")!!
        val scanned = items.add(ParsedItem(code.value), gtin = code)
        smart.edit(ItemEdit(scanned, "milk", null, Category.DAIRY, null))
        smart.edit(ItemEdit(scanned, "oat milk", null, Category.DRINKS, 21))
        assertThat(history.snapshot().aliases.single().toKey).isEqualTo("oat milk")
        assertThat(smart.present(items.items(), history.snapshot())[scanned]!!.category).isEqualTo(Category.DRINKS)
        assertThat(history.snapshot().preferences.single { it.key == "oat milk" }.repeatEveryDays).isEqualTo(21)
    }

    @Test fun `suggestion buttons persist snooze and Never and mark only accepted rows`() = runTest {
        history.importPast(listOf(ParsedItem("milk"), ParsedItem("bread"), ParsedItem("eggs")), now - 7 * RepeatPlanner.DAY_MILLIS)
        smart.chooseSuggestion("milk", SuggestionChoice.NOT_NOW, now)
        smart.chooseSuggestion("bread", SuggestionChoice.NEVER, now)
        smart.chooseSuggestion("eggs", SuggestionChoice.ADD, now)
        assertThat(items.items().single().name).isEqualTo("eggs")
        assertThat(items.items().single().fromSuggestion).isTrue()
        assertThat(items.items().single().quantity).isNull()
        assertThat(smart.suggestions(now, history.snapshot(), items.items())).isEmpty()
        items.setChecked(items.items().single().id, true)
        now += 1000
        smart.finish(null)
        assertThat(smart.suggestions(now, history.snapshot(), items.items()).map { it.name }).contains("milk")
        assertThat(smart.suggestions(now, history.snapshot(), items.items()).map { it.name }).doesNotContain("bread")
    }

    @Test fun `a two litre hint never overwrites one litre explicitly captured`() = runTest {
        repeat(2) { index -> history.importPast(listOf(ParsedItem("milk", Quantity(2.0, MeasureUnit.LITRE))), now - (index + 1) * RepeatPlanner.DAY_MILLIS) }
        val milk = items.add(ParsedItem("milk", Quantity(1.0, MeasureUnit.LITRE)))
        smart.applyQuantityHint(milk)
        assertThat(items.find(milk)!!.quantity).isEqualTo(Quantity(1.0, MeasureUnit.LITRE))
        items.setQuantity(milk, null)
        assertThat(smart.present(items.items(), history.snapshot())[milk]!!.quantityHint).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))
        assertThat(items.find(milk)!!.quantity).isNull()
        smart.applyQuantityHint(milk)
        assertThat(items.find(milk)!!.quantity).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))
    }

    @Test fun `confirmed merge records an alias and preserves explicit quantity atomically`() = runTest {
        history.importPast(listOf(ParsedItem("milk")), now - RepeatPlanner.DAY_MILLIS)
        val milk = items.add(ParsedItem("milk", Quantity(1.0, MeasureUnit.LITRE)))
        val usual = smart.similar("whole milk", history.snapshot(), items.items())!!
        assertThat(history.snapshot().aliases).isEmpty()
        val incoming = Item(ItemId("incoming"), "1 l whole milk", "whole milk", "whole milk",
            Quantity(1.0, MeasureUnit.LITRE), createdAt = now, updatedAt = now)
        assertThat(smart.addMerged(incoming, usual)).isTrue()
        assertThat(history.snapshot().aliases).containsExactly(ItemAlias("whole milk", "milk"))
        assertThat(items.items()).hasSize(1)
        assertThat(items.find(milk)!!.quantity).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))
        assertThat(smart.similar("whole milk", history.snapshot(), items.items())).isNull()
    }

    @Test fun `accepting an offered repeat preserves a manual category`() = runTest {
        history.savePreference(ItemPreference("milk", category = "HOUSEHOLD"))
        smart.setRepeat(RepeatOffer("milk", "milk", 21))
        val pref = history.snapshot().preferences.single()
        assertThat(pref.category).isEqualTo("HOUSEHOLD")
        assertThat(pref.repeatEveryDays).isEqualTo(21)
    }
    @Test fun `quantity-only editor keeps another devices rename and transferred preferences`() = runTest {
        val id = items.add(ParsedItem("milk", Quantity(1.0, MeasureUnit.LITRE)))
        smart.edit(ItemEdit(id, "milk", Quantity(1.0, MeasureUnit.LITRE), Category.HOUSEHOLD, 21))
        val frozen = items.items().single().version!!
        smart.edit(ItemEdit(id, "oat milk", Quantity(1.0, MeasureUnit.LITRE), Category.DAIRY, 7))
        assertThat(smart.edit(ItemEdit(id, "milk", Quantity(2.0, MeasureUnit.LITRE), Category.HOUSEHOLD, 21,
            version = frozen, changeCategory = false, changeRepeat = false))).isTrue()
        assertThat(items.items().single().name).isEqualTo("oat milk")
        assertThat(items.items().single().quantity!!.amount).isEqualTo(2.0)
        val presentation = smart.present(items.items(), history.snapshot()).getValue(id)
        assertThat(presentation.override).isEqualTo(Category.DAIRY)
        assertThat(presentation.repeatEveryDays).isEqualTo(7)
        assertThat(LocalReplica(db).observeStatus(LOCAL_LIST_ID).first().conflicts).isEmpty()
    }
    @Test fun `stale repeat offer preserves another devices decision and its original list scope`() = runTest {
        items.add(ParsedItem("milk"))
        val pinned = items.items().single().version!!
        val offer = RepeatOffer("milk", "milk", 21, pinned)
        history.savePreference(ItemPreference("milk", repeatEveryDays = 7))
        smart.setRepeat(offer)
        assertThat(history.snapshot().preferences.single().repeatEveryDays).isEqualTo(7)
        assertThat(LocalReplica(db).observeStatus(LOCAL_LIST_ID).first().conflicts).hasSize(1)
        ListRepository(db).create("Other list")
        assertThat(runCatching { smart.setRepeat(offer) }.isFailure).isTrue()
        assertThat(history.snapshot().preferences).isEmpty()
    }
    @Test fun `accepted quantity hint uses the displayed amount and preserves a newer explicit quantity`() = runTest {
        val milk = items.add(ParsedItem("milk"))
        val pinned = items.items().single().version!!
        items.setQuantity(milk, Quantity(5.0, MeasureUnit.LITRE))
        smart.acceptQuantityHint(milk, Quantity(2.0, MeasureUnit.LITRE), pinned)
        assertThat(items.items().single().quantity!!.amount).isEqualTo(5.0)
        assertThat(LocalReplica(db).observeStatus(LOCAL_LIST_ID).first().conflicts.single().incoming["quantity"]).isEqualTo("2.0")
    }
    @Test fun `stale similarity approval keeps its literal alias request without adding to another identity`() = runTest {
        history.importPast(listOf(ParsedItem("milk")), now - RepeatPlanner.DAY_MILLIS)
        val usual = smart.similar("whole milk", history.snapshot(), emptyList())!!
        val pinned = history.observeShopping().first().listVersion!!
        assertThat(pinned.aliasRevision).isNull()
        history.saveAlias(ItemAlias("milk", "soap"))
        val incoming = Item(ItemId("incoming"), "whole milk", "whole milk", "whole milk", createdAt = now, updatedAt = now)
        assertThat(smart.addMerged(incoming, usual, pinned)).isFalse()
        assertThat(items.items()).isEmpty()
        assertThat(history.snapshot().aliases).containsExactly(ItemAlias("milk", "soap"))
        val conflict = LocalReplica(db).observeStatus(LOCAL_LIST_ID).first().conflicts.single()
        assertThat(conflict.kind).isEqualTo("alias")
        assertThat(conflict.incoming).containsEntry("whole milk", "milk")
        assertThat(conflict.current).containsEntry("milk", "soap")
    }

}

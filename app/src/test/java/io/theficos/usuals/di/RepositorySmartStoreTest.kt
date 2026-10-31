package io.theficos.usuals.di

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.data.local.*
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.InputLanguages
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.smart.*
import io.theficos.usuals.ui.list.ItemEdit
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
    private lateinit var db: UsualsDatabase
    private lateinit var items: ItemRepository
    private lateinit var history: HistoryRepository
    private lateinit var smart: RepositorySmartStore
    private var now = 1_700_000_000_000L
    private var ids = 0
    private fun id() = "smart-${++ids}"
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsualsDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        items = ItemRepository(db, { now }, ::id)
        history = HistoryRepository(db, items, { now }, ::id)
        smart = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
    }
    @After fun tearDown() = db.close()

    @Test fun `review adds beyond six without quantities and undo removes only its unchanged row`() = runTest {
        history.importPast((1..13).map { ParsedItem("item $it", Quantity.pieces(2)) }, now - 7 * RepeatPlanner.DAY_MILLIS)
        val scope = items.currentListId()
        val candidate = smart.review(now, history.snapshot(), items.items()).last()
        val added = smart.addReviewed(candidate.key, scope, now) as io.theficos.usuals.ui.list.ReviewAddResult.Added
        assertThat(items.items().single().name).isEqualTo(candidate.name)
        assertThat(items.items().single().quantity).isNull()
        assertThat(items.items().single().fromSuggestion).isTrue()
        assertThat(smart.addReviewed(candidate.key, scope, now)).isEqualTo(io.theficos.usuals.ui.list.ReviewAddResult.AlreadyPresent)
        assertThat(smart.undoReviewed(added.version)).isTrue()
        assertThat(items.items()).isEmpty()
        assertThat(smart.undoReviewed(added.version)).isFalse()
        assertThat(history.snapshot().purchases).hasSize(13)
    }

    @Test fun `review rechecks checked aliases muted rules and stale scopes at write time`() = runTest {
        history.importPast(listOf(ParsedItem("milk"), ParsedItem("bread"), ParsedItem("eggs")), now - 7 * RepeatPlanner.DAY_MILLIS)
        val scope = items.currentListId()
        history.saveAlias(ItemAlias("whole milk", "milk"))
        items.setChecked(items.add(ParsedItem("whole milk", Quantity.pieces(3))), true)
        assertThat(smart.addReviewed("milk", scope, now)).isEqualTo(io.theficos.usuals.ui.list.ReviewAddResult.AlreadyPresent)
        assertThat(items.items().single().quantity).isEqualTo(Quantity.pieces(3))
        smart.muteSuggestion("bread", scope)
        assertThat(smart.addReviewed("bread", scope, now)).isEqualTo(io.theficos.usuals.ui.list.ReviewAddResult.Unavailable)
        history.savePreference(ItemPreference("eggs", repeatEveryDays = 7))
        assertThat(smart.addReviewed("eggs", scope, now)).isEqualTo(io.theficos.usuals.ui.list.ReviewAddResult.Unavailable)
        assertThat(smart.muteReviewed("eggs", scope, now)).isFalse()
        assertThat(history.snapshot().preferences.single { it.key == "eggs" }.muted).isFalse()
        ListRepository(db).create("Other")
        assertThat(smart.addReviewed("milk", scope, now)).isEqualTo(io.theficos.usuals.ui.list.ReviewAddResult.Unavailable)
        assertThat(items.items()).isEmpty()
    }

    @Test fun `review undo preserves a subsequently edited checked or switched row`() = runTest {
        ListRepository(db).create("Source")
        history.importPast(listOf(ParsedItem("milk")), now - 7 * RepeatPlanner.DAY_MILLIS)
        val scope = items.currentListId()
        val added = smart.addReviewed("milk", scope, now) as io.theficos.usuals.ui.list.ReviewAddResult.Added
        val id = ItemId(added.version.id)
        items.setQuantity(id, Quantity.pieces(2))
        assertThat(smart.undoReviewed(added.version)).isFalse()
        val edited = items.items().single().version!!
        items.setChecked(id, true)
        assertThat(smart.undoReviewed(edited)).isFalse()
        val checked = items.items().single().version!!
        ListRepository(db).create("Other")
        assertThat(smart.undoReviewed(checked)).isFalse()
        ListRepository(db).select(scope)
        assertThat(items.items().single().checked).isTrue()
        assertThat(items.items().single().quantity).isEqualTo(Quantity.pieces(2))
    }

    @Test fun `archive three restores private favorites without sharing or erasing local choices`() = runTest {
        val tools = io.theficos.usuals.ui.shopping.ShoppingToolsStore()
        val favorite = io.theficos.usuals.ui.shopping.SavedShoppingItem("favorite", "milk", 2.0, "LITRE")
        tools.pin(favorite)
        items.add(ParsedItem("bread"))
        val adapter = RepositoryHistoryStore(history, io.theficos.usuals.parse.LocalUtteranceParser(), smart, tools)
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

    @Test fun `rebuying a long trip adds each new key once, after the open rows, keeping gtin and store`() = runTest {
        val scope = items.currentListId()
        items.add(ParsedItem("milk"))
        val code = Gtin.parse("4006381333931")!!
        val rows = (1..150).map { io.theficos.usuals.ui.shopping.SavedShoppingItem("old-$it", "item $it") } +
            listOf(io.theficos.usuals.ui.shopping.SavedShoppingItem("old-milk", "milk", 2.0, "LITRE"),
                io.theficos.usuals.ui.shopping.SavedShoppingItem("again", "item 1"),
                io.theficos.usuals.ui.shopping.SavedShoppingItem("scanned", "soap", gtin = code.value, store = "Bonpreu"))
        assertThat(smart.addAgain(rows, scope)).isEqualTo(151)
        val list = items.items()
        assertThat(list.map { it.name }).isEqualTo(listOf("milk") + (1..150).map { "item $it" } + "soap")
        assertThat(list.map { it.position }).isInStrictOrder()
        assertThat(list.first().quantity).isNull()
        assertThat(list.last().gtin).isEqualTo(code)
        assertThat(list.last().store).isEqualTo("Bonpreu")
    }

    @Test fun `rebuy is idempotent keeps quantities and refuses a stale list scope`() = runTest {
        val scope = items.currentListId()
        val milk = items.add(ParsedItem("milk", Quantity(1.0, MeasureUnit.LITRE)))
        val source = listOf(io.theficos.usuals.ui.shopping.SavedShoppingItem("old-milk", "milk", 2.0, "LITRE"),
            io.theficos.usuals.ui.shopping.SavedShoppingItem("old-bread", "bread"))
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

    @Test fun `an edit writes, keeps and clears the note`() = runTest {
        val milk = items.add(ParsedItem("milk", note = "for the cake"))
        assertThat(smart.edit(ItemEdit(milk, "milk", null, null, null, note = "lactose free"))).isTrue()
        assertThat(items.find(milk)!!.note).isEqualTo("lactose free")
        assertThat(smart.edit(ItemEdit(milk, "oat milk", null, null, null, note = "lactose free"))).isTrue()
        assertThat(items.find(milk)!!.note).isEqualTo("lactose free")
        assertThat(smart.edit(ItemEdit(milk, "oat milk", null, null, null))).isTrue()
        assertThat(items.find(milk)!!.note).isNull()
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

    @Test fun `review respects existing snooze and mute and marks only accepted rows`() = runTest {
        history.importPast(listOf(ParsedItem("milk"), ParsedItem("bread"), ParsedItem("eggs")), now - 14 * RepeatPlanner.DAY_MILLIS)
        history.importPast(listOf(ParsedItem("milk"), ParsedItem("bread"), ParsedItem("eggs")), now - 7 * RepeatPlanner.DAY_MILLIS)
        history.savePreference(ItemPreference("milk", snoozedUntil = now))
        smart.muteSuggestion("bread", items.currentListId())
        smart.addReviewed("eggs", items.currentListId(), now)
        assertThat(items.items().single().name).isEqualTo("eggs")
        assertThat(items.items().single().fromSuggestion).isTrue()
        assertThat(items.items().single().quantity).isNull()
        assertThat(smart.review(now, history.snapshot(), items.items())).isEmpty()
        items.setChecked(items.items().single().id, true)
        now += 1000
        smart.finish(null)
        assertThat(smart.review(now, history.snapshot(), items.items()).map { it.name }).contains("milk")
        assertThat(smart.review(now, history.snapshot(), items.items()).map { it.name }).doesNotContain("bread")
    }

    @Test fun `typing can mute a memory and manual favourite adds still work`() = runTest {
        history.importPast((1..9).map { ParsedItem("item $it") }, now - RepeatPlanner.DAY_MILLIS)
        val scope = items.currentListId()
        assertThat(smart.autocomplete("item 9", history.snapshot(), items.items()).single().key).isEqualTo("item 9")
        smart.muteSuggestion("item 9", scope)
        val facts = history.snapshot()
        assertThat(facts.preferences.single().muted).isTrue()
        assertThat(smart.autocomplete("item 9", facts, items.items())).isEmpty()
        assertThat(facts.purchases).hasSize(9)
        assertThat(smart.addAgain(listOf(io.theficos.usuals.ui.shopping.SavedShoppingItem("saved", "item 9")), scope)).isEqualTo(1)
        assertThat(items.items().single().name).isEqualTo("item 9")
        assertThat(items.items().single().fromSuggestion).isFalse()
        assertThat(history.snapshot().preferences.single().muted).isTrue()
    }

    @Test fun `typing mute resolves barcode aliases preserves other choices and survives adapter recreation`() = runTest {
        val gtin = requireNotNull(Gtin.parse("4006381333931"))
        val scanned = items.add(ParsedItem(gtin.value), gtin = gtin)
        items.setChecked(scanned, true)
        history.finish()
        history.saveAlias(ItemAlias("gtin:${gtin.value}", "milk"))
        history.savePreference(ItemPreference("milk", category = "DAIRY", repeatEveryDays = 21, snoozedUntil = now))
        smart.muteSuggestion("gtin:${gtin.value}", items.currentListId())
        assertThat(history.snapshot().preferences.single()).isEqualTo(
            ItemPreference("milk", category = "DAIRY", muted = true, repeatEveryDays = 21, snoozedUntil = now))
        val reloaded = RepositorySmartStore(items, history, CategoryClassifier(InputLanguages.available), HistoryRanker(), RepeatPlanner())
        assertThat(reloaded.autocomplete(gtin.value, history.snapshot(), emptyList())).isEmpty()
        assertThat(reloaded.review(now, history.snapshot(), emptyList())).isEmpty()
        items.add(ParsedItem("milk"))
        assertThat(items.items().single().name).isEqualTo("milk")
        assertThat(history.snapshot().purchases).hasSize(1)
    }

    @Test fun `a stale typing mute cannot affect another list and unknown keys are ignored`() = runTest {
        history.importPast(listOf(ParsedItem("milk")), now - RepeatPlanner.DAY_MILLIS)
        val previousScope = items.currentListId()
        smart.muteSuggestion("unseen", previousScope)
        assertThat(history.snapshot().preferences).isEmpty()
        smart.muteSuggestion("milk", previousScope)
        ListRepository(db).create("Other list")
        history.importPast(listOf(ParsedItem("milk")), now - RepeatPlanner.DAY_MILLIS)
        assertThat(runCatching { smart.muteSuggestion("milk", previousScope) }.isFailure).isTrue()
        assertThat(history.snapshot().preferences).isEmpty()
        assertThat(smart.autocomplete("mi", history.snapshot(), emptyList()).single().name).isEqualTo("milk")
    }

    @Test fun `muting and snoozing aliased memories never rewrite shared preference rules or queue intents`() = runTest {
        history.importPast(listOf(ParsedItem("milk")), now - RepeatPlanner.DAY_MILLIS)
        history.savePreference(ItemPreference("whole milk", category = "DAIRY", repeatEveryDays = 21))
        history.saveAlias(ItemAlias("whole milk", "milk"))
        val scope = items.currentListId()
        val replica = LocalReplica(db)
        val shared = replica.projection(scope)
        val outbox = db.syncDao().pending(scope)

        smart.muteSuggestion("milk", scope)
        assertThat(history.snapshot().preferences.single()).isEqualTo(
            ItemPreference("whole milk", category = "DAIRY", muted = true, repeatEveryDays = 21))
        assertThat(replica.projection(scope)).isEqualTo(shared)
        assertThat(db.syncDao().pending(scope)).isEqualTo(outbox)

        history.savePreference(ItemPreference("whole milk", category = "DAIRY", repeatEveryDays = 21))
        history.savePreference(ItemPreference("whole milk", category = "DAIRY", repeatEveryDays = 21, snoozedUntil = now))
        assertThat(history.snapshot().preferences.single().snoozedUntil).isEqualTo(now)
        assertThat(replica.projection(scope)).isEqualTo(shared)
        assertThat(db.syncDao().pending(scope)).isEqualTo(outbox)

        history.savePreference(ItemPreference("whole milk", category = "DAIRY", repeatEveryDays = 21))
        smart.muteSuggestion("milk", scope)
        assertThat(history.snapshot().preferences.single().muted).isTrue()
        assertThat(replica.projection(scope)).isEqualTo(shared)
        assertThat(db.syncDao().pending(scope)).isEqualTo(outbox)
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

    @Test fun `confirmed capture match records an alias and preserves explicit quantity atomically`() = runTest {
        history.importPast(listOf(ParsedItem("milk")), now - RepeatPlanner.DAY_MILLIS)
        val milk = items.add(ParsedItem("milk", Quantity(1.0, MeasureUnit.LITRE)))
        val usual = smart.nameMatches("milkk", history.snapshot(), items.items()).single().item
        val source = items.add(ParsedItem("milkk", Quantity(500.0, MeasureUnit.MILLILITRE), "for breakfast"))
        val captured = items.items().first { it.id == source }
        assertThat(items.items()).hasSize(2)
        assertThat(smart.matchCaptures(listOf(io.theficos.usuals.ui.list.CaptureMatchChoice(captured, usual)), true)).isNotNull()
        assertThat(history.snapshot().aliases).containsExactly(ItemAlias("milkk", "milk"))
        assertThat(items.items()).hasSize(1)
        assertThat(items.find(milk)!!.quantity).isEqualTo(Quantity(1.5, MeasureUnit.LITRE))
        assertThat(items.find(milk)!!.note).isEqualTo("for breakfast")
        assertThat(smart.nameMatches("milkk", history.snapshot(), items.items())).isEmpty()
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
    @Test fun `stale capture match keeps the original and never changes another identity`() = runTest {
        history.importPast(listOf(ParsedItem("milk")), now - RepeatPlanner.DAY_MILLIS)
        val usual = smart.nameMatches("milkk", history.snapshot(), emptyList()).single().item
        val id = items.add(ParsedItem("milkk", Quantity.pieces(2)))
        val incoming = items.items().single()
        history.saveAlias(ItemAlias("milk", "soap"))
        assertThat(smart.matchCaptures(listOf(io.theficos.usuals.ui.list.CaptureMatchChoice(incoming, usual)), true)).isNull()
        assertThat(items.find(id)!!.name).isEqualTo("milkk")
        assertThat(items.find(id)!!.quantity).isEqualTo(Quantity.pieces(2))
        assertThat(history.snapshot().aliases).containsExactly(ItemAlias("milk", "soap"))
    }

    @Test fun `an incompatible choice rolls back every match in the batch`() = runTest {
        items.add(ParsedItem("milk", Quantity(1.0, MeasureUnit.LITRE)))
        items.add(ParsedItem("bread", Quantity.pieces(1)))
        val captures = RepositoryItemStore(items).capture(listOf(
            Item(ItemId("a"), "breaad", "breaad", "breaad", Quantity.pieces(2), createdAt = now, updatedAt = now),
            Item(ItemId("b"), "milkk", "milkk", "milkk", Quantity.pieces(3), createdAt = now, updatedAt = now),
        ))
        val choices = captures.map { source -> io.theficos.usuals.ui.list.CaptureMatchChoice(source,
            ItemMemory(if (source.name == "breaad") "bread" else "milk", if (source.name == "breaad") "bread" else "milk", 0, 0)) }
        assertThat(smart.matchCaptures(choices, true)).isNull()
        assertThat(items.items().map { it.name }).containsExactly("milk", "bread", "breaad", "milkk")
        assertThat(items.items().first { it.name == "bread" }.quantity).isEqualTo(Quantity.pieces(1))
        assertThat(history.snapshot().aliases).isEmpty()
    }

    @Test fun `a batch of confirmed names keeps raw text notes and quantities and remembers both aliases`() = runTest {
        val captures = RepositoryItemStore(items).capture(listOf(
            Item(ItemId("a"), "2 kg bananna, 1 l yogrut", "bananna", "bananna", Quantity(2.0, MeasureUnit.KILOGRAM), "ripe", createdAt = now, updatedAt = now),
            Item(ItemId("b"), "2 kg bananna, 1 l yogrut", "yogrut", "yogrut", Quantity(1.0, MeasureUnit.LITRE), createdAt = now, updatedAt = now),
        ))
        val choices = captures.zip(listOf("banana", "yogurt")).map { (source, name) ->
            io.theficos.usuals.ui.list.CaptureMatchChoice(source, ItemMemory(name, name, 0, 0)) }
        assertThat(smart.matchCaptures(choices, true)).isNotNull()
        val saved = items.items()
        assertThat(saved.map { it.name }).containsExactly("banana", "yogurt")
        assertThat(saved.first().rawText).isEqualTo("2 kg bananna, 1 l yogrut")
        assertThat(saved.first().note).isEqualTo("ripe")
        assertThat(saved.map { it.quantity }).containsExactly(Quantity(2.0, MeasureUnit.KILOGRAM), Quantity(1.0, MeasureUnit.LITRE))
        assertThat(history.snapshot().aliases).containsExactly(ItemAlias("bananna", "banana"), ItemAlias("yogrut", "yogurt"))
    }

    @Test fun `automatic correction and undo preserve the capture and do not create aliases`() = runTest {
        items.add(ParsedItem("bananna", Quantity.pieces(3), "ripe"), rawText = "3 bananna")
        val source = items.items().single()
        val matched = smart.matchCaptures(listOf(io.theficos.usuals.ui.list.CaptureMatchChoice(source, ItemMemory("banana", "banana", 0, 0))), false)!!.single()
        assertThat(history.snapshot().aliases).isEmpty()
        assertThat(matched.quantity).isEqualTo(source.quantity)
        assertThat(matched.rawText).isEqualTo(source.rawText)
        assertThat(matched.note).isEqualTo("ripe")
        assertThat(smart.matchCaptures(listOf(io.theficos.usuals.ui.list.CaptureMatchChoice(matched, ItemMemory("bananna", "bananna", 0, 0))), false)).isNotNull()
        assertThat(items.items().single().name).isEqualTo("bananna")
    }

    @Test fun `an edit after capture cannot be overwritten by a stale review`() = runTest {
        items.add(ParsedItem("bananna", Quantity.pieces(3)))
        val source = items.items().single()
        items.setQuantity(source.id, Quantity.pieces(5))
        assertThat(smart.matchCaptures(listOf(io.theficos.usuals.ui.list.CaptureMatchChoice(source, ItemMemory("banana", "banana", 0, 0))), true)).isNull()
        assertThat(items.items().single().quantity).isEqualTo(Quantity.pieces(5))
        assertThat(history.snapshot().aliases).isEmpty()
    }
    @Test fun `packs preserve details skip checked aliases and retries and leave existing preferences alone`() = runTest {
        val scope = items.currentListId()
        history.saveAlias(ItemAlias("whole milk", "milk"))
        val milk = items.add(ParsedItem("milk", Quantity.pieces(3)))
        items.setChecked(milk, true)
        val rows = listOf(
            io.theficos.usuals.ui.shopping.SavedShoppingItem("milk", "whole milk", 8.0, "LITRE", note = "Skip", category = "PRODUCE"),
            io.theficos.usuals.ui.shopping.SavedShoppingItem("bread", "Bread", 2.0, "PACK", store = "Market", note = "For visitors", category = "PRODUCE"))
        assertThat(smart.addPack(rows, scope)).isEqualTo(1)
        assertThat(smart.addPack(rows, scope)).isEqualTo(0)
        assertThat(items.items()).hasSize(2)
        assertThat(items.items().first { it.id == milk }.quantity).isEqualTo(Quantity.pieces(3))
        val bread = items.items().first { it.name == "Bread" }
        assertThat(bread.note).isEqualTo("For visitors")
        assertThat(bread.store).isEqualTo("Market")
        assertThat(bread.quantity).isEqualTo(Quantity(2.0, MeasureUnit.PACK))
        assertThat(history.snapshot().preferences.first { it.key == "bread" }.category).isEqualTo("PRODUCE")
        assertThat(history.snapshot().preferences.none { it.key == "milk" && it.category != null }).isTrue()
    }

}

package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.MeasureUnit
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.parse.LocalUtteranceParser
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The list, driven the way a person drives it: hold the band, say something,
 * let go. Everything here runs on the JVM in milliseconds with a fake store and
 * a recogniser the test speaks into by hand.
 */
class ListViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private var minted = 0

    /** Every barcode the screen handed to product enrichment, in order. */

    private fun viewModel(
        store: FakeItemStore,
        speech: FakeSpeechSource = FakeSpeechSource(),
    ) = ListViewModel(
        store = store,
        parser = LocalUtteranceParser(),
        speech = speech,
        newItemId = { ItemId("item-${++minted}") },
        clock = { FIXED_CLOCK },
        compute = main.dispatcher,
    )

    @Test
    fun choosing_the_keyboard_does_not_start_or_disable_voice() = runTest(main.dispatcher) {
        val speech = FakeSpeechSource(available = true)
        val vm = viewModel(FakeItemStore(), speech)
        vm.onType()
        assertThat(vm.state.value.capture).isEqualTo(CaptureState.Typing())
        assertThat(speech.listenCount).isEqualTo(0)
        vm.onDraftChange("milk")
        vm.onDraftSubmit()
        assertThat(vm.state.value.pending.single().name).isEqualTo("Milk")
        vm.onDraftDismiss()
        vm.onCaptureStart()
        assertThat(speech.listenCount).isEqualTo(1)
        vm.onCaptureEnd()
    }

    // The list -----------------------------------------------------------

    @Test
    fun clearing_the_list_removes_every_item_checked_or_not() = runTest(main.dispatcher) {
        val store = FakeItemStore(listOf(item("a", "leche"), item("b", "pan", checked = true, position = 2.0)))
        val vm = viewModel(store)
        vm.onClearItems()
        assertThat(store.removed).containsExactly(ItemId("a"), ItemId("b"))
        assertThat(vm.state.value.isEmpty).isTrue()
    }

    @Test
    fun the_list_menu_acts_on_the_list_it_was_opened_for_even_after_the_selection_moved() = runTest(main.dispatcher) {
        val renamed = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<String>()
        val archivedLists = mutableListOf<Pair<String, Boolean>>()
        val directory = object : ListDirectoryStore {
            override fun observe() = flowOf(ListDirectory(selected = "other", lists = listOf(NamedList("mine", "Mine", shared = true), NamedList("other", "Other", shared = false))))
            override suspend fun select(id: String) = Unit
            override suspend fun create(name: String) = Unit
            override suspend fun rename(id: String, name: String) { renamed += id to name }
            override suspend fun remove(id: String) { removed += id }
            override suspend fun archive(id: String, archived: Boolean) { archivedLists += id to archived }
        }
        val vm = ListViewModel(store = FakeItemStore(), parser = LocalUtteranceParser(), speech = FakeSpeechSource(),
            newItemId = { ItemId("item-${++minted}") }, clock = { FIXED_CLOCK }, directoryStore = directory, compute = main.dispatcher)
        vm.onRenameList("mine", "Groceries")
        vm.onRemoveList("mine")
        vm.onArchiveList("mine", true)
        assertThat(renamed).containsExactly("mine" to "Groceries")
        assertThat(removed).containsExactly("mine")
        assertThat(archivedLists).containsExactly("mine" to true)
    }

    @Test
    fun pending_and_checked_are_split_and_ordered_by_position() = runTest(main.dispatcher) {
        val store = FakeItemStore(
            listOf(
                item("c", "pan de payés", position = 3.0),
                item("a", "leche entera", quantity = Quantity.pieces(2), position = 1.0),
                item("b", "tomates", checked = true, position = 2.0),
            ),
        )

        val state = viewModel(store).state.value

        assertThat(state.pending.map { it.name })
            .containsExactly("Leche entera", "Pan de payés").inOrder()
        assertThat(state.checked.map { it.name }).containsExactly("Tomates")
        assertThat(state.isEmpty).isFalse()
    }

    @Test
    fun counts_read_as_multiples_and_measures_read_as_themselves() = runTest(main.dispatcher) {
        val store = FakeItemStore(
            listOf(
                item("a", "leche", quantity = Quantity.pieces(2), position = 1.0),
                item("b", "aceite de oliva", quantity = Quantity(0.5, MeasureUnit.LITRE), position = 2.0),
                item("c", "pan", position = 3.0),
            ),
        )

        val rows = viewModel(store).state.value.pending

        assertThat(rows[0].quantity).isEqualTo(Quantity.pieces(2))
        assertThat(rows[1].quantity).isEqualTo(Quantity(0.5, MeasureUnit.LITRE))
        assertThat(rows[2].quantity).isNull()
    }

    @Test
    fun a_row_is_capitalised_for_reading_and_the_stored_name_is_left_alone() =
        runTest(main.dispatcher) {
            val store = FakeItemStore(listOf(item("a", "leche entera")))

            assertThat(viewModel(store).state.value.pending.single().name).isEqualTo("Leche entera")
            assertThat(store.current.single().name).isEqualTo("leche entera")
        }

    @Test
    fun the_header_counts_the_whole_list() = runTest(main.dispatcher) {
        val store = FakeItemStore(
            listOf(
                item("a", "leche", position = 1.0),
                item("b", "pan", position = 2.0),
                item("c", "huevos", position = 3.0, checked = true),
            ),
        )

        val header = viewModel(store).state.value.header

        assertThat(header.name).isNull()
        // Everything on the list, bought or not: it is the size of the shop.
        assertThat(header.itemCount).isEqualTo(3)
    }

    // Capture ------------------------------------------------------------

    @Test
    fun holding_the_band_grows_a_row_and_releasing_it_adds_the_item() = runTest(main.dispatcher) {
        val store = FakeItemStore(listOf(item("a", "pan", position = 4.0)))
        val speech = FakeSpeechSource()
        val vm = viewModel(store, speech)

        vm.onCaptureStart()
        assertThat(vm.state.value.capture).isEqualTo(CaptureState.Listening(""))

        speech.hear("dos litros")
        assertThat(vm.state.value.capture).isEqualTo(CaptureState.Listening("dos litros"))

        vm.onCaptureEnd()
        assertThat(speech.stopCount).isEqualTo(1)
        speech.finish("dos litros de leche")

        assertThat(vm.state.value.capture).isEqualTo(CaptureState.Idle)
        val added = store.batches.single().single()
        assertThat(added.name).isEqualTo("leche")
        assertThat(added.rawText).isEqualTo("dos litros de leche")
        assertThat(added.quantity).isEqualTo(Quantity(2.0, MeasureUnit.LITRE))
        assertThat(added.createdAt).isEqualTo(FIXED_CLOCK)
        // New items land at the end of the list, where they were said.
        assertThat(added.position).isEqualTo(5.0)
        assertThat(vm.state.value.pending.map { it.name })
            .containsExactly("Pan", "Leche").inOrder()
    }

    @Test
    fun one_utterance_is_one_write_however_many_items_it_names() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        val speech = FakeSpeechSource()
        val vm = viewModel(store, speech)

        vm.onCaptureStart()
        vm.onCaptureEnd()
        speech.finish("leche, huevos y pan")

        assertThat(store.batches).hasSize(1)
        val batch = store.batches.single()
        assertThat(batch.map { it.name }).containsExactly("leche", "huevos", "pan").inOrder()
        assertThat(batch.map { it.position }).containsExactly(1.0, 2.0, 3.0).inOrder()
        // Said in one breath, so one timestamp rather than a millisecond of drift.
        assertThat(batch.map { it.createdAt }.distinct()).containsExactly(FIXED_CLOCK)
        assertThat(batch.map { it.id.value }).containsExactly("item-1", "item-2", "item-3").inOrder()
    }

    @Test
    fun saying_nothing_adds_nothing() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        val speech = FakeSpeechSource()
        val vm = viewModel(store, speech)

        vm.onCaptureStart()
        vm.onCaptureEnd()
        speech.finish("")

        assertThat(store.batches).isEmpty()
        assertThat(vm.state.value.capture).isEqualTo(CaptureState.Idle)
    }

    @Test
    fun a_phone_without_dictation_gets_a_text_field_in_the_same_place() =
        runTest(main.dispatcher) {
            val store = FakeItemStore()
            val speech = FakeSpeechSource(available = false)
            val vm = viewModel(store, speech)

            vm.onCaptureStart()

            assertThat(speech.listenCount).isEqualTo(0)
            assertThat(vm.state.value.capture)
                .isEqualTo(CaptureState.Typing("", becauseSpeechIsUnavailable = true))

            vm.onDraftChange("medio kilo de tomates")
            vm.onDraftSubmit()

            val added = store.current.single()
            assertThat(added.name).isEqualTo("tomates")
            assertThat(added.quantity).isEqualTo(Quantity(0.5, MeasureUnit.KILOGRAM))
            // The field stays open and empty, ready for the next item.
            assertThat(vm.state.value.capture)
                .isEqualTo(CaptureState.Typing("", becauseSpeechIsUnavailable = true))
        }

    @Test
    fun a_refused_microphone_is_not_asked_about_again() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        val speech = FakeSpeechSource(available = true)
        val vm = viewModel(store, speech)

        vm.onSpeechUnavailable()
        vm.onDraftDismiss()
        vm.onCaptureStart()

        assertThat(speech.listenCount).isEqualTo(0)
        assertThat(vm.state.value.capture)
            .isEqualTo(CaptureState.Typing("", becauseSpeechIsUnavailable = true))
    }

    @Test
    fun a_recogniser_that_dies_mid_gesture_hands_over_what_it_heard() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        val speech = FakeSpeechSource()
        val vm = viewModel(store, speech)

        vm.onCaptureStart()
        speech.hear("dos litros de leche")
        speech.fail(IllegalStateException("recogniser went away"))

        assertThat(vm.state.value.capture)
            .isEqualTo(CaptureState.Typing("dos litros de leche", becauseSpeechIsUnavailable = true))
        assertThat(store.batches).isEmpty()
    }

    @Test
    fun text_shared_into_the_app_opens_an_import_without_changing_the_list() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        val vm = viewModel(store)

        vm.onSharedText("leche, huevos y pan")

        assertThat(store.current).isEmpty()
        assertThat(vm.state.value.recipe!!.markdown).isEqualTo("leche, huevos y pan")
        assertThat(vm.state.value.recipe!!.source).isEqualTo(io.theficos.usuals.ui.recipe.RecipeSource.MARKDOWN)
    }

    @Test
    fun a_scanned_barcode_adds_nothing_without_a_scan_store_to_name_it() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        val vm = viewModel(store)

        vm.onScanned(requireNotNull(Gtin.parse("8480000123459")))

        assertThat(store.current).isEmpty()
        assertThat(vm.state.value.scanNames).isEmpty()
    }

    // Checking off, and taking off ----------------------------------------

    @Test
    fun checking_an_item_moves_it_and_writes_through() = runTest(main.dispatcher) {
        val store = FakeItemStore(
            listOf(item("a", "leche", position = 1.0), item("b", "pan", position = 2.0)),
        )
        val vm = viewModel(store)

        vm.onCheckedChange(ItemId("a"), checked = true)

        assertThat(store.current.first { it.id == ItemId("a") }.checked).isTrue()
        assertThat(vm.state.value.pending.map { it.name }).containsExactly("Pan")
        assertThat(vm.state.value.checked.map { it.name }).containsExactly("Leche")
    }

    @Test
    fun the_cart_section_starts_closed_and_opens_on_demand() = runTest(main.dispatcher) {
        val store = FakeItemStore(listOf(item("a", "leche", checked = true)))
        val vm = viewModel(store)

        assertThat(vm.state.value.checkedExpanded).isFalse()
        vm.onToggleCheckedSection()
        assertThat(vm.state.value.checkedExpanded).isTrue()
        vm.onToggleCheckedSection()
        assertThat(vm.state.value.checkedExpanded).isFalse()
    }

    @Test
    fun opening_an_editor_does_not_remove_the_row() = runTest(main.dispatcher) {
        val store = FakeItemStore(listOf(item("a", "leche")))
        val vm = viewModel(store)

        vm.onEditItem(ItemId("a"))
        assertThat(vm.state.value.editing?.id).isEqualTo(ItemId("a"))
        assertThat(store.removed).isEmpty()

        vm.onDismissEdit()
        assertThat(vm.state.value.editing).isNull()
        assertThat(store.removed).isEmpty()

        vm.onEditItem(ItemId("a"))
        vm.onConfirmRemove(ItemId("a"))
        assertThat(store.removed).containsExactly(ItemId("a"))
        assertThat(store.current).isEmpty()
        assertThat(vm.state.value.editing).isNull()
    }

    @Test
    fun starting_a_capture_closes_the_row_editor() =
        runTest(main.dispatcher) {
            val store = FakeItemStore(listOf(item("a", "leche")))
            val speech = FakeSpeechSource()
            val vm = viewModel(store, speech)

            vm.onEditItem(ItemId("a"))
            vm.onCaptureStart()

            assertThat(vm.state.value.editing).isNull()
        }

    @Test
    fun the_draft_reaches_the_state_while_rows_are_still_being_worked_out() = runTest(main.dispatcher) {
        val memory = io.theficos.usuals.smart.ItemMemory("milk", "milk", 3, 1000)
        val smart = object : SmartStore by NoSmartStore {
            override fun autocomplete(query: String, history: io.theficos.usuals.model.HistorySnapshot, active: List<io.theficos.usuals.model.Item>) =
                listOf(memory).filter { query.isNotBlank() }
        }
        val compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val vm = ListViewModel(FakeItemStore(listOf(item("a", "leche"))), LocalUtteranceParser(), FakeSpeechSource(),
            smartStore = smart, compute = compute)
        vm.onType()
        vm.onDraftChange("mi")
        // The rows and the completions wait for the background dispatcher; the draft does not.
        assertThat(vm.state.value.capture).isEqualTo(CaptureState.Typing("mi"))
        assertThat(vm.state.value.pending).isEmpty()
        assertThat(vm.state.value.autocomplete).isEmpty()
        testScheduler.advanceUntilIdle()
        assertThat(vm.state.value.pending.single().name).isEqualTo("Leche")
        assertThat(vm.state.value.autocomplete).containsExactly(memory)
        vm.onDraftChange("mil")
        assertThat(vm.state.value.capture).isEqualTo(CaptureState.Typing("mil"))
    }
}

/** An arbitrary fixed instant. Nothing in these tests reads a wall clock. */
private const val FIXED_CLOCK = 1_700_000_000_000L

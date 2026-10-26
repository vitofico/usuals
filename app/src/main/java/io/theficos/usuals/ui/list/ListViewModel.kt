package io.theficos.usuals.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.usuals.smart.BarcodeMemory
import io.theficos.usuals.smart.Category
import io.theficos.usuals.smart.ItemMemory
import io.theficos.usuals.smart.RepeatOffer
import io.theficos.usuals.ui.recipe.*
import io.theficos.usuals.ui.scan.ScanStore
import io.theficos.usuals.ui.scan.ScanNameOffer
import io.theficos.usuals.ui.scan.ScanNameProblem
import io.theficos.usuals.ui.scan.ScanResult
import io.theficos.usuals.recipe.IngredientReason
import io.theficos.usuals.recipe.RecipeProblem
import io.theficos.usuals.model.FinishedTrip
import io.theficos.usuals.model.HistorySnapshot
import io.theficos.usuals.ui.history.HistoryStore
import io.theficos.usuals.ui.history.shopping
import io.theficos.usuals.ui.history.NoHistoryStore
import io.theficos.usuals.ui.history.PromptMemory
import io.theficos.usuals.ui.history.InMemoryPromptMemory
import io.theficos.usuals.model.Gtin
import io.theficos.usuals.model.Item
import io.theficos.usuals.model.ItemVersion
import io.theficos.usuals.model.ItemTransferResult
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.ShoppingSnapshot
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.parse.Normalizer
import io.theficos.usuals.parse.UtteranceParser
import io.theficos.usuals.parse.toItem
import io.theficos.usuals.ui.shopping.favoritesOnList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The list screen's state, and the only place capture turns into rows.
 *
 * Two properties are load-bearing and everything else follows from them.
 *
 * Nothing here blocks on IO. Reads arrive as flows off the store and writes are
 * launched into [viewModelScope], so pressing the band and releasing it is a
 * state update and a coroutine, never a wait. The item appears because the
 * store's own query emits it, which is the same path a housemate's item takes.
 *
 * Nothing here reads the wall clock or formats a sentence. The clock and the id
 * generator are parameters, so a test can build a list at a fixed instant with
 * predictable ids, and every user-facing string lives in `strings.xml` and is
 * resolved by the composable.
 */
class ListViewModel(
    private val store: ItemStore,
    private val parser: UtteranceParser,
    private val speech: SpeechSource,
    private val newItemId: () -> ItemId = { ItemId(UUID.randomUUID().toString()) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val historyStore: HistoryStore = NoHistoryStore,
    private val smartStore: SmartStore = NoSmartStore,
    private val recipeStore: RecipeStore = NoRecipeStore,
    private val scanStore: ScanStore? = null,
    private val productInfoStore: io.theficos.usuals.ui.product.ProductInfoStore? = null,
    private val rowFacts: io.theficos.usuals.ui.product.RowFactsSource = io.theficos.usuals.ui.product.NoRowFacts,
    private val prompts: PromptMemory = InMemoryPromptMemory(),
    private val directoryStore: ListDirectoryStore = NoListDirectory,
    val reminders: io.theficos.usuals.ui.shopping.ReminderController? = null,
    val shoppingTools: io.theficos.usuals.ui.shopping.ShoppingToolsStore = io.theficos.usuals.ui.shopping.ShoppingToolsStore(),
    private val captureMatchMemory: CaptureMatchMemory = InMemoryCaptureMatchMemory(),
    private val automaticMatching: () -> Boolean = { false },
    private val background: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    /** Where rows are worked out from the list and its history. Tests pass their main dispatcher. */
    private val compute: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val basket: io.theficos.usuals.ui.basket.BasketSource? = null,
) : ViewModel() {

    /** State the screen owns rather than the database: what is open, what is being said. */
    private data class ScreenState(
        val swipeRemoval: SwipeRemoval? = null,
        val committingRemovals: List<SwipeRemoval> = emptyList(),
        val checkedExpanded: Boolean = false,
        val capture: CaptureState = CaptureState.Idle,
        val productInfo: io.theficos.usuals.ui.product.ProductInfoState? = null,
        val editingId: ItemId? = null,
        val editingSnapshot: ListRow? = null,
        val editingConflictId: String? = null,
        val transfer: ItemTransferRequest? = null,
        val transferResult: ItemTransferResult? = null,
        val finishedTrip: FinishedTrip? = null,
        val finishedInShoppingMode: Boolean = false,
        val finishedScope: String? = null,
        val prompt: ListPrompt? = null,
        val busy: Boolean = false,
        val historyError: Boolean = false,
        val captureMatches: List<CaptureMatchReview> = emptyList(),
        val matchingBusy: Boolean = false,
        val matchingError: Boolean = false,
        val repeatOffer: RepeatOffer? = null,
        val recipe: RecipeUiState? = null,
        val review: ReviewSession? = null,
        val scanNames: List<ScanNameOffer> = emptyList(),
        /** Scanned barcodes whose lookup has not answered yet. */
        val scanLookups: Set<Gtin> = emptySet(),
        val lastScan: ScanResult? = null,
        val selectedStore: String? = null,
        /** Read from settings when the screen opens, so the menu entry follows a change made in Settings. */
        val basketAvailable: Boolean = false,
        val basket: io.theficos.usuals.ui.basket.BasketSheetState? = null,
        /**
         * Set once dictation has been refused or has failed. The design says
         * not to re-prompt after a denial, so this latches for the lifetime of
         * the screen and the band goes straight to the keyboard.
         */
        val speechBlocked: Boolean = false,
    )

    private val screen = MutableStateFlow(ScreenState(basketAvailable = basket?.enabled() == true))
    private val captureLock = kotlinx.coroutines.sync.Mutex()
    private var removalToken = 0L
    private var productJob: Job? = null
    private var scanNameGeneration = 0

    /**
     * Shared eagerly rather than while-subscribed, on purpose. It is one local
     * query over one grocery list, so keeping it warm costs nothing,
     * and it means a rotation or a trip to settings comes back to a drawn list
     * instead of one empty frame.
     */
    private val shopping = historyStore.shopping(store.items()).stateIn(viewModelScope, SharingStarted.Eagerly, ShoppingSnapshot())

    private val directory = directoryStore.observe().stateIn(viewModelScope, SharingStarted.Eagerly, ListDirectory())
    private val listingTime = MutableStateFlow(clock())

    /**
     * Rows and suggestions, worked out off the main thread from the list, its
     * history and the store filter. A keystroke changes none of those, so typing
     * on a long list never waits for history to be scanned.
     */
    private val listed = combine(shopping, shoppingTools.state, screen.map { it.selectedStore }.distinctUntilChanged(), listingTime) {
        snapshot, personal, filter, _ -> list(snapshot, personal, filter)
    }
        .flowOn(compute).stateIn(viewModelScope, SharingStarted.Eagerly, Listed())

    /** This phone's row-fact choices, or null while lookup is off: row facts follow the opt-in. */
    private val factChoices = combine(rowFacts.lookupEnabled, rowFacts.choices) { on, choices -> choices.takeIf { on } }
        .distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Cached records for the barcodes on screen, read and never fetched: a
     * barcode nobody has looked up stays bare until its sheet is opened.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val facts = combine(factChoices, listed) { choices, listed -> if (choices?.active == true) listed.barcodes else emptySet() }
        .distinctUntilChanged()
        .flatMapLatest { gtins -> if (gtins.isEmpty()) flowOf(emptyMap()) else rowFacts.cached(gtins).catch { emit(emptyMap()) } }

    /** [listed] with what the cache says about each row's product. */
    private val shown = combine(listed, facts, factChoices) { listed, facts, choices -> listed.withFacts(facts, choices) }
        .flowOn(compute).stateIn(viewModelScope, SharingStarted.Eagerly, Listed())

    private val matches = combine(listed, screen.map { (it.capture as? CaptureState.Typing)?.draft.orEmpty() }.distinctUntilChanged()) { listed, draft ->
        if (draft.isBlank()) emptyList()
        else smartStore.autocomplete(parser.parse(draft).lastOrNull()?.name ?: draft, listed.snapshot.history, listed.ordered)
    }.flowOn(compute).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Assembled on the main thread from [screen] directly. The capture field
     * shows the draft from here, and a draft that came back from another thread
     * would drop characters and move the cursor.
     */
    val state: StateFlow<ListUiState> =
        combine(shown, matches, screen, directory) { listed, matches, screenState, lists ->
            val name = lists.lists.firstOrNull { it.id == lists.selected }?.name?.ifBlank { null }
            if (directoryStore !== NoListDirectory && lists.selected.isNotEmpty() && listed.snapshot.listId != lists.selected) {
                ListUiState(directory = lists, header = ListHeader(name = name), busy = true)
            } else project(listed, matches, screenState).let { it.copy(directory = lists, header = it.header.copy(name = name)) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ListUiState())

    init {
        viewModelScope.launch {
            combine(shopping, directory) { snapshot, lists -> snapshot to lists }.collect { (snapshot, lists) ->
                screen.update { state -> state.copy(review = state.review?.takeIf {
                    it.scope == snapshot.listId && (directoryStore === NoListDirectory || it.scope == lists.selected)
                }) }
                if (snapshot.listId.isNotEmpty() && snapshot.listId != lists.selected) return@collect
                refreshReview(snapshot)
                if (snapshot.listId in shoppingTools.state.value.deferred) updateShoppingTools {
                    it.releaseAfterShop(snapshot.listId, snapshot.history.trips.map { trip -> trip.id }.toSet())
                }
                refreshPrompts(snapshot.items, snapshot.history)
                refreshOffers(snapshot.items, snapshot.history)
                maintainRepeats(snapshot.items, snapshot.history)
            }
        }
    }

    fun onDismissConflict(id: String) { viewModelScope.launch { directoryStore.dismissConflict(id) } }
    fun onReviewConflict(conflict: ListConflict) {
        conflict.itemId?.let { target -> screen.update { it.copy(editingId = ItemId(target), editingSnapshot = null, editingConflictId = conflict.id, prompt = null) } }
    }
    fun onSelectList(id: String) = changeList { directoryStore.select(id) }
    fun onCreateList(name: String) = changeList { directoryStore.create(name) }
    fun onRenameList(id: String, name: String) = changeList { directoryStore.rename(id, name) }
    fun onListColor(list: NamedList, color: io.theficos.usuals.model.ListColor?) {
        if (screen.value.busy) return
        screen.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { directoryStore.setColor(list.id, color, list.colorRevision) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(historyError = true) } }
            finally { screen.update { it.copy(busy = false) } }
        }
    }
    /** Deletes a list, or leaves it when someone else owns it. */
    fun onRemoveList(id: String) = changeList { directoryStore.remove(id) }
    /** Archives a list, or restores one from the picker's Archived section. */
    fun onArchiveList(id: String, archived: Boolean) = changeList { directoryStore.archive(id, archived) }
    private fun changeList(change: suspend () -> Unit) {
        if (screen.value.busy || screen.value.matchingBusy || captureLock.isLocked || screen.value.capture != CaptureState.Idle) return
        viewModelScope.launch {
            productJob?.cancel()
            recipeJob?.cancel()
            basketJob?.cancel()
            scanNameGeneration++
            screen.value = ScreenState(speechBlocked = screen.value.speechBlocked, basketAvailable = screen.value.basketAvailable, busy = true)
            try { change() }
            catch (_: Exception) { screen.update { it.copy(historyError = true) } }
            finally { screen.update { it.copy(busy = false) } }
        }
    }

    private fun claimPrompt(type: String, batch: String): Boolean {
        val selected = directory.value.selected
        if (directoryStore !== NoListDirectory && (selected.isEmpty() || shopping.value.listId != selected)) return false
        return prompts.claim(if (selected.isEmpty()) type else "$selected:$type", batch)
    }

    fun onOpened() {
        // Returning tomorrow can make recent purchases eligible without any database write.
        listingTime.value = clock()
        val available = basket?.enabled() == true
        screen.update { it.copy(basketAvailable = available, basket = it.basket.takeIf { available }) }
        refreshPrompts(shopping.value.items, shopping.value.history)
        refreshOffers(shopping.value.items, shopping.value.history)
        viewModelScope.launch { maintainRepeats(shopping.value.items, shopping.value.history) }
    }

    private suspend fun maintainRepeats(rows: List<Item>, facts: HistorySnapshot) {
        try { smartStore.maintainRepeats(clock(), rows, facts) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { screen.update { it.copy(historyError = true) } }
    }

    fun onEditItem(id: ItemId) { val item = shopping.value.items.firstOrNull { it.id == id } ?: return; onEditRow(toRow(item, smartStore.present(listOf(item), shopping.value.history)[id], BarcodeMemory(shopping.value.history))) }
    fun onEditRow(row: ListRow) { screen.update { it.copy(editingId = row.id, editingSnapshot = row, editingConflictId = null, prompt = null) } }
    fun onDismissEdit() {
        screen.update { it.copy(editingId = null, editingConflictId = null) }
        refreshPrompts(shopping.value.items, shopping.value.history)
    }

    fun onProductInfo(row: ListRow) {
        val gtin = row.productGtin ?: return
        val reader = productInfoStore ?: return
        productJob?.cancel()
        screen.update { it.copy(editingId = null, editingSnapshot = null,
            productInfo = io.theficos.usuals.ui.product.ProductInfoState(gtin, row.name, lookupEnabled = reader.enabled(), provenance = row.provenance,
                avoid = factChoices.value?.avoid.orEmpty())) }
        loadProduct(refresh = false)
    }

    fun onDismissProduct() {
        productJob?.cancel()
        screen.update { it.copy(productInfo = null) }
    }

    private var basketJob: Job? = null

    /** Opens the overview for the whole list, read once from details already on the phone. */
    fun onBasketOverview() {
        val source = basket ?: return
        if (!source.enabled()) { screen.update { it.copy(basketAvailable = false, basket = null) }; return }
        val snapshot = shopping.value
        basketJob?.cancel()
        screen.update { it.copy(basket = io.theficos.usuals.ui.basket.BasketSheetState(), editingId = null) }
        basketJob = viewModelScope.launch {
            try {
                val overview = source.forList(snapshot.items, snapshot.history)
                screen.update { it.copy(basket = it.basket?.copy(overview = overview)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(basket = null, historyError = true) } }
        }
    }

    fun onDismissBasket() {
        basketJob?.cancel()
        screen.update { it.copy(basket = null) }
    }

    fun onRefreshProduct() { if (screen.value.productInfo?.loading == false) loadProduct(refresh = true) }

    private fun loadProduct(refresh: Boolean) {
        val reader = productInfoStore ?: return
        val opened = screen.value.productInfo ?: return
        productJob?.cancel()
        screen.update { it.copy(productInfo = opened.copy(loading = true, result = null)) }
        productJob = viewModelScope.launch {
            try {
                val cached = reader.cached(opened.gtin)
                coroutineContext.ensureActive()
                val stale = cached?.refreshEligible(clock()) == true
                val enabled = reader.enabled()
                val fetch = enabled && (refresh || cached == null || stale)
                screen.update { it.copy(productInfo = opened.copy(cached = cached, stale = stale, loading = fetch, lookupEnabled = enabled)) }
                if (fetch) {
                    val result = reader.refresh(opened.gtin)
                    val updated = reader.cached(opened.gtin)
                    coroutineContext.ensureActive()
                    screen.update { it.copy(productInfo = opened.copy(cached = updated ?: cached,
                        stale = (updated ?: cached)?.refreshEligible(clock()) == true,
                        loading = false, lookupEnabled = reader.enabled(), result = result)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                screen.update { it.copy(productInfo = it.productInfo?.copy(loading = false,
                    result = io.theficos.usuals.enrich.QualityLookup.Failed(opened.gtin, io.theficos.usuals.enrich.FailureReason.UNREACHABLE))) }
            }
        }
    }

    fun onSaveEdit(value: ItemEdit) {
        if (screen.value.busy) return
        screen.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val conflict = screen.value.editingConflictId
                val saved = smartStore.edit(value)
                if (saved && conflict != null) directoryStore.dismissConflict(conflict)
                screen.update { it.copy(busy = false, editingId = if (saved) null else it.editingId,
                    editingConflictId = if (saved) null else it.editingConflictId, historyError = !saved) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(busy = false, historyError = true) } }
        }
    }

    fun onTransferItem(row: ListRow) {
        if (screen.value.busy) return
        val version = row.version ?: return
        val source = directory.value.lists.firstOrNull { it.id == version.list && !it.archived } ?: return
        if (version.id != row.id.value || directory.value.selected != source.id || shopping.value.listId != source.id) return
        screen.update { it.copy(editingId = null, editingSnapshot = null, editingConflictId = null,
            transfer = ItemTransferRequest(row, source), transferResult = null, prompt = null) }
    }

    fun onDismissTransfer() { if (!screen.value.busy) screen.update { it.copy(transfer = null) } }
    fun onDismissTransferResult() { screen.update { it.copy(transferResult = null) } }

    fun onConfirmTransfer(destination: String, move: Boolean) {
        if (screen.value.busy) return
        val request = screen.value.transfer ?: return
        val version = request.row.version ?: return
        val result = when {
            directory.value.selected != request.source.id || shopping.value.listId != request.source.id -> ItemTransferResult.SOURCE_CHANGED
            transferTargets(directory.value, request.source.id).none { it.id == destination } -> ItemTransferResult.DESTINATION_UNAVAILABLE
            else -> null
        }
        if (result != null) {
            screen.update { it.copy(transfer = null, transferResult = result) }
            return
        }
        screen.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val outcome = withContext(background) { store.transfer(version, destination, move) }
                screen.update { it.copy(transfer = null, transferResult = outcome) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(transfer = null, transferResult = ItemTransferResult.SOURCE_KEPT) } }
            finally { screen.update { it.copy(busy = false) } }
        }
    }

    fun onStoreFilter(store: String?) { screen.update { it.copy(selectedStore = store) } }
    fun onSkipScanName(gtin: Gtin) {
        screen.update { it.copy(scanNames = it.scanNames.filterNot { offer -> offer.gtin == gtin }, lastScan = ScanResult(gtin, null)) }
    }
    fun onSkipScanNames() {
        scanNameGeneration++
        screen.update { state -> state.copy(scanNames = emptyList(), scanLookups = emptySet(),
            lastScan = state.scanNames.firstOrNull()?.let { ScanResult(it.gtin, null) } ?: state.lastScan) }
    }
    fun onRetryScanName(gtin: Gtin) {
        if (screen.value.scanNames.none { it.gtin == gtin }) return
        screen.update { it.copy(scanNames = it.scanNames.filterNot { offer -> offer.gtin == gtin }) }
        queueScanName(gtin, scanNameGeneration)
    }
    fun onConfirmScanName(gtin: Gtin, name: String) {
        val offer = screen.value.scanNames.firstOrNull { it.gtin == gtin } ?: return
        val scans = scanStore ?: return
        if (screen.value.busy || !name.any(Char::isLetter)) return
        screen.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                scans.add(offer, name)
                screen.update { it.copy(busy = false, scanNames = it.scanNames.filterNot { pending -> pending.gtin == gtin },
                    lastScan = ScanResult(gtin, name.trim())) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(busy = false, historyError = true) } }
        }
    }

    private var recipeJob: Job? = null

    fun onOpenRecipe(url: String = "") {
        recipeJob?.cancel()
        screen.update { it.copy(recipe = RecipeUiState(url), prompt = null, editingId = null, repeatOffer = null) }
        if (url.isNotBlank()) onReadRecipe()
    }
    fun onDismissRecipe() { recipeJob?.cancel(); screen.update { it.copy(recipe = null) } }
    fun onRecipeUrl(url: String) { screen.update { it.copy(recipe = it.recipe?.copy(url = url, result = null, selected = emptySet())) } }
    fun onRecipeSource(source: io.theficos.usuals.ui.recipe.RecipeSource) {
        if (screen.value.recipe?.busy != false) return
        recipeJob?.cancel()
        screen.update { it.copy(recipe = it.recipe?.copy(source = source, result = null, selected = emptySet())) }
    }
    fun onRecipeMarkdown(text: String) { screen.update { it.copy(recipe = it.recipe?.copy(markdown = text.take(30_001), result = null, selected = emptySet())) } }
    fun onReadRecipeMarkdown() = readRecipeMarkdown(emojiQuantities())
    private fun readRecipeMarkdown(emojiQuantities: Boolean) {
        val draft = screen.value.recipe ?: return
        val text = draft.markdown
        runRecipe {
            val plain = !text.any { it in "\r\n\u2028\u2029" } &&
                !Regex("[#\\[\\]`*_<>]|^(?:[-+•◦]\\s|[0-9]+[.)]\\s|[☐☑☒])").containsMatchIn(text.trim())
            if (draft.fromShare && plain && text.length <= io.theficos.usuals.recipe.MarkdownIngredients.MAX_TEXT) {
                val parsed = parser.parse(text)
                if (parsed.size > 1) recipeStore.review(parsed) else recipeStore.markdown(text, emojiQuantities)
            } else recipeStore.markdown(text, emojiQuantities)
        }
    }
    fun onRecipeMarkdownFile(uri: String) { if (screen.value.recipe?.source == io.theficos.usuals.ui.recipe.RecipeSource.MARKDOWN) runRecipe { recipeStore.markdownFile(uri, emojiQuantities()) } }
    private fun emojiQuantities() = shoppingTools.state.value.emojiQuantities
    /** A note already read is read again, so the review shows the names the new choice gives. */
    fun onEmojiQuantities(enabled: Boolean) {
        updateShoppingTools { it.emojiQuantities(enabled) }
        val draft = screen.value.recipe ?: return
        if (draft.result != null && draft.markdown.isNotBlank()) readRecipeMarkdown(enabled)
        else screen.update { it.copy(recipe = it.recipe?.copy(result = null, selected = emptySet())) }
    }
    fun onRecipePhoto(uri: String) {
        if (screen.value.recipe?.source == io.theficos.usuals.ui.recipe.RecipeSource.PHOTO) runRecipe { recipeStore.photoPreview(uri) }
        else recipeStore.releaseCamera(uri)
    }
    fun createRecipePhoto(): String? = try { recipeStore.cameraUri() } catch (_: Exception) { onRecipeFileFailure(); null }
    fun releaseRecipePhoto(uri: String) { recipeStore.releaseCamera(uri) }
    fun onRecipeFileFailure() { screen.update { it.copy(recipe = it.recipe?.copy(result = RecipeLoad.Failed(importProblem = io.theficos.usuals.ui.recipe.ImportProblem.READ))) } }

    fun onReadRecipe() {
        val state = screen.value.recipe ?: return
        runRecipe { recipeStore.read(state.url.trim()) }
    }
    fun onRecipeModel() {
        when (val source = screen.value.recipe?.result) {
            is RecipeLoad.Unstructured -> runRecipe { recipeStore.model(source.url, source.text) }
            is RecipeLoad.Photo -> runRecipe { recipeStore.photo(source.image) }
            else -> Unit
        }
    }
    private fun runRecipe(read: suspend () -> RecipeLoad) {
        if (screen.value.recipe?.busy != false) return
        screen.update { it.copy(recipe = it.recipe?.copy(busy = true)) }
        recipeJob = viewModelScope.launch {
            val result = try { read() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { RecipeLoad.Failed(RecipeProblem.UNREACHABLE) }
            val selected = (result as? RecipeLoad.Review)?.rows?.mapIndexedNotNull { i, row ->
                i.takeIf { row.reason == IngredientReason.NONE }
            }?.toSet().orEmpty()
            screen.update { it.copy(recipe = it.recipe?.copy(busy = false, result = result, selected = selected)) }
        }
    }
    fun onRecipeToggle(index: Int) {
        val current = screen.value.recipe ?: return
        val row = (current.result as? RecipeLoad.Review)?.rows?.getOrNull(index) ?: return
        if (current.busy || row.reason == IngredientReason.ON_LIST) return
        val selection = if (index in current.selected) current.selected - index else current.selected + index
        screen.update { it.copy(recipe = current.copy(selected = selection)) }
    }
    fun onAddRecipe() {
        val current = screen.value.recipe ?: return
        val review = current.result as? RecipeLoad.Review ?: return
        if (current.busy || current.selected.isEmpty()) return
        val rows = review.rows.filterIndexed { index, _ -> index in current.selected }
        // The sheet closes at once and rows appear batch by batch; closing or reopening the sheet does not stop it.
        screen.update { it.copy(recipe = null) }
        viewModelScope.launch {
            try { recipeStore.add(rows) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(historyError = true) } }
        }
    }

    fun onQuantityHint(id: ItemId) {
        viewModelScope.launch {
            try { smartStore.applyQuantityHint(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(historyError = true) } }
        }
    }

    private fun refreshOffers(rows: List<Item>, facts: HistorySnapshot) {
        val current = screen.value
        if (current.busy || current.prompt != null || current.editingId != null || current.transfer != null || current.transferResult != null || current.captureMatches.isNotEmpty() || current.repeatOffer != null || current.recipe != null || current.review != null || current.scanNames.isNotEmpty()) return
        val offer = smartStore.repeatOffers(rows, facts).firstOrNull { claimPrompt("repeat_offer:${it.key}", "v1") }
        if (offer != null) screen.update { it.copy(repeatOffer = offer) }
    }

    fun onRepeatOffer(accept: Boolean) {
        val offer = screen.value.repeatOffer ?: return
        if (screen.value.busy) return
        if (!accept) { screen.update { it.copy(repeatOffer = null) }; return }
        screen.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                smartStore.setRepeat(offer)
                screen.update { it.copy(busy = false, repeatOffer = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(busy = false, historyError = true) } }
        }
    }

    private suspend fun addCaptured(additions: List<Item>) = captureLock.withLock {
        val snapshot = shopping.value
        val aliases = snapshot.history.aliases.associate { it.fromKey to it.toKey }
        val scope = snapshot.listId
        val named = additions.map { incoming ->
            val usual = smartStore.confirmedName(incoming.name, snapshot.history, snapshot.items)
            if (usual == null) incoming else incoming.copy(name = usual, normalizedName = Normalizer.normalize(usual))
        }
        // Persist originals first, even if matching, navigation, or the process is interrupted.
        val captured = store.capture(named)
        val proposed = named.map { incoming ->
            smartStore.nameMatches(incoming.name, snapshot.history, snapshot.items)
                .filterNot { captureMatchMemory.declined(scope, incoming.normalizedName, it.item.key) }
        }
        val reviews = captured.zip(proposed).mapNotNull { (incoming, matches) ->
            if (matches.isEmpty()) null else CaptureMatchReview(incoming, matches)
        }.distinctBy { it.incoming.id }
        val ready = reviews.map { review ->
            val candidate = review.candidates.singleOrNull()?.takeIf { it.automatic }
            val occupied = candidate != null && (snapshot.items + captured).any {
                it.id != review.incoming.id && !it.checked && io.theficos.usuals.parse.ItemIdentity.key(it, aliases) == candidate.item.key
            }
            if (automaticMatching() && candidate != null && !occupied) {
                val matched = try {
                    smartStore.matchCaptures(listOf(CaptureMatchChoice(review.incoming, candidate.item)), remember = false)?.singleOrNull()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                review.copy(matched = matched)
            } else review
        }
        screen.update { current -> current.copy(captureMatches = current.captureMatches + ready,
            matchingError = false, prompt = null, repeatOffer = null) }
    }

    fun onCaptureMatchSelect(id: ItemId, key: String?) {
        if (screen.value.matchingBusy || captureLock.isLocked) return
        screen.update { state -> state.copy(captureMatches = state.captureMatches.map {
            if (it.incoming.id == id && it.matched == null && (key == null || it.candidates.any { candidate -> candidate.item.key == key })) it.copy(selectedKey = key) else it
        }) }
    }

    private fun rememberDeclined(reviews: List<CaptureMatchReview>, scope: String) {
        for (review in reviews.filter { it.matched == null }) for (candidate in review.candidates) {
            if (candidate.item.key != review.selectedKey) captureMatchMemory.decline(scope, review.incoming.normalizedName, candidate.item.key)
        }
    }

    fun onKeepCaptureWording() {
        if (screen.value.matchingBusy || captureLock.isLocked) return
        val reviews = screen.value.captureMatches.map { it.copy(selectedKey = null) }
        rememberDeclined(reviews, shopping.value.listId)
        val pending = reviews.any { it.matched == null }
        screen.update { it.copy(captureMatches = if (pending) it.captureMatches.filter { row -> row.matched != null } else emptyList(), matchingError = false) }
    }

    fun onApplyCaptureMatches() {
        val reviews = screen.value.captureMatches
        if (screen.value.matchingBusy || captureLock.isLocked) return
        val choices = reviews.filter { it.matched == null }.mapNotNull { review ->
            review.candidates.firstOrNull { it.item.key == review.selectedKey }?.let { CaptureMatchChoice(review.incoming, it.item) }
        }
        if (choices.isEmpty()) return
        val scope = shopping.value.listId
        screen.update { it.copy(matchingBusy = true, matchingError = false) }
        viewModelScope.launch {
            try {
                val saved = captureLock.withLock { smartStore.matchCaptures(choices, remember = true) }
                if (saved != null) {
                    rememberDeclined(reviews, scope)
                    screen.update { it.copy(captureMatches = it.captureMatches.filterNot { row -> row in reviews && row.matched == null }) }
                }
                screen.update { it.copy(matchingBusy = false, matchingError = saved == null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(matchingBusy = false, matchingError = true) } }
        }
    }

    fun onUndoCaptureMatch(id: ItemId) {
        if (screen.value.matchingBusy || captureLock.isLocked) return
        val review = screen.value.captureMatches.firstOrNull { it.incoming.id == id } ?: return
        val matched = review.matched ?: return
        screen.update { it.copy(matchingBusy = true, matchingError = false) }
        viewModelScope.launch {
            try {
                val original = ItemMemory(review.incoming.normalizedName, review.incoming.name, 0, 0)
                val saved = captureLock.withLock { smartStore.matchCaptures(listOf(CaptureMatchChoice(matched, original)), remember = false) }
                if (saved != null) {
                    rememberDeclined(listOf(review.copy(matched = null)), shopping.value.listId)
                    screen.update { it.copy(captureMatches = it.captureMatches.filterNot { row -> row.incoming.id == id }) }
                }
                screen.update { it.copy(matchingBusy = false, matchingError = saved == null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(matchingBusy = false, matchingError = true) } }
        }
    }

    fun onOpenReview() {
        val current = screen.value
        if (current.busy || current.review != null || current.capture != CaptureState.Idle ||
            current.editingId != null || current.recipe != null || current.scanNames.isNotEmpty()) return
        val snapshot = shopping.value
        if (directoryStore !== NoListDirectory && directory.value.selected != snapshot.listId) return
        val startedAt = clock()
        screen.update { it.copy(busy = true, prompt = null, repeatOffer = null) }
        viewModelScope.launch {
            try {
                val candidates = withContext(compute) { smartStore.review(startedAt, snapshot.history, snapshot.items) }
                if (shopping.value.listId == snapshot.listId &&
                    (directoryStore === NoListDirectory || directory.value.selected == snapshot.listId)) {
                    screen.update { it.copy(review = ReviewSession(snapshot.listId, candidates, startedAt = startedAt)) }
                    refreshReview(shopping.value)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(historyError = true) } }
            finally { screen.update { it.copy(busy = false) } }
        }
    }

    fun onCloseReview() { screen.update { it.copy(review = null) } }

    fun onSkipReview() {
        val review = screen.value.review ?: return
        if (review.busy || review.current == null) return
        screen.update { it.copy(review = review.advance()) }
        refreshReview(shopping.value)
    }

    fun onMoreReview() {
        val review = screen.value.review ?: return
        if (review.busy || review.current != null) return
        screen.update { it.copy(review = review.more()) }
        refreshReview(shopping.value)
    }

    fun onAddReview() = reviewAction { review ->
        val candidate = review.current ?: return@reviewAction review.copy(busy = false)
        when (val result = smartStore.addReviewed(candidate.key, review.scope, clock())) {
            is ReviewAddResult.Added -> review.advance(result.version)
            ReviewAddResult.AlreadyPresent, ReviewAddResult.Unavailable -> review.advance(undoable = false)
        }
    }

    fun onMuteReview() = reviewAction { review ->
        val candidate = review.current ?: return@reviewAction review.copy(busy = false)
        smartStore.muteReviewed(candidate.key, review.scope, clock())
        review.advance(undoable = false)
    }

    fun onUndoReview() = reviewAction { review ->
        val last = review.last ?: return@reviewAction review.copy(busy = false)
        if (last.added == null) review.undo()
        else if (smartStore.undoReviewed(last.added)) {
            // Restore the card only after the list projection no longer excludes our removed row.
            shopping.first { it.listId != review.scope || it.items.none { row -> row.id.value == last.added.id } }
            review.undo()
        } else review.copy(busy = false, last = null, problem = ReviewProblem.UNDO_CHANGED)
    }

    private fun reviewAction(action: suspend (ReviewSession) -> ReviewSession) {
        val review = screen.value.review ?: return
        if (review.busy) return
        val pending = review.copy(busy = true, problem = null)
        screen.update { it.copy(review = pending) }
        viewModelScope.launch {
            try {
                val result = action(pending)
                screen.update { if (it.review === pending) it.copy(review = result) else it }
                refreshReview(shopping.value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                screen.update { if (it.review === pending) it.copy(review = pending.copy(busy = false, problem = ReviewProblem.SAVE)) else it }
            }
        }
    }

    private fun refreshReview(snapshot: ShoppingSnapshot) {
        val review = screen.value.review ?: return
        if (review.busy || review.scope != snapshot.listId) return
        val eligible = smartStore.review(clock(), snapshot.history, snapshot.items).map { it.key }.toSet()
        screen.update { if (it.review === review) it.copy(review = review.reconcile(eligible)) else it }
    }

    fun onAutocomplete(memory: ItemMemory) {
        val typing = screen.value.capture as? CaptureState.Typing ?: return
        val parsed = parser.parse(typing.draft)
        val selected = ParsedItem(memory.name, parsed.lastOrNull()?.quantity, parsed.lastOrNull()?.note)
        val drafts = parsed.dropLast(1) + selected
        screen.update { it.copy(capture = typing.copy(draft = "")) }
        viewModelScope.launch {
            val now = clock()
            val start = nextPosition()
            store.add(drafts.mapIndexed { index, draft -> draft.toItem(
                id = newItemId(), rawText = typing.draft,
                position = start + index, clock = { now },
            ) })
        }
    }

    fun onMuteAutocomplete(memory: ItemMemory) {
        if (screen.value.capture !is CaptureState.Typing || state.value.autocomplete.none { it.key == memory.key }) return
        val scope = shopping.value.listId
        viewModelScope.launch {
            try { smartStore.muteSuggestion(memory.key, scope) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(historyError = true) } }
        }
    }

    private fun refreshPrompts(rows: List<Item>, history: HistorySnapshot) {
        if (screen.value.busy || screen.value.prompt != null || screen.value.editingId != null || screen.value.transfer != null || screen.value.transferResult != null ||
            screen.value.captureMatches.isNotEmpty() || screen.value.repeatOffer != null || screen.value.recipe != null || screen.value.review != null || screen.value.scanNames.isNotEmpty()) return
        val purchasedIds = history.purchases.map { it.id }.toSet()
        val current = rows.filterNot { it.id in purchasedIds }
        val stale = current.filter { it.checked && it.checkedAt?.let { at -> at <= clock() - 12 * 60 * 60 * 1000L } == true }
        val batch = current.filter { it.checked }.sortedBy { it.id.value }
            .joinToString("|") { "${it.id.value}:${it.checkedAt}" }
        val staleBatch = stale.sortedBy { it.id.value }.joinToString("|") { "${it.id.value}:${it.checkedAt}" }
        val next = when {
            stale.isNotEmpty() && claimPrompt("stale", staleBatch) -> ListPrompt.Stale(stale.map { it.id }.toSet())
            stale.isNotEmpty() -> null
            current.isNotEmpty() && current.all { it.checked } && claimPrompt("all_checked", batch) -> ListPrompt.AllChecked
            else -> {
                val carried = current.filter { !it.checked }.filter { item ->
                    val count = history.trips.count { it.closedAt >= item.createdAt }
                    count >= 3 && count % 3 == 0
                }
                val signature = carried.sortedBy { it.id.value }.joinToString("|") { it.id.value } + "::" +
                    history.trips.map { it.id }.sorted().joinToString("|")
                if (carried.isNotEmpty() && claimPrompt("carry_over", signature)) {
                    ListPrompt.CarryOver(carried.map { it.id }.toSet(), carried.map { it.name })
                } else null
            }
        }
        if (next != null) screen.update { it.copy(prompt = next) }
    }

    fun onDismissPrompt() { screen.update { it.copy(prompt = null) } }
    fun onDismissFinished() { screen.update { it.copy(finishedTrip = null) } }
    fun onDismissHistoryError() { screen.update { it.copy(historyError = false) } }

    fun onFinishShop(onlyIds: Set<ItemId>? = null, versions: List<ItemVersion>? = null) {
        if (screen.value.busy) return
        val finishedScope = shopping.value.listId
        val inShoppingMode = shoppingTools.state.value.mode
        screen.update { it.copy(busy = true, prompt = null) }
        viewModelScope.launch {
            try {
                val finished = if (versions == null) historyStore.finish(onlyIds) else historyStore.finishVersions(versions, onlyIds)
                screen.update { it.copy(busy = false, finishedTrip = finished ?: it.finishedTrip, finishedScope = if (finished != null) finishedScope else it.finishedScope, finishedInShoppingMode = if (finished != null) inShoppingMode else it.finishedInShoppingMode, checkedExpanded = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(busy = false, historyError = true) } }
            refreshPrompts(shopping.value.items, shopping.value.history)
        }
    }

    fun onUndoFinish() {
        val finished = screen.value.finishedTrip ?: return
        if (screen.value.busy) return
        screen.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val undone = historyStore.undo(finished.trip.id)
                screen.update { it.copy(busy = false, finishedTrip = null, historyError = !undone) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(busy = false, historyError = true) } }
            refreshPrompts(shopping.value.items, shopping.value.history)
        }
    }

    fun onRemoveItems(ids: Set<ItemId>) {
        onDismissPrompt()
        viewModelScope.launch { store.removeAll(ids.map { it to null }) }
    }

    /** Empties the list. Purchase history stays, so the usuals are still suggested. */
    fun onClearItems() {
        val rows = shopping.value.items
        screen.update { it.copy(editingId = null) }
        viewModelScope.launch { store.removeAll(rows.map { it.id to it.version }) }
    }

    private var listening: Job? = null

    // Capture ------------------------------------------------------------

    fun onType() {
        if (screen.value.capture is CaptureState.Typing) return
        listening?.cancel()
        listening = null
        speech.stop()
        screen.update { it.copy(capture = CaptureState.Typing(), editingId = null) }
    }

    /** The band has been pressed. */
    fun onCaptureStart() {
        val current = screen.value
        if (current.capture is CaptureState.Typing) return
        if (!current.speechBlocked && speech.isAvailable()) {
            startListening()
        } else {
            screen.update {
                it.copy(
                    capture = CaptureState.Typing(becauseSpeechIsUnavailable = true),
                    editingId = null,
                )
            }
        }
    }

    /**
     * The band has been released, or the gesture was cancelled. Both end the
     * capture: a person who slides their thumb off the band has still said the
     * thing, and throwing it away would be the more surprising behaviour.
     */
    fun onCaptureEnd() {
        if (screen.value.capture is CaptureState.Listening) speech.stop()
    }

    /**
     * The microphone was refused. Latch it, and open the keyboard now rather
     * than at the next press, so the gesture the person just made still ends in
     * a place they can type.
     */
    fun onSpeechUnavailable() {
        listening?.cancel()
        listening = null
        speech.stop()
        screen.update {
            it.copy(
                speechBlocked = true,
                capture = CaptureState.Typing(becauseSpeechIsUnavailable = true),
            )
        }
    }

    fun onDraftChange(text: String) {
        screen.update {
            if (it.capture is CaptureState.Typing) it.copy(capture = it.capture.copy(draft = text)) else it
        }
    }

    fun onDraftSubmit() {
        val capture = screen.value.capture
        if (capture !is CaptureState.Typing) return
        if (capture.draft.isBlank()) return
        // The field stays open with an empty draft: on a phone with no
        // recogniser this is the only way to add anything, and three items
        // should not cost three round trips to the band.
        val next = CaptureState.Typing(becauseSpeechIsUnavailable = capture.becauseSpeechIsUnavailable)
        viewModelScope.launch { commit(capture.draft, next) }
    }

    fun onDraftDismiss() {
        screen.update { it.copy(capture = CaptureState.Idle) }
    }

    /** Shared notes are local drafts. Only a standalone recipe link may fetch a page. */
    fun onSharedText(text: String) {
        val trimmed = text.trim().replace('\u2028', '\n').replace('\u2029', '\n')
        val url = trimmed.takeIf { it.none(Char::isWhitespace) && Regex("https?://[^<>]+", RegexOption.IGNORE_CASE).matches(it) }
        if (url != null) onOpenRecipe(url) else {
            onOpenRecipe()
            screen.update { it.copy(recipe = it.recipe?.copy(fromShare = true)) }
            onRecipeMarkdown(text)
            onReadRecipeMarkdown()
        }
    }

    private fun startListening() {
        listening?.cancel()
        screen.update { it.copy(capture = CaptureState.Listening(), editingId = null) }
        listening = viewModelScope.launch {
            var heard = ""
            try {
                speech.listen().collect { transcript ->
                    heard = transcript.text
                    if (!transcript.isFinal) {
                        screen.update {
                            if (it.capture is CaptureState.Listening) {
                                it.copy(capture = CaptureState.Listening(transcript.text))
                            } else {
                                it
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                // A recogniser that dies mid-gesture must not eat what it heard.
                // Hand the partial to the keyboard and stop offering dictation.
                screen.update {
                    it.copy(
                        capture = CaptureState.Typing(heard, becauseSpeechIsUnavailable = true),
                        speechBlocked = true,
                    )
                }
                return@launch
            }
            commit(heard, CaptureState.Idle)
        }
    }

    /**
     * Parse an utterance and append what it produced.
     *
     * The band is released before the write is awaited, so the surface is ready
     * for the next thing the person says while the row is still being written.
     * All items from one utterance carry one timestamp, taken once: they were
     * said in one breath and ordering them by a millisecond of drift would be
     * an invention.
     */
    private suspend fun commit(utterance: String, next: CaptureState) {
        screen.update { it.copy(capture = next) }
        val parsed = parser.parse(utterance)
        if (parsed.isEmpty()) return
        val now = clock()
        val start = nextPosition()
        val additions = parsed.mapIndexed { index, draft ->
            draft.toItem(
                id = newItemId(),
                rawText = utterance,
                position = start + index,
                clock = { now },
            )
        }
        addCaptured(additions)
    }

    /**
     * A barcode came back from the scanner.
     *
     * Nothing is added yet. The barcode is looked up, or named from an earlier
     * confirmation, and the person confirms a name before the row exists. A
     * barcode alone is never a row: digits are not something to shop for.
     */
    fun onScanned(gtin: Gtin) {
        val state = screen.value
        if (gtin in state.scanLookups || state.scanNames.any { it.gtin == gtin }) return
        queueScanName(gtin, scanNameGeneration)
    }

    private fun queueScanName(gtin: Gtin, generation: Int) {
        val scans = scanStore ?: return
        screen.update { it.copy(scanLookups = it.scanLookups + gtin) }
        viewModelScope.launch {
            val offer = try { scans.lookup(gtin) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ScanNameOffer(gtin, null, null, false, ScanNameProblem.UNAVAILABLE) }
            if (generation != scanNameGeneration) return@launch
            screen.update { state -> state.copy(scanLookups = state.scanLookups - gtin,
                scanNames = if (state.scanNames.any { it.gtin == gtin }) state.scanNames else state.scanNames + offer,
                prompt = null, repeatOffer = null) }
        }
    }

    /** New items go to the end of the list, which is where they were said. */
    private fun nextPosition(): Double = (shopping.value.items.maxOfOrNull { it.position } ?: 0.0) + 1.0

    // The list -----------------------------------------------------------

    fun updateShoppingTools(update: (io.theficos.usuals.ui.shopping.ShoppingToolsStore) -> Unit) {
        viewModelScope.launch(background) {
            try { update(shoppingTools) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(historyError = true) } }
        }
    }
    fun onPin(row: ListRow) {
        val item = shopping.value.items.firstOrNull { it.id == row.id } ?: return
        updateShoppingTools { tools ->
            val existing = tools.state.value.portable.favorites.firstOrNull { it.matches(item.name, item.quantity, item.gtin, item.store) }
            if (existing != null) tools.unpin(existing.id) else tools.pin(io.theficos.usuals.ui.shopping.SavedShoppingItem(
                UUID.randomUUID().toString(), item.name, item.quantity?.amount, item.quantity?.unit?.name, item.gtin?.value, item.store))
        }
    }
    fun onDefer(row: ListRow) {
        val snapshot = shopping.value
        val item = snapshot.items.firstOrNull { it.id == row.id && !it.checked } ?: return
        updateShoppingTools { tools ->
            tools.defer(snapshot.listId, item.id.value, item.version?.lifecycle ?: item.createdAt.toString(), snapshot.history.trips.map { it.id }.toSet())
            screen.update { it.copy(editingId = null) }
        }
    }
    fun onRestoreDeferred(row: ListRow) {
        val scope = shopping.value.listId
        updateShoppingTools { it.restore(scope, row.id.value) }
    }

    fun onAddFavorite(item: io.theficos.usuals.ui.shopping.SavedShoppingItem) {
        val scope = shopping.value.listId
        viewModelScope.launch { try { smartStore.addAgain(listOf(item), scope) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(historyError = true) } } }
    }

    fun onCheckedRow(row: ListRow, checked: Boolean) {
        screen.update { it.copy(editingId = null) }
        viewModelScope.launch { store.setChecked(row.id, checked, row.version) }
    }
    fun onSwipeRemoveRow(row: ListRow) {
        val snapshot = shopping.value
        if (state.value.busy || snapshot.items.none { it.id == row.id && it.version == row.version }) return
        screen.update { current ->
            val pending = current.swipeRemoval?.takeIf { it.scope == snapshot.listId }
            if (pending?.rows?.any { it.id == row.id } == true || current.committingRemovals.any { batch -> batch.scope == snapshot.listId && batch.rows.any { it.id == row.id } }) current
            else current.copy(editingId = null, swipeRemoval = SwipeRemoval(++removalToken, snapshot.listId, pending?.rows.orEmpty() + row))
        }
    }

    fun onUndoSwipeRemoval(token: Long) {
        screen.update { if (it.swipeRemoval?.token == token) it.copy(swipeRemoval = null) else it }
    }

    fun onCommitSwipeRemoval(token: Long) {
        val pending = screen.value.swipeRemoval?.takeIf { it.token == token } ?: return
        if (shopping.value.listId != pending.scope || (directoryStore !== NoListDirectory && directory.value.selected != pending.scope)) {
            onUndoSwipeRemoval(token)
            return
        }
        screen.update { it.copy(swipeRemoval = null, committingRemovals = it.committingRemovals + pending) }
        viewModelScope.launch {
            try {
                store.removeAll(pending.rows.map { it.id to it.version })
                // Room emits after the transaction returns. Do not briefly show the old rows again.
                val projected = withTimeoutOrNull(5_000) {
                    shopping.first { snapshot ->
                        snapshot.listId != pending.scope || pending.rows.none { removed ->
                            snapshot.items.any { it.id == removed.id && it.version == removed.version }
                        }
                    }
                    shown.first { it.snapshot == shopping.value }
                }
                if (projected == null) screen.update { it.copy(historyError = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { screen.update { it.copy(historyError = true) } }
            finally { screen.update { it.copy(committingRemovals = it.committingRemovals.filterNot { batch -> batch.token == token }) } }
        }
    }

    fun onRemoveRow(row: ListRow) {
        screen.update { it.copy(editingId = null) }
        viewModelScope.launch { store.remove(row.id, row.version) }
    }
    fun onHintRow(row: ListRow) { val quantity = row.quantityHint ?: return; viewModelScope.launch { smartStore.acceptQuantityHint(row.id, quantity, row.version) } }
    fun onCheckedChange(id: ItemId, checked: Boolean) {
        screen.update { it.copy(editingId = null) }
        viewModelScope.launch { store.setChecked(id, checked) }
    }

    fun onToggleCheckedSection() {
        screen.update { it.copy(checkedExpanded = !it.checkedExpanded, editingId = null) }
    }

    fun onConfirmRemove(id: ItemId) {
        screen.update { it.copy(editingId = null) }
        viewModelScope.launch { store.remove(id) }
    }

    override fun onCleared() {
        speech.stop()
        super.onCleared()
    }

    // Projection ---------------------------------------------------------

    private class Listed(
        val snapshot: ShoppingSnapshot = ShoppingSnapshot(),
        val ordered: List<Item> = emptyList(),
        val presentation: Map<ItemId, ItemPresentation> = emptyMap(),
        val memory: BarcodeMemory = BarcodeMemory(snapshot.history),
        val rows: ListUiState = ListUiState(),
    ) {
        /** Every product barcode a visible row stands for, its own or remembered. */
        val barcodes: Set<Gtin> = (rows.pending + rows.checked + rows.deferred).mapNotNull { it.productGtin }.toSet()

        fun withRows(next: ListUiState) = Listed(snapshot, ordered, presentation, memory, next)
    }

    private fun Listed.withFacts(facts: Map<Gtin, io.theficos.usuals.enrich.ProductQuality>, choices: io.theficos.usuals.ui.product.RowFactsChoices?): Listed {
        if (choices == null || facts.isEmpty()) return this
        fun ListRow.annotated(): ListRow {
            // A sister-database record has no grade to show, whatever its fields say.
            val quality = productGtin?.let(facts::get)?.takeIf { it.productType == io.theficos.usuals.enrich.ProductType.FOOD } ?: return this
            return copy(grade = quality.nutriScore.takeIf { choices.showGrades },
                allergens = io.theficos.usuals.smart.AllergenAvoidance.match(quality.allergens, quality.traces, choices.avoid).takeIf { it.flagged })
        }
        return withRows(rows.copy(pending = rows.pending.map { it.annotated() }, checked = rows.checked.map { it.annotated() },
            deferred = rows.deferred.map { it.annotated() }))
    }

    private fun list(snapshot: ShoppingSnapshot, personal: io.theficos.usuals.ui.shopping.ShoppingPreferences, selectedStore: String?): Listed {
        val facts = snapshot.history
        // Position first, then the timestamp, then the id: two items dropped at
        // the same position by two phones still land in the same order on both.
        val ordered = snapshot.items.sortedWith(
            compareBy({ it.position }, { it.createdAt }, { it.id.value }),
        )
        val stores = ordered.mapNotNull { it.store }.distinct().sorted()
        val filter = selectedStore?.takeIf { it in stores }
        val visible = if (filter == null) ordered else ordered.filter { it.store == filter }
        val presentation = smartStore.present(ordered, facts)
        val memory = BarcodeMemory(facts)
        val saved = personal.deferred[snapshot.listId]
        val deferred = saved?.rows.orEmpty().takeUnless { saved != null && facts.trips.any { it.id !in saved.trips } }.orEmpty()
        fun isDeferred(item: Item) = !item.checked && deferred[item.id.value] == (item.version?.lifecycle ?: item.createdAt.toString())
        return Listed(snapshot, ordered, presentation, memory, ListUiState(
            personal = personal,
            favoritesOnList = personal.portable.favoritesOnList(ordered, facts),
            repeatSuggestionKeys = io.theficos.usuals.parse.ItemIdentity.preferences(facts.preferences,
                facts.aliases.associate { it.fromKey to it.toKey }).filterValues { it.repeatEveryDays != null && !it.muted }.keys,
            deferred = visible.filter(::isDeferred).map { toRow(it, presentation[it.id], memory) },
            reviewCandidates = smartStore.review(clock(), facts, ordered),
            stores = stores, selectedStore = filter,
            // Everything on the list, bought or not. It is the size of the
            // shop, which is what the number is being read for.
            header = ListHeader(itemCount = visible.size),
            pending = visible.filterNot { it.checked || isDeferred(it) }.map { toRow(it, presentation[it.id], memory) },
            checked = visible.filter { it.checked }.map { toRow(it, presentation[it.id], memory) },
        ))
    }

    private fun project(listed: Listed, matches: List<ItemMemory>, screen: ScreenState): ListUiState {
        val removal = screen.swipeRemoval?.takeIf { it.scope == listed.snapshot.listId }
        val hidden = (screen.committingRemovals + listOfNotNull(removal)).filter { it.scope == listed.snapshot.listId }
            .flatMap { it.rows }.map { it.id }.toSet()
        return listed.rows.copy(
            swipeRemoval = removal,
            pending = listed.rows.pending.filterNot { it.id in hidden },
            checked = listed.rows.checked.filterNot { it.id in hidden },
            deferred = listed.rows.deferred.filterNot { it.id in hidden },
            header = listed.rows.header.copy(itemCount = (listed.rows.pending + listed.rows.checked + listed.rows.deferred).count { it.id !in hidden }),
            productInfo = screen.productInfo,
            basketAvailable = screen.basketAvailable,
            basket = screen.basket,
            captureMatches = screen.captureMatches,
            matchingBusy = screen.matchingBusy,
            matchingError = screen.matchingError,
            repeatOffer = screen.repeatOffer,
            recipe = screen.recipe,
            review = screen.review?.takeIf { it.scope == listed.snapshot.listId },
            scanNames = screen.scanNames,
            scanPending = screen.scanLookups + screen.scanNames.map { it.gtin },
            lastScan = screen.lastScan,
            checkedExpanded = screen.checkedExpanded,
            finishedInShoppingMode = screen.finishedInShoppingMode,
            finishedTrip = screen.finishedTrip?.takeIf { screen.finishedScope == listed.snapshot.listId }?.takeIf { historyStore !is io.theficos.usuals.ui.history.ScopedHistoryStore || listed.snapshot.history.trips.any { trip -> trip.id == it.trip.id } },
            prompt = screen.prompt,
            busy = screen.busy,
            historyError = screen.historyError,
            capture = screen.capture,
            editing = if (screen.editingId == null) null else screen.editingSnapshot ?: listed.ordered.firstOrNull { it.id == screen.editingId }?.let { toRow(it, listed.presentation[it.id], listed.memory) },
            transfer = screen.transfer?.takeIf { it.source.id == listed.snapshot.listId },
            transferResult = screen.transferResult,
            autocomplete = matches,
        )
    }

    private fun toRow(item: Item, presentation: ItemPresentation?, memory: BarcodeMemory): ListRow {
        // Typed rows ask too, not only suggestions and repeats: a staple scanned
        // once then shows its product on every later trip.
        val remembered = if (item.gtin == null) memory.remembered(item) else null
        return ListRow(
            id = item.id,
            name = displayName(item.name),
            quantity = item.quantity,
            note = item.note,
            checked = item.checked,
            rawName = item.name,
            gtin = item.gtin,
            category = presentation?.category ?: Category.OTHER,
            categoryOverride = presentation?.override,
            repeatEveryDays = presentation?.repeatEveryDays,
            fromSuggestion = item.fromSuggestion,
            quantityHint = presentation?.quantityHint,
            store = item.store,
            version = item.version,
            productGtin = item.gtin ?: remembered?.gtin,
            provenance = if (item.gtin != null) io.theficos.usuals.ui.product.ProductProvenance.Scanned
                else remembered?.let { io.theficos.usuals.ui.product.ProductProvenance.Remembered(it.lastBoughtAt) },
        )
    }

    /**
     * Lift the first letter for display only.
     *
     * `Item.name` keeps the person's own words and casing, and a recogniser
     * hands back "leche entera" once the leading "añade" has been stripped. A
     * list of lowercase rows reads like a chat log rather than something
     * written down, so the row starts with a capital and the stored name does
     * not change.
     */
    private fun displayName(name: String): String =
        if (name.isNotEmpty() && name[0].isLowerCase()) {
            name.replaceFirstChar { it.uppercaseChar() }
        } else {
            name
        }
}

package io.theficos.usuals.ui.list

import androidx.compose.runtime.Immutable
import io.theficos.usuals.smart.Category
import io.theficos.usuals.smart.ItemMemory
import io.theficos.usuals.smart.Suggestion
import io.theficos.usuals.smart.RepeatOffer
import io.theficos.usuals.model.Item
import io.theficos.usuals.model.FinishedTrip
import io.theficos.usuals.model.ItemId
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.ui.recipe.RecipeUiState
import io.theficos.usuals.ui.scan.ScanNameOffer

/**
 * Everything the list screen draws, and nothing it has to work out for itself.
 *
 * The rule this type exists to enforce: no string in here is a user-facing
 * sentence. The view model hands over counts, names and flags; the composable
 * renders them with `stringResource`. That keeps the copy in
 * `strings.xml` where it can be translated and reviewed, and it keeps the view
 * model free of an Android context so it can be tested on the JVM.
 */
data class ListUiState(
    val directory: ListDirectory = ListDirectory(),
    val header: ListHeader = ListHeader(),
    /** Still to buy, in list order. */
    val pending: List<ListRow> = emptyList(),
    /** Checked. Collapsed into one counted row unless [checkedExpanded]. */
    val checked: List<ListRow> = emptyList(),
    val checkedExpanded: Boolean = false,
    val finishedTrip: FinishedTrip? = null,
    val prompt: ListPrompt? = null,
    val busy: Boolean = false,
    val historyError: Boolean = false,
    val capture: CaptureState = CaptureState.Idle,
    /** The row being edited, if any. */
    val editing: ListRow? = null,
    val productInfo: io.theficos.usuals.ui.product.ProductInfoState? = null,
    val autocomplete: List<ItemMemory> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val scanMerge: io.theficos.usuals.ui.scan.ScanMerge? = null,
    val mergeReview: MergeReview? = null,
    val repeatOffer: RepeatOffer? = null,
    val recipe: RecipeUiState? = null,
    val scanNames: List<ScanNameOffer> = emptyList(),
    val lastScannedItem: Item? = null,
    val stores: List<String> = emptyList(),
    val selectedStore: String? = null,
    val personal: io.theficos.usuals.ui.shopping.ShoppingPreferences = io.theficos.usuals.ui.shopping.ShoppingPreferences(),
    val deferred: List<ListRow> = emptyList(),
) {
    /** True when the screen should show the instruction instead of a list. */
    val isEmpty: Boolean get() = pending.isEmpty() && checked.isEmpty() && deferred.isEmpty()
}

/** The header: the list's name, then a subline that answers "how much is left". */
data class ListHeader(
    val name: String? = null,
    val itemCount: Int = 0,
)

/**
 * One line on the list, already reduced to what the row draws. Immutable so a
 * row whose values did not change skips recomposition: its model types come
 * from a module Compose cannot see into.
 */
@Immutable
data class ListRow(
    val id: ItemId,
    val name: String,
    val quantity: Quantity? = null,
    val note: String? = null,
    val checked: Boolean = false,
    val version: io.theficos.usuals.model.ItemVersion? = null,
    val rawName: String = name,
    val gtin: io.theficos.usuals.model.Gtin? = null,
    val category: Category = Category.OTHER,
    val categoryOverride: Category? = null,
    val repeatEveryDays: Int? = null,
    val fromSuggestion: Boolean = false,
    val quantityHint: Quantity? = null,
    val store: String? = null,
    /**
     * The barcode Product details opens: the row's own, else the one the
     * household's history remembers for it. Only [gtin] is ever stored, so a
     * remembered code never reaches a purchase as if it had been scanned.
     */
    val productGtin: io.theficos.usuals.model.Gtin? = gtin,
    val provenance: io.theficos.usuals.ui.product.ProductProvenance? = gtin?.let { io.theficos.usuals.ui.product.ProductProvenance.Scanned },
    /** The cached Nutri-Score to show on the row; null shows nothing, including "does not apply". */
    val grade: io.theficos.usuals.enrich.NutriScore? = null,
    /** Avoided allergens the cached record lists; null when there is nothing to flag. */
    val allergens: io.theficos.usuals.smart.AllergenMatch? = null,
)

/**
 * What the bottom band is doing.
 *
 * [Typing] is not an error state. It is the same capture, entered a different
 * way, in the same place on screen, which is why it carries the draft rather
 * than opening a dialog. [becauseSpeechIsUnavailable] only decides whether the
 * band explains itself once; it does not change what the band does.
 */
sealed interface CaptureState {

    data object Idle : CaptureState

    /** Holding the band. [partial] is what the recogniser has heard so far. */
    data class Listening(val partial: String = "") : CaptureState

    data class Typing(
        val draft: String = "",
        val becauseSpeechIsUnavailable: Boolean = false,
    ) : CaptureState
}

sealed interface ListPrompt {
    data object AllChecked : ListPrompt
    data class Stale(val ids: Set<ItemId>) : ListPrompt
    data class CarryOver(val ids: Set<ItemId>, val names: List<String>) : ListPrompt
}

data class MergeReview(val incoming: Item, val usual: ItemMemory, val version: io.theficos.usuals.model.ListVersion? = null)

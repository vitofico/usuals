package io.theficos.usuals.ui.list

import android.content.Intent
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.outlined.Repeat
import io.theficos.usuals.ui.recipe.RecipeImportSheet
import io.theficos.usuals.ui.scan.ScanNameSheet
import io.theficos.usuals.smart.ItemMemory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import io.theficos.usuals.ui.theme.categoryAccent
import io.theficos.usuals.ui.NutriScoreChip
import io.theficos.usuals.ui.allergenList
import io.theficos.usuals.ui.theme.cautionColor
import io.theficos.usuals.smart.AllergenMatch
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextDecoration
import io.theficos.usuals.smart.Category
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.theficos.usuals.ui.shopping.categoryOrder
import io.theficos.usuals.R
import io.theficos.usuals.model.Quantity
import io.theficos.usuals.model.ItemId

/** Test handles. Named here so a test never has to match on translated copy. */
const val CaptureBandTag: String = "capture_band"
const val DraftFieldTag: String = "capture_draft"
const val CheckedDividerTag: String = "checked_divider"
const val ShoppingListTag: String = "shopping_list"

/**
 * Everything the list can do, in one bag.
 *
 * A dozen separate lambda parameters on [ListContent] would be unreadable at
 * both ends, and defaulting them all to no-ops lets a test or a preview supply
 * only the two it cares about.
 */
data class ListActions(
    val shoppingMode: Boolean = false,
    val onPin: (ListRow) -> Unit = {},
    val onDefer: (ListRow) -> Unit = {},
    val onRestoreDeferred: (ListRow) -> Unit = {},
    val onShoppingTools: () -> Unit = {},
    val onShoppingMode: () -> Unit = {},
    val onCaptureStart: () -> Unit = {},
    val onCaptureEnd: () -> Unit = {},
    val onType: () -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val onDraftSubmit: () -> Unit = {},
    val onDraftDismiss: () -> Unit = {},
    val onCheckedChange: (ItemId, Boolean) -> Unit = { _, _ -> },
    val onToggleCheckedSection: () -> Unit = {},
    val onEditItem: (ItemId) -> Unit = {},
    val onEditRow: ((ListRow) -> Unit)? = null,
    val onCheckedRow: ((ListRow, Boolean) -> Unit)? = null,
    val onRemoveRow: ((ListRow) -> Unit)? = null,
    val onHintRow: ((ListRow) -> Unit)? = null,
    val onDismissEdit: () -> Unit = {},
    val onProductInfo: (ListRow) -> Unit = {},
    val onDismissProduct: () -> Unit = {},
    val onRefreshProduct: () -> Unit = {},
    val onSaveEdit: (ItemEdit) -> Unit = {},
    val onTransferItem: (ListRow) -> Unit = {},
    val onConfirmTransfer: (String, Boolean) -> Unit = { _, _ -> },
    val onDismissTransfer: () -> Unit = {},
    val onDismissTransferResult: () -> Unit = {},
    val onStoreFilter: (String?) -> Unit = {},
    val onConfirmScanName: (io.theficos.usuals.model.Gtin, String) -> Unit = { _, _ -> },
    val onSkipScanName: (io.theficos.usuals.model.Gtin) -> Unit = {},
    val onSkipScanNames: () -> Unit = {},
    val onOpenRecipe: () -> Unit = {},
    val onRecipeUrl: (String) -> Unit = {},
    val onReadRecipe: () -> Unit = {},
    val onRecipeModel: () -> Unit = {},
    val onRecipeSource: (io.theficos.usuals.ui.recipe.RecipeSource) -> Unit = {},
    val onRecipeMarkdown: (String) -> Unit = {},
    val onReadRecipeMarkdown: () -> Unit = {},
    val onRecipeMarkdownFile: (String) -> Unit = {},
    val onRecipePhoto: (String) -> Unit = {},
    val onCreateRecipePhoto: () -> String? = { null },
    val onReleaseRecipePhoto: (String) -> Unit = {},
    val onRecipeFileFailure: () -> Unit = {},
    val onEmojiQuantities: (Boolean) -> Unit = {},
    val onRecipeToggle: (Int) -> Unit = {},
    val onAddRecipe: () -> Unit = {},
    val onDismissRecipe: () -> Unit = {},
    val onQuantityHint: (ItemId) -> Unit = {},
    val onCaptureMatchSelect: (ItemId, String?) -> Unit = { _, _ -> },
    val onApplyCaptureMatches: () -> Unit = {},
    val onKeepCaptureWording: () -> Unit = {},
    val onUndoCaptureMatch: (ItemId) -> Unit = {},
    val onRepeatOffer: (Boolean) -> Unit = {},
    val onOpenReview: () -> Unit = {},
    val onCloseReview: () -> Unit = {},
    val onAddReview: () -> Unit = {},
    val onSkipReview: () -> Unit = {},
    val onUndoReview: () -> Unit = {},
    val onMoreReview: () -> Unit = {},
    val onMuteReview: () -> Unit = {},
    val onAutocomplete: (ItemMemory) -> Unit = {},
    val onMuteAutocomplete: (ItemMemory) -> Unit = {},
    val onConfirmRemove: (ItemId) -> Unit = {},
    val onOpenScan: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenAccount: () -> Unit = {},
    val onDismissConflict: (String) -> Unit = {},
    val onReviewConflict: (ListConflict) -> Unit = {},
    val onSelectList: (String) -> Unit = {},
    val onCreateList: (String) -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onShareList: () -> Unit = {},
    val onShareText: () -> Unit = {},
    /** Null hides the menu entry: the basket overview is off. */
    val onBasketOverview: (() -> Unit)? = null,
    val onRenameList: (String, String) -> Unit = { _, _ -> },
    val onRemoveList: (String) -> Unit = {},
    val onArchiveList: (String, Boolean) -> Unit = { _, _ -> },
    val onFinishShop: (Set<ItemId>?) -> Unit = {},
    val onFinishRows: ((List<ListRow>, Set<ItemId>?) -> Unit)? = null,
    val onUndoFinish: () -> Unit = {},
    val onDismissPrompt: () -> Unit = {},
    val onDismissFinished: () -> Unit = {},
    val onDismissHistoryError: () -> Unit = {},
    val onRemoveItems: (Set<ItemId>) -> Unit = {},
    val onClearItems: () -> Unit = {},
)

/**
 * The list, wired up: state from the view model, and the one piece of Android
 * the view model must not know about, which is the microphone permission.
 *
 * The permission is asked for on the first press of the band and never again.
 * A refusal is not an error state and it does not put a dialog on screen: the
 * band opens a text field in the same place, and dictation stops being offered
 * for the rest of the session. That is the design's rule for a denied camera
 * applied to the microphone, and it is the same rule for both because a person
 * who said no once should not be asked on every item.
 */
@Composable
fun ListScreen(
    vm: ListViewModel,
    onOpenScan: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    sharedText: String? = null,
    onSharedTextConsumed: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenSharing: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val view = androidx.compose.ui.platform.LocalView.current
    var reminderOpen by rememberSaveable { mutableStateOf(false) }
    if (reminderOpen) vm.reminders?.let { io.theficos.usuals.ui.shopping.ReminderSheet(it) { reminderOpen = false } }
    state.basket?.let { io.theficos.usuals.ui.basket.BasketOverviewSheet(it, vm::onDismissBasket) }
    if (toolsOpen) io.theficos.usuals.ui.shopping.ShoppingToolsSheet(state.personal, state.selectedStore,
        vm::updateShoppingTools, vm::onAddFavorite, { toolsOpen = false },
        onReminder = vm.reminders?.let { { toolsOpen = false; reminderOpen = true } }, alreadyAdded = state.favoritesOnList)
    var microphoneAsked by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.onOpened() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(owner, view, state.personal.mode, state.personal.keepAwake) {
        val previous = view.keepScreenOn
        fun update() { view.keepScreenOn = previous || state.personal.mode && state.personal.keepAwake && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        val observer = LifecycleEventObserver { _, _ -> update() }
        owner.lifecycle.addObserver(observer); update()
        onDispose { owner.lifecycle.removeObserver(observer); view.keepScreenOn = previous }
    }

    // Granting does not start a capture: by the time the system dialog closes
    // the person has long since let go of the band. They press again, and this
    // time it listens.
    val microphone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) vm.onSpeechUnavailable() }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            vm.onSharedText(sharedText)
            onSharedTextConsumed()
        }
    }

    // Read when tapped, not captured as a value, so the row lambdas stay equal and unchanged rows skip recomposition.
    val haptics by rememberUpdatedState(state.personal.mode && state.personal.haptics)
    ListContent(
        state = state,
        actions = ListActions(
            shoppingMode = state.personal.mode,
            onShoppingTools = { toolsOpen = true },
            onShoppingMode = { vm.updateShoppingTools { it.mode(!it.state.value.mode) } },
            onPin = vm::onPin, onDefer = vm::onDefer, onRestoreDeferred = vm::onRestoreDeferred,
            onCaptureStart = {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                when {
                    granted -> vm.onCaptureStart()
                    !microphoneAsked -> {
                        microphoneAsked = true
                        microphone.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    else -> vm.onSpeechUnavailable()
                }
            },
            onCaptureEnd = vm::onCaptureEnd,
            onType = vm::onType,
            onDraftChange = vm::onDraftChange,
            onDraftSubmit = vm::onDraftSubmit,
            onDraftDismiss = vm::onDraftDismiss,
            onCheckedChange = vm::onCheckedChange,
            onToggleCheckedSection = vm::onToggleCheckedSection,
            onEditItem = vm::onEditItem,
            onEditRow = vm::onEditRow, onCheckedRow = { row, checked ->
                if (haptics) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                vm.onCheckedRow(row, checked)
            }, onRemoveRow = vm::onRemoveRow, onHintRow = vm::onHintRow,
            onDismissEdit = vm::onDismissEdit,
            onProductInfo = vm::onProductInfo,
            onDismissProduct = vm::onDismissProduct,
            onRefreshProduct = vm::onRefreshProduct,
            onSaveEdit = vm::onSaveEdit,
            onTransferItem = vm::onTransferItem,
            onConfirmTransfer = vm::onConfirmTransfer,
            onDismissTransfer = vm::onDismissTransfer,
            onDismissTransferResult = vm::onDismissTransferResult,
            onAutocomplete = vm::onAutocomplete,
            onMuteAutocomplete = vm::onMuteAutocomplete,
            onOpenReview = vm::onOpenReview,
            onCloseReview = vm::onCloseReview,
            onAddReview = vm::onAddReview,
            onSkipReview = vm::onSkipReview,
            onUndoReview = vm::onUndoReview,
            onMoreReview = vm::onMoreReview,
            onMuteReview = vm::onMuteReview,
            onQuantityHint = vm::onQuantityHint,
            onStoreFilter = vm::onStoreFilter,
            onConfirmScanName = vm::onConfirmScanName,
            onSkipScanName = vm::onSkipScanName,
            onSkipScanNames = vm::onSkipScanNames,
            onOpenRecipe = { vm.onOpenRecipe() },
            onRecipeUrl = vm::onRecipeUrl,
            onReadRecipe = vm::onReadRecipe,
            onRecipeModel = vm::onRecipeModel,
            onRecipeSource = vm::onRecipeSource,
            onRecipeMarkdown = vm::onRecipeMarkdown,
            onReadRecipeMarkdown = vm::onReadRecipeMarkdown,
            onRecipeMarkdownFile = vm::onRecipeMarkdownFile,
            onRecipePhoto = vm::onRecipePhoto,
            onCreateRecipePhoto = vm::createRecipePhoto,
            onReleaseRecipePhoto = vm::releaseRecipePhoto,
            onRecipeFileFailure = vm::onRecipeFileFailure,
            onEmojiQuantities = vm::onEmojiQuantities,
            onRecipeToggle = vm::onRecipeToggle,
            onAddRecipe = vm::onAddRecipe,
            onDismissRecipe = vm::onDismissRecipe,
            onCaptureMatchSelect = vm::onCaptureMatchSelect,
            onApplyCaptureMatches = vm::onApplyCaptureMatches,
            onKeepCaptureWording = vm::onKeepCaptureWording,
            onUndoCaptureMatch = vm::onUndoCaptureMatch,
            onRepeatOffer = vm::onRepeatOffer,
            onConfirmRemove = vm::onConfirmRemove,
            onOpenScan = onOpenScan,
            onOpenSettings = onOpenSettings,
            onOpenAccount = onOpenAccount,
            onOpenHistory = onOpenHistory,
            onSelectList = vm::onSelectList,
            onDismissConflict = vm::onDismissConflict,
            onReviewConflict = vm::onReviewConflict,
            onCreateList = vm::onCreateList,
            onShareList = onOpenSharing,
            onRenameList = vm::onRenameList,
            onRemoveList = vm::onRemoveList,
            onBasketOverview = if (state.basketAvailable) vm::onBasketOverview else null,
            onArchiveList = vm::onArchiveList,
            onShareText = {
                val text = (state.pending + state.deferred + state.checked).joinToString("\n") { row ->
                    context.getString(
                        if (row.checked) R.string.share_checked_item else R.string.share_open_item,
                        row.name, row.quantity?.let { formatQuantity(context, it) }.orEmpty(),
                    ).trim()
                }
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, context.getString(R.string.share_list)))
            },
            onFinishShop = { vm.onFinishShop(it) },
            onFinishRows = { rows, ids -> vm.onFinishShop(ids, rows.mapNotNull { it.version }.takeIf { it.size == rows.size }) },
            onUndoFinish = vm::onUndoFinish,
            onDismissPrompt = vm::onDismissPrompt,
            onDismissFinished = vm::onDismissFinished,
            onDismissHistoryError = vm::onDismissHistoryError,
            onRemoveItems = vm::onRemoveItems,
            onClearItems = vm::onClearItems,
        ),
        modifier = modifier,
    )
}

/**
 * The list surface itself, with no view model and no Android permissions in
 * sight, so it can be rendered by a test or a preview from a plain state
 * object.
 *
 * Three bands, top to bottom: who this list belongs to, the list, and the
 * capture surface. The middle one gets all the remaining height and the other
 * two are as short as they can be.
 */
@Composable
fun ListContent(
    state: ListUiState,
    actions: ListActions,
    modifier: Modifier = Modifier,
) {
    var repeatToMute by remember(state.directory.selected) { mutableStateOf<ItemMemory?>(null) }
    repeatToMute?.let { pending ->
        AlertDialog(onDismissRequest = { repeatToMute = null },
            title = { Text(stringResource(R.string.memory_stop_repeat_title, pending.name)) },
            text = { Text(stringResource(R.string.memory_stop_repeat_help)) },
            confirmButton = { TextButton(onClick = {
                repeatToMute = null
                actions.onMuteAutocomplete(pending)
            }) { Text(stringResource(R.string.memory_stop_suggesting)) } },
            dismissButton = { TextButton(onClick = { repeatToMute = null }) { Text(stringResource(R.string.edit_cancel)) } })
    }
    state.editing?.let { row ->
        EditItemSheet(row, state.busy, actions.onSaveEdit, actions.onDismissEdit,
            onRemove = { actions.onRemoveRow?.invoke(row) ?: actions.onConfirmRemove(it) },
            onPin = { actions.onPin(row) }, onDefer = { actions.onDefer(row) },
            pinned = state.personal.portable.favorites.any { it.matches(row.rawName, row.quantity, row.gtin, row.store) },
            onProductInfo = row.productGtin?.let { { actions.onProductInfo(row) } },
            onTransfer = row.version?.takeIf { it.list == state.directory.selected }?.let { { actions.onTransferItem(row) } })
    }
    state.transfer?.let { request ->
        TransferItemDialog(request.row, request.source, state.directory, state.busy,
            actions.onConfirmTransfer, actions.onDismissTransfer)
    }
    state.transferResult?.let { outcome ->
        AlertDialog(onDismissRequest = actions.onDismissTransferResult,
            text = { Text(stringResource(when (outcome) {
                io.theficos.usuals.model.ItemTransferResult.COPIED -> R.string.transfer_copied
                io.theficos.usuals.model.ItemTransferResult.MOVED -> R.string.transfer_moved
                io.theficos.usuals.model.ItemTransferResult.SOURCE_CHANGED -> R.string.transfer_source_changed
                io.theficos.usuals.model.ItemTransferResult.DESTINATION_UNAVAILABLE -> R.string.transfer_destination_unavailable
                io.theficos.usuals.model.ItemTransferResult.SOURCE_KEPT -> R.string.transfer_source_kept
            })) },
            confirmButton = { TextButton(onClick = actions.onDismissTransferResult) { Text(stringResource(android.R.string.ok)) } })
    }
    state.productInfo?.let { info ->
        io.theficos.usuals.ui.product.ProductInfoSheet(info, actions.onRefreshProduct,
            { actions.onDismissProduct(); actions.onOpenSettings() }, actions.onDismissProduct)
    }
    state.recipe?.let { RecipeImportSheet(it, actions.onRecipeUrl, actions.onReadRecipe, actions.onRecipeModel,
        actions.onRecipeToggle, actions.onAddRecipe, actions.onDismissRecipe,
        actions.onRecipeSource, actions.onRecipeMarkdown, actions.onReadRecipeMarkdown, actions.onRecipeMarkdownFile,
        actions.onRecipePhoto, actions.onCreateRecipePhoto, actions.onReleaseRecipePhoto, actions.onRecipeFileFailure,
        state.personal.emojiQuantities, actions.onEmojiQuantities) }
    if (state.productInfo == null && state.recipe == null && state.editing == null && state.transfer == null && state.transferResult == null) state.scanNames.firstOrNull()?.let {
        ScanNameSheet(it, state.scanNames.size, state.busy, { name -> actions.onConfirmScanName(it.gtin, name) },
            { actions.onSkipScanName(it.gtin) }, actions.onSkipScanNames)
    }
    SmartAssistanceDialogs(state, actions)
    ListHistoryDialogs(state, actions)
    state.review?.let { ReviewSheet(it, actions.onCloseReview, actions.onAddReview, actions.onSkipReview,
        actions.onUndoReview, actions.onMoreReview, actions.onMuteReview) }
    val typing = state.capture as? CaptureState.Typing
    BackHandler(enabled = typing != null) { actions.onDraftDismiss() }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The window draws behind the system bars and the keyboard, so the list keeps clear of all three.
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
          Column(modifier = Modifier.weight(1f).closesDraft(typing != null, actions.onDraftDismiss)) {
            ListHeaderBar(
                header = state.header,
                directory = state.directory,
                busy = state.busy || state.matchingBusy || state.capture != CaptureState.Idle,
                actions = actions,
                compact = typing != null || state.personal.mode,
                typing = typing != null,
                onFinish = state.checked.takeIf { it.isNotEmpty() }?.let { rows ->
                    { actions.onFinishRows?.invoke(rows, null) ?: actions.onFinishShop(null) }
                },
            )
            if (typing == null) {
                if (state.personal.mode) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.shopping_mode), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = actions.onShoppingMode) { Text(stringResource(R.string.shopping_stop)) }
                }
                val usefulFilter = state.stores.size > 1 || state.selectedStore != null ||
                    (state.stores.isNotEmpty() && (state.pending + state.checked + state.deferred).any { it.store == null })
                if (usefulFilter) StoreFilter(state.stores, state.selectedStore, actions.onStoreFilter)
            }
            if (!state.personal.mode && typing == null && state.reviewCandidates.isNotEmpty()) {
                ReviewEntry(!state.busy, actions.onOpenReview)
            }
            val partial = (state.capture as? CaptureState.Listening)?.partial.orEmpty()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (state.isEmpty && partial.isBlank() && state.captureMatches.isEmpty()) {
                    if (typing == null) {
                        if (state.selectedStore == null) EmptyInstruction() else Text(stringResource(R.string.store_empty), modifier = Modifier.padding(20.dp))
                    }
                } else {
                    ItemList(state = state, partial = partial, actions = actions)
                }
            }

            state.finishedTrip?.let { finished ->
                Snackbar(action = {
                    TextButton(onClick = actions.onUndoFinish, enabled = !state.busy) {
                        Text(stringResource(R.string.finish_shop_undo))
                    }
                }, dismissAction = {
                    TextButton(onClick = actions.onDismissFinished) { Text(stringResource(R.string.history_dismiss)) }
                }) {
                    Text(pluralStringResource(R.plurals.finish_shop_saved, finished.count, finished.count))
                }
            }
          }
            CaptureBand(capture = state.capture, actions = actions.copy(onMuteAutocomplete = { memory ->
                if (memory.key in state.repeatSuggestionKeys) repeatToMute = memory
                else actions.onMuteAutocomplete(memory)
            }), autocomplete = state.autocomplete)
        }
    }
}

/** While typing, a tap above the band closes the field and its keyboard instead of reaching what is under it. */
private fun Modifier.closesDraft(typing: Boolean, onClose: () -> Unit): Modifier = if (!typing) this else pointerInput(onClose) {
    awaitEachGesture {
        awaitFirstDown(pass = PointerEventPass.Initial).consume()
        onClose()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun ListHeaderBar(header: ListHeader, directory: ListDirectory, busy: Boolean, actions: ListActions, compact: Boolean, typing: Boolean, onFinish: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
        .clip(RoundedCornerShape(20.dp)).background(colors.primaryContainer)) {
        Box(Modifier.matchParentSize()) {
            io.theficos.usuals.ui.theme.BrandCollage(Modifier.align(Alignment.CenterEnd)
                .offset(x = 34.dp, y = (-8).dp)
                .size(width = if (compact) 210.dp else 260.dp, height = if (compact) 146.dp else 181.dp))
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()
            .heightIn(min = when { typing -> 56.dp; compact -> 72.dp; else -> 80.dp })
            .padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 6.dp)) {
            Column(Modifier.weight(1f)) {
                NamedListPicker(header.name, directory, busy, actions, compact)
                if (!typing) Text(pluralStringResource(R.plurals.list_item_count, header.itemCount, header.itemCount),
                    modifier = Modifier.background(colors.primaryContainer).padding(end = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer)
            }
            if (!typing) {
                IconButton(onClick = actions.onOpenHistory, enabled = !busy,
                    modifier = Modifier.background(colors.surface, CircleShape),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.primary)) {
                    Icon(Icons.Outlined.History, stringResource(R.string.history_title), Modifier.size(22.dp))
                }
                Box(Modifier.background(colors.surface, CircleShape)) {
                    ListMenu(header, directory, busy, actions, onFinish)
                }
            }
        }
    }
}

@Composable
private fun StoreFilter(stores: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        val label = stringResource(R.string.quiet_list_store_filter, selected ?: stringResource(R.string.store_all))
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = label }) {
            Icon(Icons.Outlined.Storefront, null, Modifier.size(20.dp))
            Text(label, Modifier.padding(horizontal = 8.dp))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.store_all)) }, onClick = { open = false; onSelect(null) })
            stores.forEach { store ->
                DropdownMenuItem(text = { Text(store) }, onClick = { open = false; onSelect(store) })
            }
        }
    }
}

/** Secondary list actions stay available without competing with capture and grocery rows. */
@Composable
private fun ListMenu(header: ListHeader, directory: ListDirectory, busy: Boolean, actions: ListActions, onFinish: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    // Each dialog keeps the list it was opened for: sync can change the selection while it is open.
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var archiving by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var clearing by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = directory.lists.firstOrNull { it.id == directory.selected }
    Box {
        IconButton(onClick = { managing = false; open = true }, enabled = !busy, colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.list_more), Modifier.size(22.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
          if (!managing) {
            DropdownMenuItem(text = { Text(stringResource(R.string.quiet_list_import)) }, onClick = { open = false; actions.onOpenRecipe() })
            DropdownMenuItem(text = { Text(stringResource(R.string.history_title)) }, onClick = { open = false; actions.onOpenHistory() })
            DropdownMenuItem(text = { Text(stringResource(R.string.shopping_favorites)) }, onClick = { open = false; actions.onShoppingTools() })
            DropdownMenuItem(text = { Text(stringResource(if (actions.shoppingMode) R.string.shopping_stop else R.string.shopping_start)) }, onClick = { open = false; actions.onShoppingMode() })
            onFinish?.let { finish -> DropdownMenuItem(text = { Text(stringResource(R.string.finish_shop)) }, onClick = { open = false; finish() }) }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.list_share)) }, onClick = { open = false; actions.onShareList() })
            DropdownMenuItem(text = { Text(stringResource(R.string.share_list)) }, enabled = header.itemCount > 0, onClick = { open = false; actions.onShareText() })
            actions.onBasketOverview?.let { overview ->
                DropdownMenuItem(text = { Text(stringResource(R.string.basket_title)) }, enabled = header.itemCount > 0, onClick = { open = false; overview() })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.quiet_list_manage)) }, onClick = { managing = true })
            DropdownMenuItem(text = { Text(stringResource(R.string.open_settings)) }, onClick = { open = false; actions.onOpenSettings() })
          } else {
            DropdownMenuItem(text = { Text(stringResource(R.string.quiet_list_options)) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }, onClick = { managing = false })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(stringResource(R.string.list_rename)) }, onClick = { open = false; renaming = selected?.id; draft = selected?.name.orEmpty() })
            DropdownMenuItem(text = { Text(stringResource(R.string.list_clear)) }, enabled = header.itemCount > 0, onClick = { open = false; clearing = selected?.id ?: "" })
            if (!directory.unused) DropdownMenuItem(text = { Text(stringResource(R.string.list_archive)) }, onClick = { open = false; archiving = selected?.id })
            DropdownMenuItem(text = { Text(stringResource(if (selected?.editor == true) R.string.share_leave else R.string.list_delete)) }, onClick = { open = false; removing = selected?.id })
          }
        }
    }
    directory.lists.firstOrNull { it.id == renaming }?.let { list ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.list_rename)) },
            text = { OutlinedTextField(draft, { if (it.length <= 100) draft = it }, label = { Text(stringResource(R.string.lists_name)) }, singleLine = true) },
            confirmButton = { TextButton(enabled = draft.isNotBlank(), onClick = { renaming = null; actions.onRenameList(list.id, draft.trim()) }) { Text(stringResource(R.string.list_rename)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.edit_cancel)) } })
    }
    directory.lists.firstOrNull { it.id == archiving }?.let { list ->
        AlertDialog(onDismissRequest = { archiving = null },
            title = { Text(stringResource(R.string.list_archive_title, list.name.ifBlank { stringResource(R.string.household_fallback_name) })) },
            text = { Text(stringResource(if (list.editor) R.string.list_archive_help_editor else R.string.list_archive_help)) },
            confirmButton = { TextButton(onClick = { archiving = null; actions.onArchiveList(list.id, true) }) { Text(stringResource(R.string.list_archive)) } },
            dismissButton = { TextButton(onClick = { archiving = null }) { Text(stringResource(R.string.edit_cancel)) } })
    }
    directory.lists.firstOrNull { it.id == removing }?.let { list -> RemoveListDialog(list, { removing = null }, actions) }
    clearing?.let { id ->
        val name = (directory.lists.firstOrNull { it.id == id }?.name ?: header.name).orEmpty().ifBlank { stringResource(R.string.household_fallback_name) }
        AlertDialog(onDismissRequest = { clearing = null },
            title = { Text(stringResource(R.string.list_clear_title, name)) },
            text = { Text(stringResource(R.string.list_clear_help)) },
            confirmButton = { TextButton(onClick = { clearing = null; actions.onClearItems() }) { Text(stringResource(R.string.list_clear)) } },
            dismissButton = { TextButton(onClick = { clearing = null }) { Text(stringResource(R.string.edit_cancel)) } })
    }
}

/** Confirms deleting a list, or leaving it when someone else owns it. */
@Composable
internal fun RemoveListDialog(list: NamedList, onDismiss: () -> Unit, actions: ListActions) {
    val leaves = list.editor
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (leaves) R.string.share_leave_title else R.string.list_delete_title, list.name.ifBlank { stringResource(R.string.household_fallback_name) })) },
        text = { Text(stringResource(if (leaves) R.string.share_leave_help else R.string.list_delete_help)) },
        confirmButton = { TextButton(onClick = { onDismiss(); actions.onRemoveList(list.id) }) { Text(stringResource(if (leaves) R.string.share_leave else R.string.list_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_cancel)) } })
}

@Composable
private fun EmptyInstruction() {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val artworkHeight = (maxHeight - 160.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale)
            .coerceIn(0.dp, 156.dp)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (artworkHeight >= 96.dp) {
                io.theficos.usuals.ui.theme.BrandCollage(Modifier.size(width = 240.dp, height = artworkHeight).padding(bottom = 16.dp))
            }
            Text(stringResource(R.string.quiet_list_empty_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(stringResource(R.string.quiet_list_empty_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun CategoryHeader(category: Category, count: Int) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val accent = categoryAccent(category, dark)
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 28.dp, top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(28.dp).background(accent.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center) {
            Icon(when (category) {
                Category.PRODUCE -> Icons.Outlined.Eco
                Category.DAIRY -> Icons.Outlined.Egg
                Category.BAKERY -> Icons.Outlined.BakeryDining
                Category.MEAT -> Icons.Outlined.Restaurant
                Category.FISH -> Icons.Outlined.SetMeal
                Category.FROZEN -> Icons.Outlined.AcUnit
                Category.PANTRY -> Icons.Outlined.Kitchen
                Category.DRINKS -> Icons.Outlined.LocalCafe
                Category.SNACKS -> Icons.Outlined.Cookie
                Category.HOUSEHOLD -> Icons.Outlined.CleaningServices
                Category.PERSONAL_CARE -> Icons.Outlined.Spa
                Category.OTHER -> Icons.Outlined.ShoppingBag
            }, null, modifier = Modifier.size(18.dp), tint = accent)
        }
        Text(categoryLabel(category), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemList(
    state: ListUiState,
    partial: String,
    actions: ListActions,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(ShoppingListTag),
        contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
    ) {
        if (state.captureMatches.isNotEmpty()) {
            item(key = "capture-match-header") { CaptureMatchHeader(state) }
            items(state.captureMatches, key = { "capture-match:${it.incoming.id.value}" }) { review ->
                CaptureMatchRow(review, state.matchingBusy, actions)
            }
            item(key = "capture-match-actions") { CaptureMatchActions(state, actions) }
        }
        state.pending.groupBy { it.category }.toSortedMap(compareBy { state.personal.portable.categoryOrder(state.selectedStore).indexOf(it) }).forEach { (category, rows) ->
            item(key = "category:${category.name}") {
                CategoryHeader(category, rows.size)
            }
            itemsIndexed(rows, key = { _, row -> "item:${row.id.value}" }) { index, row ->
                RemovableItemRow(row, actions, RoundedCornerShape(
                    topStart = if (index == 0) 16.dp else 0.dp, topEnd = if (index == 0) 16.dp else 0.dp,
                    bottomStart = if (index == rows.lastIndex) 16.dp else 0.dp,
                    bottomEnd = if (index == rows.lastIndex) 16.dp else 0.dp), divider = index < rows.lastIndex)
            }
        }

        if (state.deferred.isNotEmpty()) {
            item(key = "deferred-heading") {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.shopping_next_time), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.shopping_next_time_help), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(state.deferred, key = { "deferred:${it.id.value}" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.name, Modifier.weight(1f))
                    val restoreLabel = stringResource(R.string.shopping_restore_item, row.name)
                    TextButton(onClick = { actions.onRestoreDeferred(row) }, modifier = Modifier.semantics { contentDescription = restoreLabel }) { Text(stringResource(R.string.shopping_restore)) }
                }
            }
        }

        // What is being said right now, growing at the end of the list. It is
        // not an item yet, so it has no id and cannot be tapped.
        if (partial.isNotBlank()) {
            item(key = "capture-partial") { PartialRow(partial) }
        }

        if (state.checked.isNotEmpty()) {
            item(key = "checked-divider") {
                CheckedDivider(
                    count = state.checked.size,
                    expanded = state.checkedExpanded,
                    onClick = actions.onToggleCheckedSection,
                )
            }
            item(key = "finish-shop") {
                TextButton(onClick = { actions.onFinishRows?.invoke(state.checked, null) ?: actions.onFinishShop(null) },
                    enabled = !state.busy, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Text(stringResource(R.string.finish_shop))
                }
            }
            if (state.checkedExpanded) {
                items(state.checked, key = { "item:${it.id.value}" }) { row ->
                    RemovableItemRow(
                        row = row,
                        actions = actions,
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RemovableItemRow(row: ListRow, actions: ListActions, shape: Shape = RoundedCornerShape(16.dp), divider: Boolean = false) {
    key(row.id, row.version ?: row) {
        val current by rememberUpdatedState(actions)
        val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) current.onRemoveRow?.invoke(row) ?: current.onConfirmRemove(row.id)
            true
        })
        SwipeToDismissBox(state = dismiss,
            modifier = Modifier.padding(horizontal = 20.dp).clip(shape), backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer))
        }) {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                ItemRow(row, actions, divider)
            }
        }
    }
}

/** A tap checks the row, a hold edits it, and a swipe removes it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    row: ListRow,
    actions: ListActions,
    divider: Boolean,
) {
    val checkLabel = stringResource(
        if (row.checked) R.string.item_uncheck else R.string.item_check,
        row.name,
    )
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (actions.shoppingMode) 84.dp else 64.dp)
            .semantics { toggleableState = if (row.checked) ToggleableState.On else ToggleableState.Off }
            .combinedClickable(
                role = Role.Checkbox,
                onClickLabel = checkLabel,
                onLongClickLabel = stringResource(R.string.item_edit_action, row.name),
                onLongClick = { actions.onEditRow?.invoke(row) ?: actions.onEditItem(row.id) },
                onClick = { actions.onCheckedRow?.invoke(row, !row.checked) ?: actions.onCheckedChange(row.id, !row.checked) },
            )
            .drawBehind {
                if (divider) drawLine(line,
                    start = androidx.compose.ui.geometry.Offset(58.dp.toPx(), size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width - 16.dp.toPx(), size.height),
                    strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 14.dp).size(24.dp)
            .background(if (row.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, CircleShape)
            .border(if (row.checked) 0.dp else 1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center) {
            if (row.checked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = if (actions.shoppingMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                textDecoration = if (row.checked) TextDecoration.LineThrough else TextDecoration.None,
                color = if (row.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            row.allergens?.let { AllergenLine(it, row.checked) }
            row.store?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
            row.note?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        row.grade?.let { grade ->
            // Its own tap target, so the grade opens the facts behind it instead of checking the row.
            Box(Modifier.minimumInteractiveComponentSize()
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.product_details)) { actions.onProductInfo(row) }
                .alpha(if (row.checked) 0.5f else 1f), contentAlignment = Alignment.Center) {
                NutriScoreChip(grade)
            }
        }
        if (row.fromSuggestion && row.repeatEveryDays != null) {
            Icon(Icons.Outlined.Repeat, stringResource(R.string.repeat_added),
                tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 7.dp, end = 8.dp).size(18.dp))
        }
        if (row.quantity != null) {
            QuantityText(row.quantity)
        } else if (row.quantityHint != null && !row.checked && !actions.shoppingMode) {
            val hint = quantityText(row.quantityHint)
            val label = stringResource(R.string.quantity_hint_use, hint, row.name)
            TextButton(onClick = { actions.onHintRow?.invoke(row) ?: actions.onQuantityHint(row.id) },
                modifier = Modifier.semantics { contentDescription = label }) {
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "Contains milk" under the name, in amber with an icon, so it reads as a warning without relying on colour. */
@Composable
private fun AllergenLine(match: AllergenMatch, checked: Boolean) {
    val contains = allergenList(match.contains)
    val traces = allergenList(match.mayContain)
    val text = when {
        match.mayContain.isEmpty() -> stringResource(R.string.row_allergen_contains, contains)
        match.contains.isEmpty() -> stringResource(R.string.row_allergen_may_contain, traces)
        else -> stringResource(R.string.row_allergen_both, contains, traces)
    }
    // A bought row is dimmed like its name; the warning mattered before it went in the trolley.
    val color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else cautionColor(MaterialTheme.colorScheme.background.luminance() < 0.5f)
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.WarningAmber, null, tint = color, modifier = Modifier.padding(end = 4.dp).size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun QuantityText(quantity: Quantity) {
    val text = quantityText(quantity)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.background(MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun PartialRow(partial: String) {
    Text(
        text = partial,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 9.dp, bottom = 9.dp),
    )
}

/**
 * Done is done: everything in the cart collapses into one counted rule, and the
 * remaining list stays clean. Tapping opens it, for the moment when someone
 * asks whether the olives were already picked up.
 */
@Composable
private fun CheckedDivider(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val label = pluralStringResource(R.plurals.list_checked_count, count, count)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = stringResource(
                    if (expanded) R.string.list_checked_collapse else R.string.list_checked_expand,
                ),
                onClick = onClick,
            )
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp)
            .testTag(CheckedDividerTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.width(28.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** Voice, keyboard and barcode capture share a reachable bottom dock. */
@Composable
private fun CaptureBand(capture: CaptureState, actions: ListActions, autocomplete: List<ItemMemory>) {
    val listening = capture is CaptureState.Listening
    val typing = capture as? CaptureState.Typing
    val current by rememberUpdatedState(actions)
    val hold = Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            current.onCaptureStart()
            tryAwaitRelease()
            current.onCaptureEnd()
        })
    }
    Surface(color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(22.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(10.dp)) {
            val stacked = maxWidth < 308.dp || LocalDensity.current.fontScale > 1.3f
            if (typing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DraftField(typing, actions, autocomplete, Modifier.weight(1f))
                    CaptureScanButton(actions.onOpenScan)
                }
            } else if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CaptureTypeButton(actions.onType, Modifier.weight(1f))
                        CaptureScanButton(actions.onOpenScan)
                    }
                    CaptureVoiceButton(listening, hold, Modifier.fillMaxWidth())
                }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CaptureTypeButton(actions.onType, Modifier.weight(1f))
                CaptureVoiceButton(listening, hold, Modifier.weight(1f))
                CaptureScanButton(actions.onOpenScan)
            }
        }
    }
}

@Composable
private fun CaptureTypeButton(onType: () -> Unit, modifier: Modifier) {
    val label = stringResource(R.string.capture_type)
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(role = Role.Button, onClick = onType).semantics { contentDescription = label }
        .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Add, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f).padding(start = 6.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CaptureVoiceButton(listening: Boolean, hold: Modifier, modifier: Modifier) {
    val label = stringResource(if (listening) R.string.capture_listening else R.string.capture_hint)
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary).then(hold)
        .semantics { contentDescription = label }.testTag(CaptureBandTag)
        .heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Mic, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
        Text(label,
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.weight(1f).padding(start = 6.dp))
    }
}

@Composable
private fun CaptureScanButton(onScan: () -> Unit) {
    IconButton(onClick = onScan, modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
        Icon(Icons.Outlined.QrCodeScanner, stringResource(R.string.capture_scan))
    }
}

/**
 * The keyboard fallback. Same band, same place, no dialog: a phone with no
 * recogniser is not an error condition, it is a phone that types.
 */
@Composable
private fun DraftField(
    typing: CaptureState.Typing,
    actions: ListActions,
    choices: List<ItemMemory>,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(modifier = modifier) {
        if (typing.becauseSpeechIsUnavailable) {
            Text(
                text = stringResource(R.string.capture_speech_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                if (typing.draft.isEmpty()) {
                    Text(
                        text = stringResource(R.string.capture_write_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = typing.draft,
                    onValueChange = actions.onDraftChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { actions.onDraftSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag(DraftFieldTag),
                )
            }
            TextButton(
                onClick = actions.onDraftSubmit,
                enabled = typing.draft.isNotBlank(),
            ) {
                Text(
                    text = stringResource(R.string.capture_add),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (choices.isNotEmpty()) {
            Text(stringResource(R.string.memory_from_history), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(choices, key = { it.key }) { choice ->
                    HistoryCompletion(choice, actions)
                }
            }
        }

    }
}

@Composable
private fun HistoryCompletion(choice: ItemMemory, actions: ListActions) {
    var menuOpen by remember(choice.key) { mutableStateOf(false) }
    val addLabel = stringResource(R.string.autocomplete_add, choice.name)
    val muteLabel = stringResource(R.string.memory_mute_item, choice.name)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { actions.onAutocomplete(choice) },
                modifier = Modifier.semantics { contentDescription = addLabel }) { Text(choice.name) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.memory_suggestion_options, choice.name))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.memory_dont_suggest)) },
                        modifier = Modifier.semantics { contentDescription = muteLabel },
                        onClick = { menuOpen = false; actions.onMuteAutocomplete(choice) })
                }
            }
        }
    }
}

@Composable
private fun ListHistoryDialogs(state: ListUiState, actions: ListActions) {
    val prompt = state.prompt
    if (prompt != null) {
        AlertDialog(
            onDismissRequest = actions.onDismissPrompt,
            title = { Text(stringResource(if (prompt is ListPrompt.CarryOver) R.string.history_still_needed else R.string.finish_shop_question)) },
            text = { Text(when (prompt) {
                ListPrompt.AllChecked -> stringResource(R.string.finish_shop_all_checked)
                is ListPrompt.Stale -> pluralStringResource(R.plurals.history_stale_question, prompt.ids.size, prompt.ids.size)
                is ListPrompt.CarryOver -> stringResource(R.string.history_carry_over, prompt.names.joinToString(", "))
            }) },
            confirmButton = {
                TextButton(onClick = {
                    if (prompt is ListPrompt.CarryOver) actions.onDismissPrompt()
                    else actions.onFinishRows?.invoke(state.checked, (prompt as? ListPrompt.Stale)?.ids) ?: actions.onFinishShop((prompt as? ListPrompt.Stale)?.ids)
                }) { Text(stringResource(if (prompt is ListPrompt.CarryOver) R.string.history_keep else R.string.finish_shop)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (prompt is ListPrompt.CarryOver) actions.onRemoveItems(prompt.ids) else actions.onDismissPrompt()
                }) { Text(stringResource(when (prompt) {
                    is ListPrompt.CarryOver -> R.string.history_remove
                    is ListPrompt.Stale -> R.string.history_not_now
                    ListPrompt.AllChecked -> R.string.finish_shop_keep_open
                })) }
            },
        )
    }
    if (state.historyError) {
        AlertDialog(onDismissRequest = actions.onDismissHistoryError,
            text = { Text(stringResource(R.string.history_failed)) },
            confirmButton = { TextButton(onClick = actions.onDismissHistoryError) { Text(stringResource(R.string.history_dismiss)) } },
        )
    }
}

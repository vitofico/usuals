@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.theficos.usuals.ui.history

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.theficos.usuals.R
import io.theficos.usuals.ui.list.quantityText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var pastOpen by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(vm.today()) }
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())
    }
    val purchases = remember(state.history.purchases, state.query) {
        val matches = historyMatcher(state.query)
        state.history.purchases.filter { matches(it.name) }.groupBy { it.tripId }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val json = vm.exportJson()
                    requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
                vm.exported()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { vm.fileFailed() }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val text = requireNotNull(resolver.openInputStream(uri)).bufferedReader().use { it.readText() }
                    vm.importJson(text)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { vm.fileFailed() }
        }
    }
    LaunchedEffect(state.notice) { if (state.notice == HistoryNotice.IMPORTED) { pasted = ""; pastOpen = false } }
    if (pastOpen) ModalBottomSheet(onDismissRequest = { pastOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.history_past_title), style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(value = date, onValueChange = { date = it }, singleLine = true,
                label = { Text(stringResource(R.string.history_date_label)) }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pasted, onValueChange = { pasted = it }, minLines = 3, maxLines = 6,
                label = { Text(stringResource(R.string.history_paste_label)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.importPast(pasted, date) }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.history_import_past))
            }
            TextButton(onClick = { pastOpen = false }) { Text(stringResource(R.string.history_dismiss)) }
            if (state.notice == HistoryNotice.INVALID_DATE || state.notice == HistoryNotice.EMPTY_LIST) {
                Text(stringResource(if (state.notice == HistoryNotice.INVALID_DATE) R.string.history_invalid_date else R.string.history_empty_import),
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (infoOpen) AlertDialog(onDismissRequest = { infoOpen = false },
        title = { Text(stringResource(R.string.history_transfer_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.history_intro))
            Text(stringResource(R.string.history_transfer_hint))
        } },
        confirmButton = { TextButton(onClick = { infoOpen = false }) { Text(stringResource(R.string.shopping_close)) } })
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.history_back)) }
        }, actions = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.quiet_history_options)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_past_title)) }, onClick = { menuOpen = false; vm.dismissNotice(); pastOpen = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_export)) }, onClick = { menuOpen = false; export.launch("usuals-${vm.today()}.json") })
                    DropdownMenuItem(text = { Text(stringResource(R.string.history_import)) }, onClick = { menuOpen = false; importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.quiet_history_about)) }, onClick = { menuOpen = false; infoOpen = true })
                }
            }
        })
    }, bottomBar = {
        if (state.selected.isNotEmpty()) Surface(modifier = Modifier.imePadding(), color = MaterialTheme.colorScheme.surface) {
            Button(onClick = vm::rebuy, modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.history_add_selected, state.selected.size))
            }
        }
    }) { padding ->
        // No spacedBy: a trip's purchases are items of their own, drawn as one card, so the gaps are explicit.
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("history_list").padding(padding).padding(horizontal = 20.dp),
        ) {
            item {
                OutlinedTextField(value = state.query, onValueChange = vm::search, singleLine = true,
                    label = { Text(stringResource(R.string.history_search)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.history_rebuy_review), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            }
            state.notice?.let { notice ->
                item {
                    Spacer(Modifier.height(12.dp))
                    Text(if (notice == HistoryNotice.REBOUGHT) stringResource(R.string.history_rebuy_done, state.added) else stringResource(when (notice) {
                        HistoryNotice.REBOUGHT -> R.string.history_rebuy_done
                        HistoryNotice.IMPORTED -> R.string.history_imported
                        HistoryNotice.EXPORTED -> R.string.history_exported
                        HistoryNotice.INVALID_FILE -> R.string.history_invalid_file
                        HistoryNotice.INVALID_DATE -> R.string.history_invalid_date
                        HistoryNotice.EMPTY_LIST -> R.string.history_empty_import
                        HistoryNotice.FAILED -> R.string.history_failed
                    }, stringResource(R.string.app_name)))
                    TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.history_dismiss)) }
                }
            }
            if (state.history.trips.isEmpty()) {
                item { Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.history_empty)) }
            }
            if (state.query.isNotBlank() && purchases.isEmpty()) item { Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.history_no_results)) }
            val zone = ZoneId.systemDefault()
            for (trip in state.history.trips) {
                val rows = purchases[trip.id] ?: continue
                val day = Instant.ofEpochMilli(trip.closedAt).atZone(zone).toLocalDate()
                item(key = "trip:${trip.id}") {
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth().tripSlice(first = true, last = false).padding(start = 20.dp, end = 20.dp, top = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(formatter.format(Instant.ofEpochMilli(trip.closedAt)), style = MaterialTheme.typography.titleMedium)
                        Text(pluralStringResource(R.plurals.history_item_count, rows.size, rows.size),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        state.baskets[trip.id]?.let { io.theficos.usuals.ui.basket.BasketTripSummary(it) }
                        TextButton(onClick = { vm.selectTrip(trip.id) }) { Text(stringResource(R.string.history_select_trip)) }
                    }
                }
                itemsIndexed(rows, key = { _, purchase -> "purchase:${trip.id}:${purchase.id.value}" }) { index, purchase ->
                    val last = index == rows.lastIndex
                    Column(Modifier.fillMaxWidth().tripSlice(first = false, last = last).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = if (last) 20.dp else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val quantity = purchase.quantity?.let { quantityText(it) }.orEmpty()
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(checked = purchase.id.value in state.selected,
                                onCheckedChange = { vm.select(purchase.id.value) },
                                modifier = Modifier.semantics { contentDescription = purchase.name })
                            Text(stringResource(R.string.history_purchase, purchase.name, quantity).trim())
                        }
                        val bought = Instant.ofEpochMilli(purchase.boughtAt)
                        if (bought.atZone(zone).toLocalDate() != day) {
                            Text(formatter.format(bought), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One slice of a trip's card. A trip is a header and one lazy item per
 * purchase, so the card is drawn in pieces: the whole card's outline, cut where
 * a slice meets its neighbours.
 */
@Composable
private fun Modifier.tripSlice(first: Boolean, last: Boolean): Modifier {
    val shape = MaterialTheme.shapes.large
    val fill = MaterialTheme.colorScheme.surface
    val line = MaterialTheme.colorScheme.outlineVariant
    return clipToBounds().drawBehind {
        val stroke = 1.dp.toPx()
        // Past the corner radius, so an open edge shows neither its corners nor its outline.
        val overhang = size.width
        val top = if (first) stroke / 2 else -overhang
        val bottom = if (last) size.height - stroke / 2 else size.height + overhang
        val outline = shape.createOutline(Size(size.width - stroke, bottom - top), layoutDirection, this)
        translate(left = stroke / 2, top = top) {
            drawOutline(outline, fill)
            drawOutline(outline, line, style = Stroke(stroke))
        }
    }
}

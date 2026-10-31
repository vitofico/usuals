@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.usuals.ui.history

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.theficos.usuals.R
import kotlinx.coroutines.*

@Composable
fun ReceiptSheet(editor: ReceiptEditor) {
    val draft by editor.state.collectAsState()
    val current = draft ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var correctingLine by remember(current.id) { mutableStateOf<Int?>(null) }
    var viewingPhoto by remember(current.id) { mutableStateOf(false) }
    var capture by rememberSaveable(current.id) { mutableStateOf<String?>(null) }
    var photoRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var exportRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraRefused by rememberSaveable { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        capture?.let { uri -> if (success && draft?.id == photoRequest) editor.photo(uri) else editor.releaseCamera(uri) }
        capture = null
        photoRequest = null
    }
    fun takePhoto() {
        editor.cameraUri()?.let { uri ->
            capture = uri
            photoRequest = current.id
            try { camera.launch(Uri.parse(uri)) } catch (_: Exception) { editor.releaseCamera(uri); capture = null; editor.failed() }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && draft?.id == photoRequest) takePhoto()
        else if (!granted) { cameraRefused = true; editor.failed() }
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && draft?.id == photoRequest) editor.photo(uri.toString())
        photoRequest = null
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val image = draft?.takeIf { it.id == exportRequest }?.image
        exportRequest = null
        if (uri != null && image != null) scope.launch {
            try { withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(image.bytes()) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { editor.failed(ReceiptProblem.SAVE) }
        }
    }
    correctingLine?.let { index ->
        val lines = current.text?.lines().orEmpty()
        if (index in lines.indices) current.image?.let { image ->
            ReceiptLineCorrection(image, lines[index], { correctingLine = null }) { value ->
                editor.text(lines.mapIndexed { i, line -> if (i == index) value else line }.joinToString("\n"))
                correctingLine = null
            }
        }
    }
    val saving by rememberUpdatedState(current.saving)
    ModalBottomSheet(onDismissRequest = editor::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !saving || it != SheetValue.Hidden })) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row {
                Text(stringResource(R.string.receipt_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = editor::close, enabled = !current.saving) { Text(stringResource(R.string.recipe_close)) }
            }
            Text(stringResource(R.string.receipt_local), style = MaterialTheme.typography.bodyMedium)
            current.image?.let { image ->
                val bitmap = remember(image) { image.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
                bitmap?.let {
                    if (viewingPhoto) ReceiptPhotoViewer(it) { viewingPhoto = false }
                    Box(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.receipt_view)) { viewingPhoto = true }.testTag("receipt_preview")) {
                        Image(it.asImageBitmap(), stringResource(R.string.receipt_preview), Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit)
                        Icon(Icons.Outlined.ZoomIn, null, Modifier.align(Alignment.BottomEnd).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small).padding(8.dp))
                    }
                }
            }
            if (!current.busy) {
                OutlinedButton(onClick = {
                    photoRequest = current.id
                    try { choose.launch(arrayOf("image/*")) } catch (_: Exception) { editor.failed() }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recipe_choose_photo)) }
                OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) takePhoto()
                    else if (cameraRefused) editor.failed()
                    else { photoRequest = current.id; permission.launch(Manifest.permission.CAMERA) }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recipe_take_photo)) }
            }
            if (current.trip == null) {
                OutlinedTextField(current.date, editor::date, enabled = !current.busy, singleLine = true,
                    label = { Text(stringResource(R.string.history_date_label)) }, modifier = Modifier.fillMaxWidth().testTag("receipt_date"))
                if (current.image != null && current.text == null) {
                    Text(stringResource(R.string.receipt_model_privacy), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = editor::read, enabled = !current.busy, modifier = Modifier.fillMaxWidth().testTag("receipt_read")) { Text(stringResource(R.string.receipt_read)) }
                    TextButton(onClick = { editor.text("") }, enabled = !current.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.receipt_manual)) }
                }
                current.text?.let { text ->
                    Text(stringResource(R.string.receipt_review_hint))
                    Text(stringResource(R.string.receipt_compare))
                    text.lines().forEachIndexed { index, line ->
                        if (line.isNotBlank()) TextButton(onClick = { correctingLine = index }, enabled = !current.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(line) }
                    }
                    OutlinedTextField(text, editor::text, enabled = !current.busy, minLines = 3, maxLines = 8,
                        label = { Text(stringResource(R.string.receipt_items)) }, modifier = Modifier.fillMaxWidth().testTag("receipt_items"))
                }
            }
            if (current.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("receipt_busy"))
            current.problem?.let { problem -> Text(stringResource(when (problem) {
                ReceiptProblem.NOT_CONFIGURED -> R.string.recipe_model_missing
                ReceiptProblem.NO_IMAGES -> R.string.recipe_no_images
                ReceiptProblem.BUDGET -> R.string.model_problem_budget
                ReceiptProblem.EMPTY -> R.string.receipt_empty
                ReceiptProblem.DATE -> R.string.history_invalid_date
                ReceiptProblem.SCOPE -> R.string.receipt_scope_changed
                ReceiptProblem.MODEL -> R.string.receipt_model_failed
                ReceiptProblem.READ -> R.string.recipe_file_failed
                ReceiptProblem.SAVE -> R.string.receipt_save_failed
            }), color = MaterialTheme.colorScheme.error) }
            if (current.image != null) {
                if (!current.existing || current.trip == null) Button(onClick = editor::save,
                    enabled = !current.busy && (current.trip != null || !current.text.isNullOrBlank()),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("receipt_save")) {
                    Text(stringResource(if (current.trip == null) R.string.receipt_save_purchases else R.string.receipt_save_photo))
                }
                TextButton(onClick = {
                    exportRequest = current.id
                    try { export.launch("usuals-receipt.jpg") } catch (_: Exception) { editor.failed(ReceiptProblem.SAVE) }
                }, enabled = !current.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.receipt_export)) }
            }
            if (current.existing) TextButton(onClick = editor::remove, enabled = !current.busy, modifier = Modifier.fillMaxWidth().testTag("receipt_remove")) { Text(stringResource(R.string.receipt_remove)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ReceiptLineCorrection(image: io.theficos.usuals.llm.ModelImage, line: String,
    onDismiss: () -> Unit, save: (String) -> Unit) {
    var text by remember(line) { mutableStateOf(line) }
    var fullImage by remember { mutableStateOf(false) }
    val bitmap = remember(image) { image.bytes().let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    if (fullImage && bitmap != null) ReceiptPhotoViewer(bitmap) { fullImage = false }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.receipt_correct_line)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            bitmap?.let { original ->
                Image(original.asImageBitmap(), stringResource(R.string.receipt_preview),
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).clickable(onClickLabel = stringResource(R.string.receipt_view)) { fullImage = true }, contentScale = ContentScale.Fit)
                TextButton(onClick = { fullImage = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.receipt_view)) }
            }
            OutlinedTextField(text, { text = it.replace("\n", " ") }, label = { Text(stringResource(R.string.receipt_items)) }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { save(text) }) { Text(stringResource(R.string.pack_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pack_cancel)) } })
}

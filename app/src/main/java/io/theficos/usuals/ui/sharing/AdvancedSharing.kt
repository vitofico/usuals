package io.theficos.usuals.ui.sharing

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.theficos.usuals.R
import io.theficos.usuals.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** Every rare action, collapsed by default. Its file pickers stay registered while it is collapsed. */
@Composable
internal fun AdvancedSharing(state: SharingState, vm: SharingViewModel, stage: SharingStage, onScan: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by rememberSaveable { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    fun read(uri: Uri?, action: (ByteArray) -> Unit) {
        if (uri == null) return
        scope.launch(Dispatchers.IO) { runCatching { readLimited(context, uri) }.onSuccess(action).onFailure { vm.fileFailure() } }
    }
    fun write(uri: Uri?, bytes: ByteArray?, after: () -> Unit = {}) {
        if (uri == null || bytes == null) return after()
        scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("unavailable") }.onFailure { vm.fileFailure() }
            after()
        }
    }
    val requestKit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::prepareBackupRequest) }
    val saveRequest = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { write(it, state.backupRequest, vm::clearBackupRequest) }
    LaunchedEffect(state.backupRequest) { if (state.backupRequest != null) saveRequest.launch("usuals-backup-request.json") }
    val frozenFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.selectFrozen(it.toString()) } }
    val frozenKit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::frozenKit) }
    val pendingExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { write(it, state.pendingExport, vm::clearPendingExport) }
    LaunchedEffect(state.pendingExport) { if (state.pendingExport != null) pendingExport.launch("usuals-pending-work.json") }
    val saveTransfer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.usuals.transfer+json")) { write(it, state.transferFile) }
    val reviewTransfer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::reviewTransfer) }
    val finishTransfer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::completeTransfer) }
    val recover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::recover) }

    if (state.problem == SharingProblem.LOCKED_KEYS) OutlinedButton(enabled = !state.busy, onClick = { resetting = true }) { Text(stringResource(R.string.sharing_reset_keys)) }
    if (state.frozenUri != null) SectionCard {
        Text(stringResource(R.string.restore_title), style = MaterialTheme.typography.titleLarge)
        val preview = state.frozenCopy
        if (preview == null) {
            Text(stringResource(R.string.restore_key_help))
            SharingField(state.recoveryKey, R.string.sharing_recovery_key, state.busy, secret = true) { vm.field(SharingField.RECOVERY, it) }
            OutlinedButton(enabled = !state.busy && state.recoveryKey.isNotBlank(), onClick = { frozenKit.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.restore_open_kit)) }
        } else {
            Text(preview.name.ifBlank { stringResource(R.string.household_fallback_name) })
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.restore_items, preview.items, preview.items))
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.restore_purchases, preview.purchases, preview.purchases))
            Text(stringResource(R.string.restore_copy_help))
            if (preview.older) Text(stringResource(R.string.restore_older), color = MaterialTheme.colorScheme.error)
            if (preview.ancestryUnverifiable) Text(stringResource(R.string.restore_ancestry), color = MaterialTheme.colorScheme.error)
            Button(enabled = !state.busy, onClick = { vm.saveFrozenCopy(copyName(context, preview.name, state.lists)) }) { Text(stringResource(R.string.restore_save)) }
        }
        TextButton(enabled = !state.busy, onClick = vm::closeFrozen) { Text(stringResource(R.string.sharing_cancel)) }
    }
    TextButton(onClick = { open = !open }) { Text(stringResource(R.string.sharing_advanced)) }
    if (open) {
        state.households.filter { it.role == "owner" && !it.removed }.forEach { household -> SectionCard {
            Text(stringResource(R.string.sharing_household, household.id.takeLast(6)), style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = !state.busy, onClick = { vm.rotate(household.id) }) { Text(stringResource(R.string.sharing_rotate)) }
            TextButton(enabled = !state.busy, onClick = { vm.prepareReplacement(household.id) }) { Text(stringResource(R.string.sharing_replace_kit)) }
            TextButton(enabled = !state.busy, onClick = { finishTransfer.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.sharing_finish_transfer)) }
        } }
        if (stage == SharingStage.READY && invitedOrigin(state.invitation) == null) JoinCard(state, vm, onScan = onScan)
        if (stage == SharingStage.READY && state.connection == null) SectionCard {
            Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.settings_server_help))
            ServerAddress(state, vm, R.string.connection_connect, vm::connectServer, onScan)
        }
        if (stage == SharingStage.START && state.connection == null) SectionCard {
            Text(stringResource(R.string.sharing_manual_title), style = MaterialTheme.typography.titleLarge)
            SharingField(state.origin, R.string.sharing_origin, state.busy) { vm.field(SharingField.ORIGIN, it) }
            SharingField(state.setupCode, R.string.sharing_setup_code, state.busy, secret = true) { vm.field(SharingField.SETUP, it) }
            Button(enabled = !state.busy && state.origin.isNotBlank() && state.setupCode.isNotBlank(), onClick = vm::prepare) { Text(stringResource(R.string.sharing_prepare)) }
        }
        SectionCard {
            Text(stringResource(R.string.sharing_recover_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.sharing_recover_help))
            SharingField(state.recoveryKey, R.string.sharing_recovery_key, state.busy, secret = true) { vm.field(SharingField.RECOVERY, it) }
            OutlinedButton(enabled = !state.busy && state.recoveryKey.isNotBlank(), onClick = { recover.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.sharing_open_recovery)) }
            Text(stringResource(R.string.restore_request_help), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !state.busy && state.recoveryKey.isNotBlank(), onClick = { requestKit.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.restore_request)) }
            state.backupFingerprint?.let { fingerprint -> SelectionContainer { Text(stringResource(R.string.restore_fingerprint, fingerprint), style = MaterialTheme.typography.bodySmall) } }
        }
        OutlinedButton(enabled = !state.busy, onClick = { reviewTransfer.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.sharing_review_transfer)) }
        OutlinedButton(enabled = !state.busy, onClick = { frozenFile.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.restore_open)) }
        OutlinedButton(enabled = !state.busy, onClick = vm::preparePendingExport) { Text(stringResource(R.string.sharing_export_pending)) }
        TextButton(enabled = !state.busy, onClick = { resetting = true }) { Text(stringResource(R.string.sharing_reset_keys)) }
        if (state.connection != null) TextButton(enabled = !state.busy, onClick = vm::disconnectServer) { Text(stringResource(R.string.connection_disconnect)) }
    }
    if (resetting) AlertDialog(onDismissRequest = { resetting = false }, title = { Text(stringResource(R.string.sharing_reset_keys)) },
        text = { Text(stringResource(R.string.sharing_reset_keys_help)) },
        confirmButton = { TextButton(onClick = { resetting = false; vm.resetUnavailable() }) { Text(stringResource(R.string.sharing_reset_keys)) } },
        dismissButton = { TextButton(onClick = { resetting = false }) { Text(stringResource(R.string.sharing_cancel)) } })
    if (state.transferReview != null) AlertDialog(onDismissRequest = vm::closeTransfer, title = { Text(stringResource(R.string.sharing_transfer)) },
        text = { Column { Text(stringResource(R.string.sharing_accept_help)); state.problem?.let { Text(sharingProblem(it), color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(enabled = !state.busy, onClick = vm::acceptTransfer) { Text(stringResource(R.string.sharing_accept_transfer)) } },
        dismissButton = { TextButton(onClick = vm::closeTransfer) { Text(stringResource(R.string.sharing_cancel)) } })
    if (state.transferFile != null) AlertDialog(onDismissRequest = vm::closeTransfer, title = { Text(stringResource(R.string.sharing_transfer)) },
        text = { Text(stringResource(R.string.sharing_transfer_file_help)) },
        confirmButton = { TextButton(onClick = { saveTransfer.launch("usuals-transfer.json") }) { Text(stringResource(R.string.sharing_save_transfer)) } },
        dismissButton = { TextButton(onClick = vm::closeTransfer) { Text(stringResource(R.string.history_dismiss)) } })
}

/** A picked file, refused past 8 MiB so a wrong pick cannot exhaust memory. */
internal fun readLimited(context: Context, uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
    val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val n = input.read(buffer); if (n < 0) break; require(bytes.size() + n <= 8 * 1024 * 1024); bytes.write(buffer, 0, n) }
    bytes.toByteArray()
} ?: error("unavailable")

internal fun copyName(context: Context, base: String, lists: List<SharingList>): String {
    var number = 1
    while (true) {
        val name = context.getString(R.string.sharing_local_copy_name, number++, base.ifBlank { context.getString(R.string.household_fallback_name) }).take(100)
        if (lists.none { it.name == name }) return name
    }
}

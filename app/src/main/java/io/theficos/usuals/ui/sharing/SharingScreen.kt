package io.theficos.usuals.ui.sharing

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.first
import io.theficos.usuals.R
import io.theficos.usuals.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.theficos.usuals.sync.InvitationLink
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingScreen(vm: SharingViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, invitation: String? = null, onInvitationConsumed: () -> Unit = {},
    sourceList: String? = null, onSourceConsumed: () -> Unit = {}, connectionOnly: Boolean = false) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? android.app.Activity)?.window
        val wasSecure = window?.attributes?.flags?.and(android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val scope = rememberCoroutineScope()
    var showKey by remember { mutableStateOf(false) }
    var showInviteQr by remember { mutableStateOf(false) }
    var removingCopy by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf<String?>(null) }
    var memberHousehold by remember { mutableStateOf<String?>(null) }
    var confirmRemoval by remember { mutableStateOf<Pair<String, String>?>(null) }
    var history by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf<SharingScan?>(null) }
    var pasteInvitation by remember { mutableStateOf(false) }
    var replacingJoin by remember(state.joining?.assertion?.requestId, state.invitation) { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(vm, sourceList) {
        if (sourceList != null) {
            vm.state.first { !it.busy }
            vm.rememberShareSource(sourceList)
            // Keep navigation's saved source until the encrypted store has committed it.
            if (vm.state.first { !it.busy }.problem == null) onSourceConsumed()
        }
    }
    val shareOwners = state.households.filter { it.role == "owner" && !it.removed }
    LaunchedEffect(state.sharingSource, state.busy, state.kit, shareOwners.map { it.id }) {
        if (!connectionOnly && !state.busy && state.sharingSource != null && state.kit == null && shareOwners.size == 1) {
            sharing = shareOwners.single().id
            history = state.shareAttempts.firstOrNull { it.household == sharing && it.source == state.sharingSource }?.includeHistory ?: false
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle, state.joining?.assertion?.requestId) {
        val expiresAt = state.joining?.invitation?.expiresAt ?: return@LaunchedEffect
        if (state.joining != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do { delay(5000); vm.checkJoin() } while (isActive && System.currentTimeMillis() < expiresAt)
        }
    }
    LaunchedEffect(invitation) { if (invitation != null) { vm.state.first { !it.busy }; if (vm.scanned(invitation, serverOnly = connectionOnly)) onInvitationConsumed() } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.usuals.recovery+json")) { uri ->
        if (uri != null) state.kit?.let { kit -> scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(kit.file) } ?: error("unavailable") }
                .onSuccess { vm.markSaved() }.onFailure { vm.fileFailure() }
        } }
    }
    fun read(uri: android.net.Uri?, action: (ByteArray) -> Unit) {
        if (uri == null) return
        scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(bytes.size() + n <= 8 * 1024 * 1024); bytes.write(buffer, 0, n) }
                bytes.toByteArray()
            } ?: error("unavailable") }.onSuccess(action).onFailure { vm.fileFailure() }
        }
    }
    val requestKit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::prepareBackupRequest) }
    val saveRequest = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = state.backupRequest
        if (uri != null && bytes != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("unavailable") }.onFailure { vm.fileFailure() }
            vm.clearBackupRequest()
        } else vm.clearBackupRequest()
    }
    LaunchedEffect(state.backupRequest) { if (state.backupRequest != null) saveRequest.launch("usuals-backup-request.json") }
    val frozenFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.selectFrozen(it.toString()) } }
    val frozenKit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::frozenKit) }
    val verify = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::verifyFile) }
    val pendingExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = state.pendingExport
        if (uri != null && bytes != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("unavailable") }
                .onFailure { vm.fileFailure() }
            vm.clearPendingExport()
        } else vm.clearPendingExport()
    }
    LaunchedEffect(state.pendingExport) { if (state.pendingExport != null) pendingExport.launch("usuals-pending-work.json") }
    val saveTransfer = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.usuals.transfer+json")) { uri ->
        if (uri != null) state.transferFile?.let { bytes -> scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("unavailable") }
                .onFailure { vm.fileFailure() }
        } }
    }
    val reviewTransfer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::reviewTransfer) }
    val finishTransfer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::completeTransfer) }
    val recover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { read(it, vm::recover) }
    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text(stringResource(if (connectionOnly) R.string.settings_server else R.string.sharing_title), style = MaterialTheme.typography.headlineSmall) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) }
        })
    }, bottomBar = {
        if (state.busy || state.problem != null || state.message != null) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.problem?.let { Text(sharingProblem(it), color = MaterialTheme.colorScheme.error) }
                state.message?.let { Text(sharingMessage(it), color = MaterialTheme.colorScheme.primary) }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ConnectionPanel(state, vm, showHouseholdSetup = !connectionOnly) { scanning = SharingScan.JOIN }
            if (!connectionOnly) {
            state.lists.firstOrNull { it.id == state.sharingSource }?.let { source ->
                Text(stringResource(R.string.connection_share_source, source.name.ifBlank { context.getString(R.string.household_fallback_name) }))
                TextButton(enabled = !state.busy, onClick = { vm.rememberShareSource(null) }) { Text(stringResource(R.string.sharing_cancel)) }
            }
            Text(stringResource(R.string.sharing_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.joining != null) SectionCard {
                val pending = requireNotNull(state.joining)
                Text(stringResource(R.string.sharing_pair_verify_title), style = MaterialTheme.typography.titleLarge)
                Text(pending.invitation.origin, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.connection_compare))
                var showVerificationQr by remember(pending.assertion.requestId) { mutableStateOf(false) }
                TextButton(onClick = { showVerificationQr = !showVerificationQr }) { Text(stringResource(R.string.sharing_reverse_qr)) }
                if (showVerificationQr) QrCode(vm.reverseQr(pending), R.string.sharing_reverse_qr)
                val fp = vm.fingerprint(pending)
                SelectionContainer { Text(fp.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.sharing_pair_waiting), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !state.busy, onClick = vm::retryJoin) { Text(stringResource(R.string.sharing_retry_request)) }
                TextButton(enabled = !state.busy, onClick = vm::checkJoin) { Text(stringResource(R.string.sharing_check_approval)) }
            }
            if (state.kit == null) SectionCard {
                Text(stringResource(R.string.sharing_pair_title), style = MaterialTheme.typography.titleLarge)
                val origin = remember(state.invitation) { runCatching { InvitationLink.parse(state.invitation.trim()).origin }.getOrNull() }
                if (origin == null) Text(stringResource(R.string.sharing_pair_help))
                else {
                    Text(stringResource(R.string.sharing_pair_server, origin), style = MaterialTheme.typography.bodyMedium)
                    Button(enabled = !state.busy, onClick = {
                        if (state.joining != null) replacingJoin = true else vm.join()
                    }) { Text(stringResource(R.string.sharing_pair_connect)) }
                }
                TextButton(enabled = !state.busy, onClick = { pasteInvitation = !pasteInvitation }) { Text(stringResource(R.string.sharing_pair_paste)) }
                if (pasteInvitation) {
                    SharingField(state.invitation, R.string.sharing_invitation, state.busy, secret = true) { vm.field(SharingField.INVITATION, it) }
                }
            }
            OutlinedButton(enabled = !state.busy, onClick = vm::preparePendingExport) { Text(stringResource(R.string.sharing_export_pending)) }
            OutlinedButton(enabled = !state.busy, onClick = { frozenFile.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.restore_open)) }
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
            if (state.problem == SharingProblem.LOCKED_KEYS) OutlinedButton(enabled = !state.busy, onClick = { resetting = true }) { Text(stringResource(R.string.sharing_reset_keys)) }
            if (state.bootstrapPending) OutlinedButton(enabled = !state.busy, onClick = vm::resumeBootstrap) { Text(stringResource(R.string.sharing_retry_creation)) }
            state.households.forEach { household -> SectionCard {
                Text(stringResource(R.string.sharing_household, household.id.takeLast(6)), style = MaterialTheme.typography.titleLarge)
                Text(household.origin, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (household.role == "owner") R.string.sharing_owner else R.string.sharing_editor))
                if (household.removed) Text(stringResource(R.string.sharing_access))
                TextButton(enabled = !state.busy, onClick = { memberHousehold = household.id }) { Text(stringResource(R.string.sharing_members)) }
                if (household.role == "owner" && !household.removed) {
                    TextButton(enabled = !state.busy, onClick = {
                        sharing = household.id
                        history = state.shareAttempts.firstOrNull { it.household == household.id && it.source == state.sharingSource }?.includeHistory ?: false
                    }) { Text(stringResource(R.string.sharing_share_list)) }
                    TextButton(enabled = !state.busy, onClick = { vm.rotate(household.id) }) { Text(stringResource(R.string.sharing_rotate)) }
                    TextButton(enabled = !state.busy, onClick = { finishTransfer.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.sharing_finish_transfer)) }
                    state.lists.filter { it.shared && it.household == household.id && it.id !in household.lists }.forEach { list ->
                        TextButton(enabled = !state.busy, onClick = { vm.retryShare(household.id, list.id) }) { Text(stringResource(R.string.sharing_retry_list, list.name)) }
                    }
                    TextButton(enabled = !state.busy, onClick = { showInviteQr = false; vm.invite(household.id) }) { Text(stringResource(R.string.sharing_invite)) }
                    TextButton(enabled = !state.busy, onClick = { vm.prepareReplacement(household.id) }) { Text(stringResource(R.string.sharing_replace_kit)) }
                    TextButton(enabled = !state.busy, onClick = { vm.pending(household.id) }) { Text(stringResource(R.string.sharing_review_requests)) }
                }
                if (household.pending) TextButton(enabled = !state.busy, onClick = { vm.discardInvalid(household.id) }) { Text(stringResource(R.string.sharing_discard_invalid)) }
                if (household.pending) TextButton(enabled = !state.busy, onClick = { vm.resume(household.id) }) { Text(stringResource(R.string.sharing_resume)) }
            } }
                state.lists.filter { it.shared && it.accessRemoved }.forEach { list ->
                    Text(stringResource(R.string.sharing_unavailable_list, list.name.ifBlank { context.getString(R.string.household_fallback_name) }))
                    Text(stringResource(R.string.sharing_retained_help), style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !state.busy, onClick = {
                        vm.retain(list.id, copyName(context, list.name, state.lists))
                    }) { Text(stringResource(R.string.sharing_retain_copy)) }
                    TextButton(enabled = !state.busy, onClick = { removingCopy = list.id }) { Text(stringResource(R.string.sharing_remove_copy)) }
                }
            OutlinedButton(enabled = !state.busy, onClick = { reviewTransfer.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.sharing_review_transfer)) }
            if (state.kit != null) SectionCard {
                val kit = requireNotNull(state.kit)
                Text(stringResource(R.string.sharing_kit_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.sharing_kit_help))
                if (showKey) {
                    SelectionContainer { Text(kit.secret, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { showKey = false }) { Text(stringResource(R.string.sharing_hide_key)) }
                    TextButton(onClick = { (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(
                        android.content.ClipData.newPlainText(context.getString(R.string.sharing_recovery_key), kit.secret).apply {
                            description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                        }) }) { Text(stringResource(R.string.sharing_copy_key)) }
                } else TextButton(onClick = { showKey = true }) { Text(stringResource(R.string.sharing_show_key)) }
                OutlinedButton(enabled = !state.busy, onClick = { save.launch("usuals-recovery.json") }) { Text(stringResource(R.string.sharing_save_kit)) }
                if (state.saved) {
                    Text(stringResource(R.string.sharing_verify_help))
                    SharingField(state.recoveryKey, R.string.sharing_recovery_key, state.busy, secret = true) { vm.field(SharingField.RECOVERY, it) }
                    OutlinedButton(enabled = !state.busy && state.recoveryKey.isNotBlank(), onClick = { verify.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.sharing_verify_file)) }
                }
                Button(enabled = !state.busy && state.verified, onClick = { showKey = false; vm.create() }) { Text(stringResource(if (state.kit is io.theficos.usuals.sync.ReplacementKit) R.string.sharing_replace_kit else R.string.sharing_create)) }
                TextButton(onClick = { showKey = false; vm.cancelSetup() }) { Text(stringResource(R.string.sharing_cancel)) }
            } else {
                SectionCard {
                    Text(stringResource(R.string.sharing_create_title), style = MaterialTheme.typography.titleLarge)
                    if (state.connection == null) {
                        SharingField(state.origin, R.string.sharing_origin, state.busy) { vm.field(SharingField.ORIGIN, it) }
                        SharingField(state.setupCode, R.string.sharing_setup_code, state.busy, secret = true) { vm.field(SharingField.SETUP, it) }
                    }
                    Button(enabled = !state.busy && state.origin.isNotBlank() && (state.connection != null || state.setupCode.isNotBlank()), onClick = vm::prepare) { Text(stringResource(R.string.sharing_prepare)) }
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
            }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    state.outgoing?.let { invite -> AlertDialog(onDismissRequest = vm::closeInvitation,
        title = { Text(stringResource(R.string.sharing_invite)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.sharing_invite_help))
            if (showInviteQr) QrCode(invite.uri(), R.string.sharing_qr)
            else TextButton(onClick = { showInviteQr = true }) { Text(stringResource(R.string.sharing_show_qr)) }
            TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, invite.uri()) }, null)) }) { Text(stringResource(R.string.sharing_share_invite)) }
        } }, confirmButton = { TextButton(onClick = vm::closeInvitation) { Text(stringResource(R.string.history_dismiss)) } }) }
    state.reviewing?.let { AlertDialog(onDismissRequest = vm::closeReview,
        title = { Text(stringResource(R.string.sharing_requests_title)) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.sharing_requests_help))
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.problem?.let { Text(sharingProblem(it), color = MaterialTheme.colorScheme.error) }
            if (state.requests.isEmpty()) Text(stringResource(R.string.sharing_no_requests))
            state.requests.firstOrNull()?.let { pending ->
                val code = remember(pending) { runCatching { vm.applicantFingerprint(pending) }.getOrNull() }
                var compared by remember(pending) { mutableStateOf(false) }
                Text(code.orEmpty().chunked(8).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                Row { Checkbox(compared, { compared = it }); Text(stringResource(R.string.connection_compare)) }
                TextButton(enabled = !state.busy, onClick = { scanning = SharingScan.VERIFY }) { Text(stringResource(R.string.sharing_scan_verification)) }
                Button(enabled = !state.busy && compared && code != null, onClick = { vm.field(SharingField.FINGERPRINT, requireNotNull(code)); vm.approve(pending) }) { Text(stringResource(R.string.sharing_approve)) }
                TextButton(enabled = !state.busy, onClick = { vm.deny(pending) }) { Text(stringResource(R.string.sharing_deny)) }
            }
        } }, confirmButton = { TextButton(onClick = vm::closeReview) { Text(stringResource(R.string.history_dismiss)) } }) }
    if (replacingJoin) AlertDialog(onDismissRequest = { replacingJoin = false }, title = { Text(stringResource(R.string.sharing_replace_request_title)) },
        text = { Text(stringResource(R.string.sharing_replace_request_help)) },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { replacingJoin = false; vm.join() }) { Text(stringResource(R.string.sharing_replace_request)) } },
        dismissButton = { TextButton(onClick = { replacingJoin = false }) { Text(stringResource(R.string.sharing_cancel)) } })
    if (removingCopy != null) AlertDialog(onDismissRequest = { removingCopy = null }, title = { Text(stringResource(R.string.sharing_remove_copy)) },
        text = { Text(stringResource(R.string.sharing_remove_copy_help)) },
        confirmButton = { TextButton(onClick = { val id = requireNotNull(removingCopy); removingCopy = null; vm.removeCopy(id) }) { Text(stringResource(R.string.sharing_remove_copy)) } },
        dismissButton = { TextButton(onClick = { removingCopy = null }) { Text(stringResource(R.string.sharing_cancel)) } })
    if (resetting) AlertDialog(onDismissRequest = { resetting = false }, title = { Text(stringResource(R.string.sharing_reset_keys)) },
        text = { Text(stringResource(R.string.sharing_reset_keys_help)) },
        confirmButton = { TextButton(onClick = { resetting = false; vm.resetUnavailable() }) { Text(stringResource(R.string.sharing_reset_keys)) } },
        dismissButton = { TextButton(onClick = { resetting = false }) { Text(stringResource(R.string.sharing_cancel)) } })
    sharing?.let { household -> AlertDialog(onDismissRequest = { sharing = null; vm.rememberShareSource(null) }, title = { Text(stringResource(R.string.sharing_share_list)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.sharing_preview))
            val pendingShare = state.shareAttempts.firstOrNull { it.household == household && it.source == state.sharingSource }
            Row { Checkbox(history, { history = it }, enabled = pendingShare == null); Text(stringResource(R.string.sharing_include_history)) }
            if (pendingShare != null) Text(stringResource(R.string.connection_retry_copy))
            state.lists.filter { list -> !list.shared && (state.sharingSource == null || state.sharingSource == list.id) &&
                (state.sharingSource != null || state.shareAttempts.none { it.household == household && it.source == list.id }) }.forEach { list ->
                TextButton(enabled = !state.busy, onClick = { sharing = null; vm.share(household, list.id, history) }) { Text(list.name.ifBlank { context.getString(R.string.household_fallback_name) }) }
            }
            state.problem?.let { Text(sharingProblem(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { sharing = null; vm.rememberShareSource(null) }) { Text(stringResource(R.string.sharing_cancel)) } }) }
    memberHousehold?.let { household -> AlertDialog(onDismissRequest = { memberHousehold = null }, title = { Text(stringResource(R.string.sharing_members)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            val owner = state.households.firstOrNull { it.id == household }?.role == "owner"
            state.members[household].orEmpty().forEach { member ->
                Text(stringResource(R.string.sharing_member, member.id.takeLast(6), stringResource(if (member.role == "owner") R.string.sharing_owner else R.string.sharing_editor)))
                member.devices.forEach { device ->
                    Text(stringResource(R.string.sharing_device, device.id.takeLast(6)))
                    state.fingerprints[household]?.get(device.id)?.let { fingerprint ->
                        Text(fingerprint.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                    }
                    if (device.id == state.devices[household]) Text(stringResource(R.string.sharing_this_device))
                    if (owner) {
                        TextButton(enabled = !state.busy, onClick = { confirmRemoval = household to device.id }) { Text(stringResource(R.string.sharing_remove_device)) }
                        if (member.devices.none { it.id == state.devices[household] }) TextButton(enabled = !state.busy, onClick = { vm.transfer(household, device.id) }) { Text(stringResource(R.string.sharing_transfer)) }
                    }
                }
            }
            state.problem?.let { Text(sharingProblem(it), color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = { memberHousehold = null }) { Text(stringResource(R.string.history_dismiss)) } }) }
    confirmRemoval?.let { (household, device) -> AlertDialog(onDismissRequest = { confirmRemoval = null }, title = { Text(stringResource(R.string.sharing_remove_device)) },
        text = { Text(stringResource(R.string.sharing_remove_help, state.fingerprints[household]?.get(device).orEmpty().chunked(8).joinToString(" "))) },
        confirmButton = { TextButton(onClick = { confirmRemoval = null; vm.remove(household, device) }) { Text(stringResource(R.string.sharing_remove_device)) } },
        dismissButton = { TextButton(onClick = { confirmRemoval = null }) { Text(stringResource(R.string.sharing_cancel)) } }) }
    if (state.transferReview != null) AlertDialog(onDismissRequest = vm::closeTransfer, title = { Text(stringResource(R.string.sharing_transfer)) },
        text = { Column { Text(stringResource(R.string.sharing_accept_help)); state.problem?.let { Text(sharingProblem(it), color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(enabled = !state.busy, onClick = vm::acceptTransfer) { Text(stringResource(R.string.sharing_accept_transfer)) } },
        dismissButton = { TextButton(onClick = vm::closeTransfer) { Text(stringResource(R.string.sharing_cancel)) } })
    if (state.transferFile != null) AlertDialog(onDismissRequest = vm::closeTransfer, title = { Text(stringResource(R.string.sharing_transfer)) },
        text = { Text(stringResource(R.string.sharing_transfer_file_help)) },
        confirmButton = { TextButton(onClick = { saveTransfer.launch("usuals-transfer.json") }) { Text(stringResource(R.string.sharing_save_transfer)) } },
        dismissButton = { TextButton(onClick = vm::closeTransfer) { Text(stringResource(R.string.history_dismiss)) } })
    scanning?.let { mode -> QrCameraDialog(joining = mode == SharingScan.JOIN, serverOnly = connectionOnly, onDismiss = { scanning = null }, onCode = { code ->
        scanning = null
        if (mode == SharingScan.JOIN) vm.scanned(code, serverOnly = connectionOnly)
        else state.requests.firstOrNull()?.let { vm.approveQr(it, code) }
    }) }
}
private enum class SharingScan { JOIN, VERIFY }
@Composable private fun SharingField(value: String, label: Int, busy: Boolean, secret: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text(stringResource(label)) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password else if (label == R.string.sharing_origin) androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Ascii,
            autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}
@Composable private fun QrCode(value: String, label: Int) {
    val bitmap = remember(value) {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600, mapOf(EncodeHintType.MARGIN to 3))
        Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply { setPixels(IntArray(600 * 600) { if (matrix[it % 600, it / 600]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }, 0, 600, 0, 0, 600, 600) }
    }
    Image(bitmap.asImageBitmap(), stringResource(label), Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(vertical = 12.dp))
}
@Composable private fun sharingProblem(problem: SharingProblem) = stringResource(when (problem) {
    SharingProblem.NOT_USUALS -> R.string.sharing_not_usuals
    SharingProblem.FILE -> R.string.sharing_file_problem
    SharingProblem.FROZEN_KEY -> R.string.restore_key_help
    SharingProblem.ALREADY_JOINED -> R.string.sharing_already_joined
    SharingProblem.HISTORY -> R.string.sharing_history_unverifiable
    SharingProblem.OFFLINE -> R.string.sharing_offline
    SharingProblem.VERIFICATION -> R.string.sharing_verification
    SharingProblem.ACCESS -> R.string.sharing_access
    SharingProblem.LOCKED_KEYS -> R.string.sharing_locked_keys
    SharingProblem.RECOVERY -> R.string.sharing_recovery_required
    SharingProblem.CAPACITY -> R.string.sharing_capacity
    SharingProblem.CHANGED -> R.string.sharing_changed
    SharingProblem.UPGRADE -> R.string.sharing_upgrade
}, stringResource(R.string.app_name))
@Composable private fun sharingMessage(message: SharingMessage) = stringResource(when (message) {
    SharingMessage.COPY_KEPT -> R.string.sharing_copy_kept
    SharingMessage.COPY_REMOVED -> R.string.sharing_copy_removed
    SharingMessage.FROZEN_SAVED -> R.string.restore_saved
    SharingMessage.REAPPROVE -> R.string.sharing_transfer_reapprove
    SharingMessage.KEYS_RESET -> R.string.sharing_keys_reset
    SharingMessage.SHARED -> R.string.sharing_shared
    SharingMessage.ROTATED -> R.string.sharing_rotated
    SharingMessage.REMOVED -> R.string.sharing_removed
    SharingMessage.TRANSFERRED -> R.string.sharing_transferred
    SharingMessage.KIT_VERIFIED -> R.string.sharing_kit_verified
    SharingMessage.CREATED -> R.string.sharing_created
    SharingMessage.WAITING -> R.string.sharing_waiting
    SharingMessage.JOINED -> R.string.sharing_joined
    SharingMessage.APPROVED -> R.string.sharing_approved
    SharingMessage.DISCARDED -> R.string.sharing_discarded
    SharingMessage.REPLACED -> R.string.sharing_replaced
    SharingMessage.RECOVERED -> R.string.sharing_recovered
})

private fun copyName(context: android.content.Context, base: String, lists: List<SharingList>): String {
    var number = 1
    while (true) {
        val name = context.getString(R.string.sharing_local_copy_name, number++, base.ifBlank { context.getString(R.string.household_fallback_name) }).take(100)
        if (lists.none { it.name == name }) return name
    }
}

package io.theficos.usuals.ui.sharing

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.theficos.usuals.R
import io.theficos.usuals.sync.InviteCode
import io.theficos.usuals.sync.ReplacementKit
import io.theficos.usuals.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The browser's "Return to Usuals" link: it only brings the person back, the sign-in poll does the rest. */
internal const val RETURNED_FROM_SIGN_IN = "usuals://connected"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharingScreen(vm: SharingViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, invitation: String? = null, onInvitationConsumed: () -> Unit = {},
    sourceList: String? = null, onSourceConsumed: () -> Unit = {}) {
    val state by vm.state.collectAsState()
    val stage = sharingStage(state)
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? android.app.Activity)?.window
        val wasSecure = window?.attributes?.flags?.and(android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val scope = rememberCoroutineScope()
    var removingCopy by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf<String?>(null) }
    var memberHousehold by remember { mutableStateOf<String?>(null) }
    var confirmRemoval by remember { mutableStateOf<Pair<String, String>?>(null) }
    var history by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
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
    val owners = state.households.filter { it.role == "owner" && !it.removed }.map { it.id }
    LaunchedEffect(state.sharingSource, state.busy, state.kit, owners) {
        if (!state.busy && state.sharingSource != null && state.kit == null && owners.size == 1) {
            sharing = owners.single()
            history = state.shareAttempts.firstOrNull { it.household == sharing && it.source == state.sharingSource }?.includeHistory ?: false
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle, state.joining?.assertion?.requestId) {
        val expiresAt = state.joining?.invitation?.expiresAt ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            do { delay(5000); vm.checkJoin() } while (isActive && System.currentTimeMillis() < expiresAt)
        }
    }
    // Requests show up while the invite sheet is open, and later on this screen.
    LaunchedEffect(vm, lifecycle, owners, stage, state.inviting) {
        if (stage == SharingStage.READY && owners.isNotEmpty()) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                vm.state.first { !it.busy }
                owners.forEach(vm::pollRequests)
                delay(if (state.inviting != null) 3000 else 15000)
            }
        }
    }
    LaunchedEffect(invitation) {
        if (invitation == null) return@LaunchedEffect
        if (invitation.startsWith(RETURNED_FROM_SIGN_IN)) return@LaunchedEffect onInvitationConsumed()
        vm.state.first { !it.busy }
        if (vm.scanned(invitation)) onInvitationConsumed()
    }
    val saveKit = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.usuals.recovery+json")) { uri ->
        val kit = state.kit
        if (uri != null && kit != null) scope.launch(Dispatchers.IO) {
            // Reading the saved file back proves the storage kept it intact.
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(kit.file) } ?: error("unavailable")
                readLimited(context, uri)
            }.onSuccess(vm::saveKit).onFailure { vm.fileFailure() }
        }
    }
    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings_server), style = MaterialTheme.typography.headlineSmall) }, navigationIcon = {
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
            state.lists.firstOrNull { it.id == state.sharingSource }?.let { source ->
                Text(stringResource(R.string.connection_share_source, source.name.ifBlank { context.getString(R.string.household_fallback_name) }))
                TextButton(enabled = !state.busy, onClick = { vm.rememberShareSource(null) }) { Text(stringResource(R.string.sharing_cancel)) }
            }
            if (state.bootstrapPending) OutlinedButton(enabled = !state.busy, onClick = vm::resumeBootstrap) { Text(stringResource(R.string.sharing_retry_creation)) }
            when (stage) {
                SharingStage.START -> {
                    Text(stringResource(R.string.sharing_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SectionCard {
                        Text(stringResource(R.string.sharing_start_title), style = MaterialTheme.typography.titleLarge)
                        val saved = state.connection
                        if (saved == null) {
                            Text(stringResource(R.string.sharing_start_help))
                            ServerAddress(state, vm, R.string.sharing_continue, vm::startSharing) { scanning = true }
                        } else {
                            Text(stringResource(R.string.sharing_start_connected, saved.info.name))
                            Button(enabled = !state.busy, onClick = vm::startSharing, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sharing_start_title)) }
                        }
                    }
                    JoinCard(state, vm) { scanning = true }
                    ServerCard(state, vm)
                    Text(stringResource(R.string.settings_offline), style = MaterialTheme.typography.bodySmall)
                }
                SharingStage.SIGN_IN -> SignInStep(state, vm)
                SharingStage.PROTECT -> ProtectStep(state, vm) { saveKit.launch("usuals-recovery.json") }
                SharingStage.JOINING -> SectionCard {
                    val pending = requireNotNull(state.joining)
                    Text(stringResource(R.string.sharing_joining_title), style = MaterialTheme.typography.titleLarge)
                    Text(pending.invitation.origin, style = MaterialTheme.typography.bodySmall)
                    vm.joinCode(pending)?.let { code ->
                        Text(stringResource(R.string.sharing_joining_code))
                        Text(spaced(code), style = MaterialTheme.typography.headlineMedium)
                    }
                    Text(stringResource(R.string.sharing_pair_waiting), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled = !state.busy, onClick = vm::retryJoin) { Text(stringResource(R.string.sharing_retry_request)) }
                    TextButton(enabled = !state.busy, onClick = vm::checkJoin) { Text(stringResource(R.string.sharing_check_approval)) }
                }
                SharingStage.READY -> {
                    state.households.forEach { household -> SectionCard {
                        Text(stringResource(R.string.sharing_household, household.id.takeLast(6)), style = MaterialTheme.typography.titleLarge)
                        Text(household.origin, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (household.role == "owner") R.string.sharing_owner else R.string.sharing_editor))
                        if (household.removed) Text(stringResource(R.string.sharing_access))
                        if (household.role == "owner" && !household.removed) {
                            Button(enabled = !state.busy, onClick = { vm.invite(household.id) }) { Text(stringResource(R.string.sharing_invite)) }
                            state.requests[household.id].orEmpty().forEach { RequestRow(it, state.busy, vm) }
                            TextButton(enabled = !state.busy, onClick = {
                                sharing = household.id
                                history = state.shareAttempts.firstOrNull { it.household == household.id && it.source == state.sharingSource }?.includeHistory ?: false
                            }) { Text(stringResource(R.string.sharing_share_list)) }
                            state.lists.filter { it.shared && it.household == household.id && it.id !in household.lists }.forEach { list ->
                                TextButton(enabled = !state.busy, onClick = { vm.retryShare(household.id, list.id) }) { Text(stringResource(R.string.sharing_retry_list, list.name)) }
                            }
                        }
                        TextButton(enabled = !state.busy, onClick = { memberHousehold = household.id }) { Text(stringResource(R.string.sharing_members)) }
                        if (household.pending) TextButton(enabled = !state.busy, onClick = { vm.discardInvalid(household.id) }) { Text(stringResource(R.string.sharing_discard_invalid)) }
                        if (household.pending) TextButton(enabled = !state.busy, onClick = { vm.resume(household.id) }) { Text(stringResource(R.string.sharing_resume)) }
                    } }
                    ServerCard(state, vm)
                }
            }
            // A link that arrives mid-way (another invite, or one opened while signing in) still gets its Join step.
            if (stage != SharingStage.START && invitedOrigin(state.invitation) != null) JoinCard(state, vm, onJoin = { if (state.joining != null) replacingJoin = true else vm.join() }) { scanning = true }
            state.lists.filter { it.shared && it.accessRemoved }.forEach { list ->
                Text(stringResource(R.string.sharing_unavailable_list, list.name.ifBlank { context.getString(R.string.household_fallback_name) }))
                Text(stringResource(R.string.sharing_retained_help), style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !state.busy, onClick = { vm.retain(list.id, copyName(context, list.name, state.lists)) }) { Text(stringResource(R.string.sharing_retain_copy)) }
                TextButton(enabled = !state.busy, onClick = { removingCopy = list.id }) { Text(stringResource(R.string.sharing_remove_copy)) }
            }
            if (stage != SharingStage.JOINING) AdvancedSharing(state, vm, stage) { scanning = true }
            Spacer(Modifier.height(24.dp))
        }
    }
    state.outgoing?.let { invite -> InviteSheet(invite, state.requests[state.inviting].orEmpty(), state, vm) }
    if (replacingJoin) AlertDialog(onDismissRequest = { replacingJoin = false }, title = { Text(stringResource(R.string.sharing_replace_request_title)) },
        text = { Text(stringResource(R.string.sharing_replace_request_help)) },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { replacingJoin = false; vm.join() }) { Text(stringResource(R.string.sharing_replace_request)) } },
        dismissButton = { TextButton(onClick = { replacingJoin = false }) { Text(stringResource(R.string.sharing_cancel)) } })
    if (removingCopy != null) AlertDialog(onDismissRequest = { removingCopy = null }, title = { Text(stringResource(R.string.sharing_remove_copy)) },
        text = { Text(stringResource(R.string.sharing_remove_copy_help)) },
        confirmButton = { TextButton(onClick = { val id = requireNotNull(removingCopy); removingCopy = null; vm.removeCopy(id) }) { Text(stringResource(R.string.sharing_remove_copy)) } },
        dismissButton = { TextButton(onClick = { removingCopy = null }) { Text(stringResource(R.string.sharing_cancel)) } })
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
    if (scanning) QrCameraDialog(onDismiss = { scanning = false }, onCode = { code -> scanning = false; vm.scanned(code) })
}

/** Scan or paste an invite; once it parses, one Join tap sends the request. */
@Composable
internal fun JoinCard(state: SharingState, vm: SharingViewModel, onJoin: () -> Unit = vm::join, onScan: () -> Unit) {
    var paste by rememberSaveable { mutableStateOf(false) }
    SectionCard {
        Text(stringResource(R.string.sharing_join_title), style = MaterialTheme.typography.titleLarge)
        val origin = remember(state.invitation) { invitedOrigin(state.invitation) }
        if (origin == null) {
            Text(stringResource(R.string.sharing_join_help))
            OutlinedButton(enabled = !state.busy, onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sharing_scan_invite)) }
            TextButton(enabled = !state.busy, onClick = { paste = !paste }) { Text(stringResource(R.string.sharing_paste_invite)) }
            if (paste) SharingField(state.invitation, R.string.sharing_invitation, state.busy, secret = true) { vm.field(SharingField.INVITATION, it) }
        } else {
            Text(stringResource(R.string.sharing_join_server, origin))
            Button(enabled = !state.busy, onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sharing_join)) }
            TextButton(enabled = !state.busy, onClick = { vm.field(SharingField.INVITATION, "") }) { Text(stringResource(R.string.sharing_cancel)) }
        }
    }
}

internal fun invitedOrigin(text: String): String? = runCatching { InviteCode.parse(text.trim()).origin }.getOrNull()

/** The recovery file is saved and read back automatically; the key is copied and confirmed by the person. */
@Composable
private fun ProtectStep(state: SharingState, vm: SharingViewModel, onSave: () -> Unit) {
    val context = LocalContext.current
    val kit = requireNotNull(state.kit)
    var stored by remember(kit) { mutableStateOf(false) }
    SectionCard {
        Text(stringResource(R.string.sharing_kit_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.sharing_kit_help))
        if (state.verified) Text(stringResource(R.string.sharing_kit_verified), color = MaterialTheme.colorScheme.primary)
        else Button(enabled = !state.busy, onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sharing_save_kit)) }
        Text(stringResource(R.string.sharing_recovery_key), style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(kit.secret, style = MaterialTheme.typography.bodyLarge) }
        TextButton(onClick = { (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(
            android.content.ClipData.newPlainText(context.getString(R.string.sharing_recovery_key), kit.secret).apply {
                description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            }) }) { Text(stringResource(R.string.sharing_copy_key)) }
        Row { Checkbox(stored, { stored = it }); Text(stringResource(R.string.sharing_kit_stored)) }
        Button(enabled = !state.busy && state.verified && stored, onClick = vm::create, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (kit is ReplacementKit) R.string.sharing_replace_kit else R.string.sharing_create))
        }
        TextButton(onClick = vm::cancelSetup) { Text(stringResource(R.string.sharing_cancel)) }
    }
}

@Composable internal fun SharingField(value: String, label: Int, busy: Boolean, secret: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, label = { Text(stringResource(label)) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (secret) androidx.compose.ui.text.input.KeyboardType.Password else if (label == R.string.sharing_origin) androidx.compose.ui.text.input.KeyboardType.Uri else androidx.compose.ui.text.input.KeyboardType.Ascii,
            autoCorrectEnabled = false),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}
@Composable internal fun sharingProblem(problem: SharingProblem) = stringResource(when (problem) {
    SharingProblem.NOT_USUALS -> R.string.sharing_not_usuals
    SharingProblem.WRONG_SERVER -> R.string.sharing_wrong_server
    SharingProblem.OTHER_DEVICE -> R.string.sharing_other_device
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

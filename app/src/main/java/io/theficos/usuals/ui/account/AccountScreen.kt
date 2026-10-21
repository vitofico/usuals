package io.theficos.usuals.ui.account

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R
import io.theficos.usuals.sync.Pairing
import io.theficos.usuals.ui.components.SectionCard
import kotlinx.coroutines.delay

/** The browser's "Return to Usuals" link: it only brings the person back, the sign-in poll does the rest. */
internal const val RETURNED_FROM_SIGN_IN = "usuals://connected"

/** Settings > Account: connect this phone to a server account, or see and leave the one it uses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(vm: AccountViewModel, onBack: () -> Unit, link: String? = null, onLinkConsumed: () -> Unit = {}) {
    val state by vm.state.collectAsState()
    var scanning by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.load() }
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        if (!link.startsWith(RETURNED_FROM_SIGN_IN)) vm.scanned(link)
        onLinkConsumed()
    }
    val pending = state.connecting
    LaunchedEffect(pending?.ticket?.id) {
        if (pending == null) return@LaunchedEffect
        while (System.currentTimeMillis() < pending.ticket.expiresAt) {
            delay(if (pending.offered) 1000 else 2500)
            vm.check()
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        TopAppBar(title = { Text(stringResource(R.string.account_title), style = MaterialTheme.typography.headlineSmall) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings_back)) }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.problem?.let { Text(accountProblem(it), color = MaterialTheme.colorScheme.error) }
            if (state.done && state.connection != null) Text(stringResource(R.string.account_connected), color = MaterialTheme.colorScheme.primary)
            val saved = state.connection
            val offer = state.offer
            when {
                saved != null -> SignedIn(state, vm) { signingOut = true }
                pending != null -> Connecting(state, vm)
                offer != null -> SectionCard {
                    Text(stringResource(R.string.account_offer_title, offer.origin), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.account_offer_help))
                    Button(enabled = !state.busy, onClick = vm::connectOffer, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_connect)) }
                    TextButton(enabled = !state.busy, onClick = vm::dismissOffer) { Text(stringResource(R.string.account_not_now)) }
                }
                else -> SignedOut(state, vm, onScan = { scanning = true }, onSignOut = { signingOut = true })
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (scanning) QrCameraDialog(onDismiss = { scanning = false }) { code -> scanning = false; vm.scanned(code) }
    val unsent = state.unsent
    if (signingOut || unsent != null) AlertDialog(onDismissRequest = { signingOut = false; vm.keepSignedIn() },
        title = { Text(stringResource(R.string.account_sign_out_title)) },
        text = { Text(if (unsent != null) pluralStringResource(R.plurals.account_unsent, unsent, unsent) else stringResource(R.string.account_sign_out_help)) },
        confirmButton = { TextButton(enabled = !state.busy, onClick = { signingOut = false; vm.signOut(force = unsent != null) }) {
            Text(stringResource(if (unsent != null) R.string.account_sign_out_anyway else R.string.account_sign_out))
        } },
        dismissButton = { TextButton(onClick = { signingOut = false; vm.keepSignedIn() }) { Text(stringResource(R.string.edit_cancel)) } })
}

@Composable
private fun SignedOut(state: AccountState, vm: AccountViewModel, onScan: () -> Unit, onSignOut: () -> Unit) {
    if (state.expired) SectionCard {
        Text(stringResource(R.string.account_expired_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.account_expired_help))
        TextButton(enabled = !state.busy, onClick = onSignOut) { Text(stringResource(R.string.account_sign_out)) }
    } else Text(stringResource(R.string.account_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SectionCard {
        Text(stringResource(R.string.account_scan_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.account_scan_help))
        Button(enabled = !state.busy, onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_scan)) }
    }
    SectionCard {
        Text(stringResource(R.string.account_browser_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.account_browser_help))
        OutlinedTextField(state.origin, vm::field, enabled = !state.busy, label = { Text(stringResource(R.string.account_server)) },
            placeholder = { Text("https://usuals.example.org") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = !state.busy && state.origin.isNotBlank(), onClick = vm::signInInBrowser, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.connection_signin))
        }
    }
    Text(stringResource(R.string.account_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A scanned offer completes at once; the browser sign-in shows the code to compare while it waits. */
@Composable
private fun Connecting(state: AccountState, vm: AccountViewModel) {
    val context = LocalContext.current
    val pending = state.connecting ?: return
    SectionCard {
        Text(pending.origin, style = MaterialTheme.typography.bodySmall)
        if (pending.offered) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text(stringResource(R.string.account_connecting))
        } else {
            Text(stringResource(R.string.connection_compare))
            Text(Pairing.connectionCode(pending.fingerprint).chunked(3).joinToString(" "), style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.browserUrl))) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connection_signin))
            }
            Text(stringResource(R.string.connection_waiting))
        }
        TextButton(onClick = vm::cancel) { Text(stringResource(R.string.edit_cancel)) }
    }
}

/** The signed-in account, its server's optional AI, and signing out. */
@Composable
private fun SignedIn(state: AccountState, vm: AccountViewModel, onSignOut: () -> Unit) {
    val context = LocalContext.current
    val saved = state.connection ?: return
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(saved.token) { vm.loadAi() }
    SectionCard {
        Text(state.name?.let { stringResource(R.string.account_signed_in_as, it) } ?: stringResource(R.string.account_signed_in), style = MaterialTheme.typography.titleLarge)
        Text(saved.info.name, style = MaterialTheme.typography.bodyMedium)
        Text(saved.origin, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(saved.origin))) }) { Text(stringResource(R.string.settings_server_account)) }
        OutlinedButton(enabled = !state.busy, onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.account_sign_out)) }
    }
    if (saved.aiEnabled || saved.info.ai || state.ai?.profiles?.isNotEmpty() == true) SectionCard {
        val selected = state.ai?.let { settings -> settings.profiles.firstOrNull { it.id == settings.selected } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.settings_ai), modifier = Modifier.weight(1f))
            Switch(saved.aiEnabled, vm::enableAi, enabled = saved.aiEnabled || (!state.busy && state.ai?.revision != null))
        }
        Text(stringResource(R.string.settings_ai_help), style = MaterialTheme.typography.bodySmall)
        selected?.let {
            Text(it.label, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { showDetails = !showDetails }) { Text(stringResource(R.string.settings_ai_details)) }
            if (showDetails) {
                Text(it.model, style = MaterialTheme.typography.bodySmall)
                if (it.imageModel != it.model) Text(it.imageModel, style = MaterialTheme.typography.bodySmall)
                Text(it.baseUrl, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(if (it.isLocal) R.string.connection_local else R.string.connection_external), style = MaterialTheme.typography.bodySmall)
        }
        if (saved.aiEnabled && state.ai?.revision != null && saved.aiRevision != state.ai.revision) {
            Text(stringResource(R.string.settings_ai_changed), color = MaterialTheme.colorScheme.error)
            TextButton(enabled = !state.busy, onClick = { vm.enableAi(true) }) { Text(stringResource(R.string.settings_ai_update)) }
        }
        Text(stringResource(R.string.connection_privacy), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun accountProblem(problem: AccountProblem) = stringResource(when (problem) {
    AccountProblem.NOT_USUALS -> R.string.account_problem_not_usuals
    AccountProblem.OUTDATED -> R.string.account_problem_outdated
    AccountProblem.ALREADY -> R.string.account_problem_already
    AccountProblem.OFFLINE -> R.string.account_problem_offline
    AccountProblem.EXPIRED_CODE -> R.string.account_problem_expired_code
    AccountProblem.UPGRADE -> R.string.account_problem_upgrade
    AccountProblem.REFUSED -> R.string.account_problem_refused
})

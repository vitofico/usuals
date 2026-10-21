package io.theficos.usuals.ui.sharing

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.theficos.usuals.R
import io.theficos.usuals.sync.Pairing
import io.theficos.usuals.ui.components.SectionCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Both screens show the same six digits; the browser approves and the poll here notices. */
@Composable
internal fun SignInStep(state: SharingState, vm: SharingViewModel) {
    val context = LocalContext.current
    val pending = requireNotNull(state.connecting)
    LaunchedEffect(pending.ticket.id) {
        while (System.currentTimeMillis() < pending.ticket.expiresAt) {
            delay(2500)
            vm.checkConnection()
        }
    }
    SectionCard {
        Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleLarge)
        Text(pending.origin, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.connection_compare))
        Text(spaced(Pairing.connectionCode(pending.fingerprint)), style = MaterialTheme.typography.headlineMedium)
        Button(enabled = !state.busy, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.browserUrl))) }) { Text(stringResource(R.string.connection_signin)) }
        Text(stringResource(R.string.connection_waiting))
        TextButton(onClick = vm::cancelConnection) { Text(stringResource(R.string.connection_cancel)) }
    }
}

/** A server address typed or scanned; [action] contacts the server only when tapped. */
@Composable
internal fun ServerAddress(state: SharingState, vm: SharingViewModel, action: Int, onAction: () -> Unit, onScan: () -> Unit) {
    OutlinedTextField(state.origin, { vm.field(SharingField.ORIGIN, it) }, enabled = !state.busy,
        label = { Text(stringResource(R.string.connection_link)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(enabled = !state.busy && state.origin.isNotBlank(), onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(stringResource(action)) }
    OutlinedButton(enabled = !state.busy, onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sharing_scan_server)) }
}

/** The signed-in server and its optional AI; disconnecting lives under Advanced. */
@Composable
internal fun ServerCard(state: SharingState, vm: SharingViewModel) {
    val context = LocalContext.current
    val saved = state.connection ?: return
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(saved.token) {
        vm.state.first { !it.busy }
        vm.loadAi()
    }
    SectionCard {
        Text(stringResource(R.string.settings_connected), style = MaterialTheme.typography.labelLarge)
        Text(saved.info.name, style = MaterialTheme.typography.titleLarge)
        Text(saved.origin, style = MaterialTheme.typography.bodySmall)
        if (saved.aiEnabled || saved.info.ai || state.ai?.profiles?.isNotEmpty() == true) {
            val selected = state.ai?.let { settings -> settings.profiles.firstOrNull { it.id == settings.selected } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.settings_ai), modifier = Modifier.weight(1f))
                Switch(saved.aiEnabled, vm::enableServerAi, enabled = saved.aiEnabled || (!state.busy && state.ai?.revision != null))
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
                TextButton(enabled = !state.busy, onClick = { vm.enableServerAi(true) }) { Text(stringResource(R.string.settings_ai_update)) }
            }
            Text(stringResource(R.string.connection_privacy), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(saved.origin))) }) { Text(stringResource(R.string.settings_server_account)) }
    }
}

/** Six digits read more easily as two groups of three. */
internal fun spaced(code: String) = code.chunked(3).joinToString(" ")

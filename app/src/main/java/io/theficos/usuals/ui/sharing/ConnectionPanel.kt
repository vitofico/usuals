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
import io.theficos.usuals.ui.components.SectionCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
internal fun ConnectionPanel(state: SharingState, vm: SharingViewModel, showHouseholdSetup: Boolean = true, onScan: () -> Unit) {
    val context = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }
    val pending = state.connecting
    LaunchedEffect(pending?.ticket?.id) {
        if (pending != null) while (System.currentTimeMillis() < pending.ticket.expiresAt) {
            delay(2500)
            vm.checkConnection()
        }
    }
    LaunchedEffect(state.connection?.token) {
        if (state.connection != null) {
            vm.state.first { !it.busy }
            vm.loadAi()
        }
    }
    SectionCard {
        val saved = state.connection
        if (saved == null && pending == null) {
            Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.settings_server_help))
            OutlinedTextField(state.origin, { vm.field(SharingField.ORIGIN, it) }, enabled = !state.busy,
                label = { Text(stringResource(R.string.connection_link)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !state.busy && state.origin.isNotBlank(), onClick = vm::connectServer, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connection_connect))
            }
            OutlinedButton(enabled = !state.busy, onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (showHouseholdSetup) R.string.sharing_scan_invitation else R.string.settings_scan_server)) }
            Text(stringResource(R.string.settings_offline), style = MaterialTheme.typography.bodySmall)
        } else if (pending != null) {
            Text(pending.origin)
            Text(stringResource(R.string.connection_compare))
            Text(pending.fingerprint.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !state.busy, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.browserUrl))) }) { Text(stringResource(R.string.connection_signin)) }
            Text(stringResource(R.string.connection_waiting))
            TextButton(onClick = vm::cancelConnection) { Text(stringResource(R.string.connection_cancel)) }
        } else if (saved != null) {
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
            if (showHouseholdSetup) OutlinedButton(enabled = !state.busy, onClick = onScan) { Text(stringResource(R.string.sharing_scan_invitation)) }
            if (showHouseholdSetup && state.households.isEmpty() && state.kit == null) Button(enabled = !state.busy, onClick = vm::prepare) { Text(stringResource(R.string.sharing_create_title)) }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(saved.origin))) }) { Text(stringResource(R.string.settings_server_account)) }
            TextButton(enabled = !state.busy, onClick = vm::disconnectServer) { Text(stringResource(R.string.connection_disconnect)) }
        }
    }
}

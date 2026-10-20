package io.theficos.usuals.ui.sharing

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.theficos.usuals.R
import io.theficos.usuals.ui.components.SectionCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
internal fun ConnectionPanel(state: SharingState, vm: SharingViewModel, onScan: () -> Unit) {
    val context = LocalContext.current
    var showAi by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
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
        Text(stringResource(R.string.connection_title), style = MaterialTheme.typography.titleLarge)
        Button(enabled = !state.busy, onClick = onScan) { Text(stringResource(R.string.sharing_scan_invitation)) }
        val saved = state.connection
        if (saved == null && pending == null) {
            Text(stringResource(R.string.connection_intro))
            OutlinedTextField(state.origin, { vm.field(SharingField.ORIGIN, it) }, enabled = !state.busy,
                label = { Text(stringResource(R.string.connection_link)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = !state.busy && state.origin.isNotBlank(), onClick = vm::connectServer) { Text(stringResource(R.string.connection_connect)) }
        } else if (pending != null) {
            Text(pending.origin)
            Text(stringResource(R.string.connection_compare))
            Text(pending.fingerprint.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !state.busy, onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pending.browserUrl))) }) { Text(stringResource(R.string.connection_signin)) }
            Text(stringResource(R.string.connection_waiting))
            TextButton(onClick = vm::cancelConnection) { Text(stringResource(R.string.connection_cancel)) }
        } else if (saved != null) {
            Text(saved.info.name)
            Text(saved.origin, style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !state.busy, onClick = vm::disconnectServer) { Text(stringResource(R.string.connection_disconnect)) }
            if (state.households.isEmpty() && state.kit == null) Button(enabled = !state.busy, onClick = vm::prepare) { Text(stringResource(R.string.sharing_create_title)) }
            TextButton(onClick = { showAi = !showAi }) { Text(stringResource(R.string.connection_ai)) }
            if (showAi) {
            Row { Switch(saved.aiEnabled, vm::enableServerAi, enabled = !state.busy && state.ai?.revision != null); Text(stringResource(R.string.connection_ai)) }
            Text(stringResource(R.string.connection_privacy), style = MaterialTheme.typography.bodySmall)
            val settings = state.ai
            if (settings != null) {
                val selected = settings.profiles.firstOrNull { it.id == settings.selected }
                selected?.let {
                    Text("${it.label} · ${it.model} · ${it.imageModel}")
                    Text(it.baseUrl, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (it.isLocal) R.string.connection_local else R.string.connection_external))
                }
                Text(stringResource(R.string.connection_usage, settings.used, settings.dailyLimit))
                TextButton(enabled = !state.busy && settings.revision != null, onClick = vm::testAi) { Text(stringResource(R.string.settings_test_connection)) }
                state.aiReady?.let { ready -> Text(stringResource(if (ready) R.string.settings_probe_structured_ok else R.string.model_problem_unreachable)) }
                TextButton(enabled = !state.busy, onClick = { key = ""; vm.chooseAi(null, null) }) { Text(stringResource(R.string.connection_default)) }
                Text(stringResource(R.string.connection_personal))
                OutlinedTextField(key, { key = it.take(4096) }, enabled = !state.busy, label = { Text(stringResource(R.string.connection_key)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.connection_key_help), style = MaterialTheme.typography.bodySmall)
                settings.profiles.forEach { profile ->
                    TextButton(enabled = !state.busy, onClick = { val draft = key; key = ""; vm.chooseAi(profile.id, draft) }) {
                        Text(stringResource(R.string.connection_choose, profile.label))
                    }
                    if (state.directProviderUrl?.trimEnd('/') == profile.baseUrl.trimEnd('/')) TextButton(enabled = !state.busy, onClick = { vm.migrateDirectKey(profile) }) {
                        Text(stringResource(R.string.connection_migrate, profile.label))
                    }
                }
            }
            }
        }
    }
}

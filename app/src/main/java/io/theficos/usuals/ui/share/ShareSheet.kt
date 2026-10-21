package io.theficos.usuals.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.usuals.R

/** Who has access to one list, and adding people from the server by tapping them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(vm: ShareViewModel, onDismiss: () -> Unit, onOpenAccount: () -> Unit) {
    val state by vm.state.collectAsState()
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(state.gone) { if (state.gone) onDismiss() }
    val target = state.target ?: return
    val name = target.name.ifBlank { stringResource(R.string.household_fallback_name) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.share_title, name), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!state.signedIn) {
                Text(stringResource(R.string.share_signed_out))
                Button(onClick = { onDismiss(); onOpenAccount() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_account_open)) }
                return@Column
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.problem?.let { Text(stringResource(when (it) {
                ShareProblem.OFFLINE -> R.string.share_problem_offline
                ShareProblem.SIGN_IN -> R.string.share_problem_sign_in
                ShareProblem.FULL -> R.string.share_problem_full
                ShareProblem.REFUSED -> R.string.share_problem_refused
            }), color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.share_people), style = MaterialTheme.typography.titleSmall)
            val members = target.members.ifEmpty { listOf(ShareMember(requireNotNull(target.me), "", owner = true)) }
            members.forEach { member ->
                val mine = member.id == target.me
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (mine) stringResource(R.string.share_you) else member.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (member.owner) R.string.share_owner else R.string.share_editor),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (mine && !member.owner) TextButton(enabled = !state.busy, onClick = { leaving = true }) { Text(stringResource(R.string.share_leave)) }
                    else if (state.owner && !member.owner) TextButton(enabled = !state.busy, onClick = { vm.remove(member) }) { Text(stringResource(R.string.share_remove)) }
                }
            }
            Text(stringResource(R.string.share_add), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(state.query, vm::query, label = { Text(stringResource(R.string.share_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (state.candidates.isEmpty() && state.problem == null) Text(stringResource(R.string.share_no_people), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.candidates.forEach { person ->
                Column(Modifier.fillMaxWidth().clickable(enabled = !state.busy, onClickLabel = stringResource(R.string.share_add)) { vm.add(person) }.padding(vertical = 8.dp)) {
                    Text(person.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    person.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text(stringResource(R.string.share_leave_title, name)) },
        text = { Text(stringResource(R.string.share_leave_help)) },
        confirmButton = { TextButton(onClick = { leaving = false; vm.leave() }) { Text(stringResource(R.string.share_leave)) } },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.edit_cancel)) } })
}

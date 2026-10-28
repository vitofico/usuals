package io.theficos.usuals.ui.notifications

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.theficos.usuals.R
import io.theficos.usuals.ui.shopping.ReminderController
import io.theficos.usuals.ui.shopping.ReminderSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NotificationsPanel(controller: SharedNotifications, reminders: ReminderController?) {
    val settings by controller.state.collectAsStateWithLifecycle()
    val available by controller.available.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var denied by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var reminderOpen by remember { mutableStateOf(false) }
    var permissionAccount by remember { mutableStateOf<String?>(null) }
    fun save(enabled: Boolean? = null, items: Boolean? = null, shops: Boolean? = null, account: String? = controller.account()) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { controller.configure(enabled, items, shops, account) }
                failed = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) save(true, account = permissionAccount)
    }
    Text(stringResource(R.string.notifications_help))
    if (!available) Text(stringResource(R.string.notifications_sign_in))
    NotificationToggle(stringResource(R.string.notifications_shared), settings.enabled, available) { enabled ->
        denied = false
        if (!enabled) save(false)
        else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            { permissionAccount = controller.account(); permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) denied = true
        else save(true)
    }
    NotificationToggle(stringResource(R.string.notifications_items), settings.itemsAdded, available && settings.enabled) { save(items = it) }
    NotificationToggle(stringResource(R.string.notifications_shops), settings.shopsFinished, available && settings.enabled) { save(shops = it) }
    if (denied) Text(stringResource(R.string.reminder_permission))
    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) {
        Text(stringResource(R.string.reminder_notification_settings))
    }
    if (failed) Text(stringResource(R.string.reminder_failed), color = MaterialTheme.colorScheme.error)
    reminders?.let { TextButton(onClick = { reminderOpen = true }) { Text(stringResource(R.string.reminder_title)) } }
    if (reminderOpen) reminders?.let { ReminderSheet(it) { reminderOpen = false } }
}

@Composable
private fun NotificationToggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

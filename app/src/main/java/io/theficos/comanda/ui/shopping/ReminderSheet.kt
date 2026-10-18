@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.theficos.comanda.ui.shopping

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.theficos.comanda.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.format.TextStyle

@Composable
fun ReminderSheet(controller: ReminderController, onDismiss: () -> Unit) {
    val settings by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var day by remember(settings.day) { mutableIntStateOf(settings.day) }
    var hour by remember(settings.hour) { mutableIntStateOf(settings.hour) }
    var minute by remember(settings.minute) { mutableIntStateOf(settings.minute) }
    var chooseDay by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun save(enabled: Boolean) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { controller.configure(enabled, day, hour, minute) }
                failed = false
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) save(true)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.reminder_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.reminder_help))
            if (settings.unavailable) Text(stringResource(R.string.shopping_preferences_unavailable))
            else {
                Text(stringResource(if (settings.enabled) R.string.reminder_on else R.string.reminder_off), style = MaterialTheme.typography.titleMedium)
                Box {
                    OutlinedButton(onClick = { chooseDay = true }) { Text(DayOfWeek.of(day).getDisplayName(TextStyle.FULL, locale)) }
                    DropdownMenu(expanded = chooseDay, onDismissRequest = { chooseDay = false }) {
                        DayOfWeek.entries.forEach { value -> DropdownMenuItem(text = { Text(value.getDisplayName(TextStyle.FULL, locale)) },
                            onClick = { day = value.value; chooseDay = false }) }
                    }
                }
                OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute,
                    android.text.format.DateFormat.is24HourFormat(context)).show() }) {
                    Text(android.text.format.DateFormat.getTimeFormat(context).format(java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, hour); set(java.util.Calendar.MINUTE, minute)
                    }.time))
                }
                Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) denied = true
                    else save(true)
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(if (settings.enabled) R.string.reminder_save else R.string.reminder_enable)) }
                if (settings.enabled) TextButton(onClick = { save(false) }) { Text(stringResource(R.string.reminder_disable)) }
                if (denied) {
                    Text(stringResource(R.string.reminder_permission))
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) {
                        Text(stringResource(R.string.reminder_notification_settings))
                    }
                }
                if (failed) Text(stringResource(R.string.reminder_failed), color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.shopping_close)) }
        }
    }
}

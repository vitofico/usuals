package io.theficos.usuals.di

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import io.theficos.usuals.MainActivity
import io.theficos.usuals.R
import io.theficos.usuals.ui.shopping.ReminderController
import io.theficos.usuals.widget.EXTRA_OPEN_LIST
import java.util.concurrent.TimeUnit

private const val CHANNEL = "shopping-reminders"
private const val TAG = "shopping-reminder"
private const val NOTIFICATION = 710

fun preferenceReminders(context: Context): ReminderController {
    val preferences = context.getSharedPreferences("shopping_reminders", Context.MODE_PRIVATE)
    val work by lazy { WorkManager.getInstance(context) }
    return ReminderController(
        read = { preferences.getString("state", null) },
        write = { check(preferences.edit().putString("state", it).commit()) },
        enqueue = { settings ->
            work.enqueueUniqueWork("$TAG-${settings.token}-${settings.nextAt}", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ShoppingReminderWorker>()
                    .addTag(TAG).setInputData(workDataOf("token" to settings.token, "at" to settings.nextAt))
                    .setInitialDelay((settings.nextAt - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS).build())
        },
        cancel = { work.cancelAllWorkByTag(TAG); NotificationManagerCompat.from(context).cancel(NOTIFICATION) },
        notify = {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
            if ((Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_LIST, true)
                val pending = PendingIntent.getActivity(context, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle(context.getString(R.string.reminder_notification_title))
                    .setContentText(context.getString(R.string.reminder_notification_body))
                    .setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
                try { NotificationManagerCompat.from(context).notify(NOTIFICATION, notification) } catch (_: SecurityException) { }
            }
        },
    )
}

class ShoppingReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        return try {
            if (ServiceLocator.reminders.fire(inputData.getString("token").orEmpty(), inputData.getLong("at", 0))) Result.success() else Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}

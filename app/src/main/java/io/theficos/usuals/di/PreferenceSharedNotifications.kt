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
import io.theficos.usuals.MainActivity
import io.theficos.usuals.R
import io.theficos.usuals.sync.ServerConnection
import io.theficos.usuals.sync.Wire
import io.theficos.usuals.ui.notifications.SharedNotifications
import io.theficos.usuals.widget.EXTRA_OPEN_LIST

private const val CHANNEL = "shared-list-activity"
private const val TAG = "shared-list:"

internal fun notificationAccount(connection: ServerConnection?) = connection?.let {
    Wire.hash("${it.origin}\n${it.userId}".toByteArray())
}

fun preferenceSharedNotifications(context: Context): SharedNotifications {
    val prefs = context.getSharedPreferences("shared_notifications", Context.MODE_PRIVATE)
    val manager = context.getSystemService(NotificationManager::class.java)
    return SharedNotifications(
        read = { prefs.getString("state", null) },
        write = { check(prefs.edit().putString("state", it).commit()) },
        cancel = { manager.activeNotifications.filter { it.tag?.startsWith(TAG) == true }.forEach { manager.cancel(it.tag, it.id) } },
        deliver = { notice ->
            manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notifications_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
            if ((Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_LIST, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pending = PendingIntent.getActivity(context, 712, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val body = when {
                    notice.items > 0 && notice.shops > 0 -> context.getString(R.string.notifications_mixed_body)
                    notice.shops > 0 -> context.resources.getQuantityString(R.plurals.notifications_shops_body, notice.shops, notice.shops)
                    else -> context.resources.getQuantityString(R.plurals.notifications_items_body, notice.items, notice.items)
                }
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(context.getString(R.string.notifications_activity_title))
                    .setContentText(body).setContentIntent(pending).setAutoCancel(true)
                    .setSilent(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_SECRET)
                    .setTimeoutAfter(24 * 60 * 60 * 1000L).build()
                try { NotificationManagerCompat.from(context).notify(TAG + notice.list, 0, notification) } catch (_: SecurityException) { }
            }
        },
    )
}

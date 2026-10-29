package io.theficos.usuals.ui.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.di.preferenceSharedNotifications
import io.theficos.usuals.sync.*
import io.theficos.usuals.widget.EXTRA_OPEN_LIST
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class PreferenceSharedNotificationsTest {
    @Test fun wiredDeliveryIsSilentPrivateAndClearedByDisableOrForeground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = preferenceSharedNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        controller.bind("synthetic-account")
        val list = SharedList("test", 1, "owner", Person("me", "Me"),
            listOf(Member("me", "Me", "owner"), Member("other", "Other", "editor")), Projection())
        controller.receive("synthetic-account", ListChanges("1", true, false, listOf(list), emptyList()), true)
        controller.configure(true)
        fun receive(id: String) = controller.receive("synthetic-account", ListChanges(id, false, false,
            listOf(list.copy(activity = listOf(ListActivity(id, "items_added", 2, System.currentTimeMillis() + 1000)))), emptyList()), false)
        receive("fresh")
        assertThat(manager.activeNotifications).hasLength(1)
        val notification = manager.activeNotifications.single().notification
        assertThat(notification.channelId).isEqualTo("shared-list-activity")
        assertThat(manager.getNotificationChannel(notification.channelId).importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
        assertThat(manager.getNotificationChannel(notification.channelId).sound).isNull()
        assertThat(manager.getNotificationChannel(notification.channelId).shouldVibrate()).isFalse()
        assertThat(notification.visibility).isEqualTo(Notification.VISIBILITY_SECRET)
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()).contains("2")
        assertThat(shadowOf(notification.contentIntent).savedIntent.getBooleanExtra(EXTRA_OPEN_LIST, false)).isTrue()
        controller.configure(itemsAdded = false)
        assertThat(manager.activeNotifications).isEmpty()
        controller.configure(itemsAdded = true); receive("another")
        controller.foreground(true)
        assertThat(manager.activeNotifications).isEmpty()
    }
}

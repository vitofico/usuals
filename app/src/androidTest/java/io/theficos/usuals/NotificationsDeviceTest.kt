package io.theficos.usuals

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.theficos.usuals.ui.notifications.*
import io.theficos.usuals.ui.theme.UsualsTheme
import io.theficos.usuals.di.preferenceSharedNotifications
import io.theficos.usuals.sync.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotificationsDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun controlsStartOffAndEachCategoryAndMasterCanBeDisabled() {
        var raw: String? = null
        val controller = SharedNotifications({ raw }, { raw = it }, {}, {}, { 100L })
        controller.bind("synthetic-account")
        compose.setContent { UsualsTheme { Column { NotificationsPanel(controller, null) } } }
        val master = compose.activity.getString(R.string.notifications_shared)
        val items = compose.activity.getString(R.string.notifications_items)
        val shops = compose.activity.getString(R.string.notifications_shops)
        compose.onNodeWithText(master).assertIsDisplayed()
        compose.runOnIdle { assertFalse(controller.state.value.enabled) }
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            compose.activity.packageName, Manifest.permission.POST_NOTIFICATIONS)
        compose.onNodeWithText(master).performClick()
        compose.waitUntil { controller.state.value.enabled }
        compose.onNodeWithText(items).performClick()
        compose.waitUntil { !controller.state.value.itemsAdded }
        compose.onNodeWithText(shops).performClick()
        compose.waitUntil { !controller.state.value.shopsFinished }
        compose.onNodeWithText(master).performClick()
        compose.waitUntil { !controller.state.value.enabled }
        compose.runOnIdle { assertTrue(raw != null) }
    }

    @Test fun productionDeliveryUsesSilentChannelAndDisablingClearsIt() {
        val context = compose.activity
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val controller = preferenceSharedNotifications(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            controller.bind("synthetic-delivery-account")
            val list = SharedList("synthetic-list", 1, "owner", Person("me", "Me"),
                listOf(Member("me", "Me", "owner"), Member("other", "Other", "editor")), Projection())
            controller.receive("synthetic-delivery-account", ListChanges("1", true, false, listOf(list), emptyList()), true)
            controller.configure(true)
            controller.receive("synthetic-delivery-account", ListChanges("2", false, false, listOf(list.copy(activity =
                listOf(ListActivity("synthetic-event", "items_added", 2, System.currentTimeMillis() + 1000)))), emptyList()), false)
            compose.waitUntil(5_000) { manager.activeNotifications.any { it.tag == "shared-list:synthetic-list" } }
            assertEquals(1, manager.activeNotifications.count { it.tag == "shared-list:synthetic-list" })
            val channel = manager.getNotificationChannel("shared-list-activity")
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertEquals(null, channel.sound)
            assertFalse(channel.shouldVibrate())
            controller.configure(false)
            compose.waitUntil(5_000) { manager.activeNotifications.none { it.tag == "shared-list:synthetic-list" } }
        } finally { controller.bind(null) }
    }

}

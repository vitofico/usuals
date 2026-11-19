package io.theficos.usuals

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.theficos.usuals.ui.notifications.*
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-w411dp-h891dp")
class NotificationsScreenTest {
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
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
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

}

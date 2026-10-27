package io.theficos.usuals

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.theficos.usuals.ui.list.*
import io.theficos.usuals.ui.theme.UsualsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SyncIndicatorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun statusChangesKeepListInPlaceAndDetailsRemainAccessible() {
        val directory = mutableStateOf(ListDirectory(selected = "list", lists = listOf(NamedList("list", "Groceries", shared = false)), status = SyncStatus.SYNCED))
        var signIn = false
        compose.setContent {
            UsualsTheme {
                Column {
                    NamedListPicker(null, directory.value, false, ListActions(onOpenAccount = { signIn = true }))
                    Text("Milk", Modifier.testTag("first_item"))
                }
            }
        }
        val top = compose.onNodeWithTag("first_item").fetchSemanticsNode().boundsInRoot.top
        fun unchanged() = assertEquals(top, compose.onNodeWithTag("first_item").fetchSemanticsNode().boundsInRoot.top, 0.1f)
        for (status in SyncStatus.entries) {
            compose.runOnIdle { directory.value = directory.value.copy(status = status, pending = 3) }
            unchanged()
        }
        val details = compose.activity.getString(R.string.sync_details)
        compose.onNodeWithContentDescription(details, substring = true).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.sync_capacity)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.lists_pending, 3, 3)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.history_dismiss)).performClick()
        compose.runOnIdle { directory.value = directory.value.copy(status = SyncStatus.SIGN_IN, pending = 0) }
        unchanged()
        compose.onNodeWithContentDescription(details, substring = true).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.account_expired_title)).performClick()
        compose.runOnIdle { assertTrue(signIn) }
        compose.runOnIdle { directory.value = directory.value.copy(status = null) }
        unchanged()
    }
}

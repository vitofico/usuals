package io.theficos.usuals.di

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidWalletTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test fun picker_asks_for_a_launcher_without_sending_shopping_data() {
        val picker = AndroidWallet(context).picker("Choose wallet")
        assertThat(picker.action).isEqualTo(Intent.ACTION_PICK_ACTIVITY)
        val base = picker.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertThat(base.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(base.categories).containsExactly(Intent.CATEGORY_LAUNCHER)
        assertThat(base.data).isNull()
        assertThat(base.extras).isNull()
    }
    @Test fun selected_launcher_is_remembered_but_disabled_or_removed_app_is_not_opened() {
        val component = ComponentName("example.wallet", "example.wallet.Main")
        val info = ActivityInfo().apply {
            packageName = component.packageName; name = component.className; exported = true; enabled = true
            applicationInfo = ApplicationInfo().apply { packageName = component.packageName; enabled = true }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(component.packageName), ResolveInfo().apply { activityInfo = info })
        assertThat(AndroidWallet(context).choose(component)!!.component).isEqualTo(component)
        assertThat(AndroidWallet(context).selected()!!.component).isEqualTo(component)
        info.enabled = false
        assertThat(AndroidWallet(context).selected()).isNull()
        assertThat(AndroidWallet(context).choose(ComponentName("missing", "missing.Main"))).isNull()
    }
}

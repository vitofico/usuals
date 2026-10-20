package io.theficos.usuals.wiring

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.di.ServiceLocator
import io.theficos.usuals.allowsTutorial
import io.theficos.usuals.pendingShare
import io.theficos.usuals.STATE_SHARE_CONSUMED
import io.theficos.usuals.widget.EXTRA_OPEN_LIST
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TutorialPreferencesTest {
    @Test fun `recreating an already consumed share or widget entry still defers the tutorial`() {
        val share = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Apples") }
        val consumed = Bundle().apply { putBoolean(STATE_SHARE_CONSUMED, true) }
        assertThat(pendingShare(share, consumed)).isNull()
        assertThat(allowsTutorial(share)).isFalse()
        assertThat(allowsTutorial(Intent().putExtra(EXTRA_OPEN_LIST, true))).isFalse()
        assertThat(allowsTutorial(Intent(Intent.ACTION_VIEW))).isFalse()
        assertThat(allowsTutorial(Intent(Intent.ACTION_MAIN))).isTrue()
    }

    @Test fun `finishing a tour survives graph reconstruction without changing privacy settings`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        ServiceLocator.reset()
        try {
            ServiceLocator.init(context)
            val settings = ServiceLocator.settingsStore.load()
            assertThat(ServiceLocator.tutorialPreferences.completed.value).isFalse()
            ServiceLocator.tutorialPreferences.complete()
            assertThat(ServiceLocator.tutorialPreferences.completed.value).isTrue()
            ServiceLocator.reset()
            ServiceLocator.init(context)
            assertThat(ServiceLocator.tutorialPreferences.completed.value).isTrue()
            assertThat(ServiceLocator.settingsStore.load()).isEqualTo(settings)
            assertThat(ServiceLocator.settingsStore.scanLookupEnabled()).isFalse()
        } finally { ServiceLocator.reset() }
    }
}

package io.theficos.comanda.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the capture band knows before anyone presses it.
 *
 * Design section 12 wants the keyboard fallback to be the same row with no
 * modal, which only works if the mode is decided in advance. These are the
 * checks that the deciding is real: an SDK gate that actually gates, a factory
 * whose null return lines up with the availability query the band asked, and a
 * capture path that produces an event rather than an exception on a device with
 * no recogniser at all.
 *
 * Robolectric provides no recognition service, so the emulated device here is
 * exactly the "STT unavailable" row of the failure table.
 */
@RunWith(RobolectricTestRunner::class)
class SpeechAvailabilityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * On-device recognition is an API 33 feature. The version gate has to short
     * circuit before the platform call, because on an older release that method
     * does not exist to be called.
     */
    @Test
    @Config(sdk = [26])
    fun `on-device recognition is never claimed below api 33`() {
        assertThat(SpeechCapture.isOnDeviceRecognitionAvailable(context)).isFalse()
        assertThat(SpeechCapture.captureMode(context)).isNotEqualTo(CaptureMode.ON_DEVICE)
    }

    /**
     * Above the gate the platform gets asked, and whatever it answers must not
     * be an exception. This is a query about an optional system service, and an
     * OEM build that answers it badly should cost the user the capture band,
     * not the settings screen that asked.
     */
    @Test
    @Config(sdk = [33])
    fun `asking about on-device recognition never throws`() {
        SpeechCapture.isOnDeviceRecognitionAvailable(context)
        SpeechCapture.isRecognitionAvailable(context)
        SpeechCapture.captureMode(context)
    }

    /**
     * The truth table, with the platform already asked.
     *
     * These two queries are separate platform calls and a device is free to
     * answer them inconsistently, so the decision is tested here rather than
     * through a test framework's shadow of an absent recognition service. The
     * platform-facing overload is covered by the two tests above; what matters
     * is that this table never sends the band to a recogniser that is not there.
     */
    @Test
    fun `the capture mode follows from the two availability answers`() {
        assertThat(SpeechCapture.captureMode(onDeviceAvailable = true, recognitionAvailable = true))
            .isEqualTo(CaptureMode.ON_DEVICE)
        assertThat(SpeechCapture.captureMode(onDeviceAvailable = false, recognitionAvailable = true))
            .isEqualTo(CaptureMode.NETWORK)
        assertThat(SpeechCapture.captureMode(onDeviceAvailable = false, recognitionAvailable = false))
            .isEqualTo(CaptureMode.KEYBOARD_ONLY)
    }

    /**
     * A device that claims an on-device recogniser while reporting no
     * recognition service at all is contradicting itself. Believing the
     * optimistic half would send the band to a recogniser that cannot be
     * created, and the failure would land mid-gesture, which is the one place
     * design section 12 says it must not.
     */
    @Test
    fun `a contradictory device falls back to the keyboard`() {
        assertThat(SpeechCapture.captureMode(onDeviceAvailable = true, recognitionAvailable = false))
            .isEqualTo(CaptureMode.KEYBOARD_ONLY)
    }

    /**
     * The failure table's "STT unavailable" row, end to end. A factory that
     * cannot build a recogniser produces an event the band can render, not a
     * thrown exception in the middle of a gesture.
     */
    @Test
    fun `capturing with no recogniser yields a keyboard fallback`() = runTest {
        val events = SpeechCapture(SpeechEngineFactory { null }).listen().toList()

        assertThat(events).containsExactly(CaptureEvent.Failed(CaptureFailure.UNAVAILABLE))
        assertThat(CaptureFailure.UNAVAILABLE.fallsBackToKeyboard).isTrue()
    }
}

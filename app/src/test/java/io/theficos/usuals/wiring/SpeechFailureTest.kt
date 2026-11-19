package io.theficos.usuals.wiring

import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.capture.CaptureFailure
import io.theficos.usuals.di.endOfCapture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which recogniser errors end dictation for the session, and which are just a
 * press that did not work.
 *
 * The view model latches on any exception this flow closes with, and nothing
 * ever clears that latch, so every code classified as fatal here costs the
 * person the app's primary gesture until they kill the process. The adapter
 * used to classify by hand and got the two commonest transient cases backwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SpeechFailureTest {

    /**
     * Several recognisers report the stop that immediately follows a start as a
     * client error, so this is what a mistap looks like. Reading it as a phone
     * that cannot listen would end dictation on a fumbled press.
     */
    @Test
    fun a_press_too_short_to_get_going_is_not_a_broken_microphone() {
        assertThat(endOfCapture(SpeechRecognizer.ERROR_CLIENT)).isNull()
    }

    /**
     * The point of the change, stated as a property rather than a list: every
     * code is read through `:capture`'s table, which has its own tests, so this
     * file cannot drift into a second, private classification again. The one
     * documented exception is the client error above.
     */
    @Test
    fun every_other_code_is_classified_by_capture_and_not_here() {
        val codes = 1..CaptureFailure.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS

        codes.filterNot { it == SpeechRecognizer.ERROR_CLIENT }.forEach { code ->
            val expected = CaptureFailure.fromRecognizerError(code)
            val ended = endOfCapture(code)

            assertThat(ended != null).isEqualTo(expected.fallsBackToKeyboard)
            if (ended != null) {
                assertThat(ended.failure).isEqualTo(expected)
                assertThat(ended.code).isEqualTo(code)
            }
        }
    }
}

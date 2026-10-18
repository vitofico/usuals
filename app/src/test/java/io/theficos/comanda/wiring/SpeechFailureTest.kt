package io.theficos.comanda.wiring

import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.capture.CaptureFailure
import io.theficos.comanda.di.endOfCapture
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

    @Test
    fun another_app_on_the_microphone_does_not_end_dictation() {
        // A phone call or a stray assistant activation. It frees up.
        assertThat(endOfCapture(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)).isNull()
    }

    @Test
    fun a_recognition_service_having_a_bad_moment_does_not_end_dictation() {
        assertThat(endOfCapture(SpeechRecognizer.ERROR_SERVER)).isNull()
        assertThat(endOfCapture(CaptureFailure.ERROR_SERVER_DISCONNECTED)).isNull()
    }

    @Test
    fun silence_is_an_ordinary_outcome_of_a_press() {
        assertThat(endOfCapture(SpeechRecognizer.ERROR_NO_MATCH)).isNull()
        assertThat(endOfCapture(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)).isNull()
    }

    /**
     * Several recognisers report the stop that immediately follows a start as a
     * client error, so this is what a mistap looks like. Reading it as a phone
     * that cannot listen would end dictation on a fumbled press.
     */
    @Test
    fun a_press_too_short_to_get_going_is_not_a_broken_microphone() {
        assertThat(endOfCapture(SpeechRecognizer.ERROR_CLIENT)).isNull()
    }

    @Test
    fun a_refused_microphone_opens_the_keyboard() {
        val ended = endOfCapture(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        assertThat(ended).isNotNull()
        assertThat(ended?.failure).isEqualTo(CaptureFailure.PERMISSION_DENIED)
    }

    @Test
    fun a_missing_language_model_opens_the_keyboard() {
        val ended = endOfCapture(CaptureFailure.ERROR_LANGUAGE_UNAVAILABLE)

        assertThat(ended).isNotNull()
        assertThat(ended?.failure).isEqualTo(CaptureFailure.LANGUAGE_UNAVAILABLE)
    }

    @Test
    fun a_recorder_that_will_not_read_opens_the_keyboard() {
        assertThat(endOfCapture(SpeechRecognizer.ERROR_AUDIO)?.failure)
            .isEqualTo(CaptureFailure.AUDIO)
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

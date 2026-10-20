package io.theficos.usuals.capture

import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The error table, as a table.
 *
 * Every code `SpeechRecognizer` documents has to land somewhere deliberate.
 * The failure mode this catches is a new platform error code quietly falling
 * into the default branch and telling the user "speech recognition failed" when
 * the truthful answer was "grant microphone permission".
 */
class CaptureFailureTest {

    private val mappings: List<Pair<Int, CaptureFailure>> = listOf(
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT to CaptureFailure.NETWORK,
        SpeechRecognizer.ERROR_NETWORK to CaptureFailure.NETWORK,
        SpeechRecognizer.ERROR_AUDIO to CaptureFailure.AUDIO,
        SpeechRecognizer.ERROR_SERVER to CaptureFailure.SERVICE,
        SpeechRecognizer.ERROR_CLIENT to CaptureFailure.UNKNOWN,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT to CaptureFailure.NO_SPEECH,
        SpeechRecognizer.ERROR_NO_MATCH to CaptureFailure.NO_MATCH,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY to CaptureFailure.BUSY,
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to CaptureFailure.PERMISSION_DENIED,
        CaptureFailure.ERROR_TOO_MANY_REQUESTS to CaptureFailure.RATE_LIMITED,
        CaptureFailure.ERROR_SERVER_DISCONNECTED to CaptureFailure.SERVICE,
        CaptureFailure.ERROR_LANGUAGE_NOT_SUPPORTED to CaptureFailure.LANGUAGE_UNAVAILABLE,
        CaptureFailure.ERROR_LANGUAGE_UNAVAILABLE to CaptureFailure.LANGUAGE_UNAVAILABLE,
        CaptureFailure.ERROR_CANNOT_CHECK_SUPPORT to CaptureFailure.UNAVAILABLE,
        CaptureFailure.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS to CaptureFailure.UNAVAILABLE,
    )

    @Test
    fun `every documented error code maps to a named failure`() {
        for ((code, expected) in mappings) {
            assertWithMessage("SpeechRecognizer error code $code")
                .that(CaptureFailure.fromRecognizerError(code))
                .isEqualTo(expected)
        }
    }

    /**
     * The literals used for the codes added in API 33 and 34 have to be the
     * platform's, or the table above is mapping numbers nothing sends. Checked
     * as a contiguous run because that is how the platform allocated them.
     */
    @Test
    fun `the api 33 and 34 codes are the ones the platform documents`() {
        assertThat(CaptureFailure.ERROR_TOO_MANY_REQUESTS).isEqualTo(10)
        assertThat(CaptureFailure.ERROR_SERVER_DISCONNECTED).isEqualTo(11)
        assertThat(CaptureFailure.ERROR_LANGUAGE_NOT_SUPPORTED).isEqualTo(12)
        assertThat(CaptureFailure.ERROR_LANGUAGE_UNAVAILABLE).isEqualTo(13)
        assertThat(CaptureFailure.ERROR_CANNOT_CHECK_SUPPORT).isEqualTo(14)
        assertThat(CaptureFailure.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS).isEqualTo(15)

        // Contiguous with the codes the older platform defined, so there is no
        // gap where an unhandled code could hide.
        val covered = mappings.map { it.first }.toSet()
        assertThat(covered).containsExactlyElementsIn((1..15).toList())
    }

    /**
     * An OEM recogniser inventing its own code, or a platform release adding
     * one after this build shipped. Falling back to the keyboard is the safe
     * answer: we cannot promise a retry will work when we do not know what
     * failed.
     */
    @Test
    fun `an unrecognised code falls back to the keyboard`() {
        for (code in listOf(0, -1, 16, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val failure = CaptureFailure.fromRecognizerError(code)

            assertWithMessage("code $code")
                .that(failure)
                .isEqualTo(CaptureFailure.UNKNOWN)
            assertThat(failure.fallsBackToKeyboard).isTrue()
        }
    }

    /**
     * The distinction the flag exists to draw. A misheard word is worth trying
     * again; a missing language model is not, and a band that keeps offering a
     * gesture which cannot work is worse than one that turns into a text field.
     */
    @Test
    fun `transient failures keep the microphone and terminal ones do not`() {
        val worthRetrying = listOf(
            CaptureFailure.NO_SPEECH,
            CaptureFailure.NO_MATCH,
            CaptureFailure.BUSY,
            CaptureFailure.SERVICE,
        )
        val notWorthRetrying = listOf(
            CaptureFailure.NETWORK,
            CaptureFailure.AUDIO,
            CaptureFailure.RATE_LIMITED,
            CaptureFailure.PERMISSION_DENIED,
            CaptureFailure.LANGUAGE_UNAVAILABLE,
            CaptureFailure.UNAVAILABLE,
            CaptureFailure.UNKNOWN,
        )

        for (failure in worthRetrying) {
            assertWithMessage("$failure").that(failure.fallsBackToKeyboard).isFalse()
        }
        for (failure in notWorthRetrying) {
            assertWithMessage("$failure").that(failure.fallsBackToKeyboard).isTrue()
        }
        // Both lists together are the whole enum, so a new case cannot be added
        // without someone deciding which side of the line it sits on.
        assertThat(worthRetrying + notWorthRetrying)
            .containsExactlyElementsIn(CaptureFailure.entries)
    }
}

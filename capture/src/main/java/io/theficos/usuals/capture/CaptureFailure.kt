package io.theficos.usuals.capture

import android.speech.SpeechRecognizer

/** A capture failure and whether retrying dictation in this session is useful. */
enum class CaptureFailure(val fallsBackToKeyboard: Boolean) {
    NO_SPEECH(false),
    NO_MATCH(false),
    BUSY(false),
    NETWORK(true),
    AUDIO(true),
    SERVICE(false),
    RATE_LIMITED(true),
    PERMISSION_DENIED(true),
    LANGUAGE_UNAVAILABLE(true),
    UNAVAILABLE(true),
    UNKNOWN(true),
    ;

    companion object {

        /**
         * Map an `android.speech.SpeechRecognizer.ERROR_*` code.
         *
         * Codes 10 through 15 arrived in API 33 and 34 and are written as
         * literals rather than by constant name, for two reasons. The module
         * compiles against whatever `compileSdk` is set today, and a phone on
         * an older release can still be handed one of these by a recogniser
         * implementation that is newer than its platform. Naming them in
         * comments keeps the table readable without tying it to an SDK level.
         */
        fun fromRecognizerError(errorCode: Int): CaptureFailure = when (errorCode) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> NETWORK
            SpeechRecognizer.ERROR_NETWORK -> NETWORK
            SpeechRecognizer.ERROR_AUDIO -> AUDIO
            SpeechRecognizer.ERROR_SERVER -> SERVICE
            SpeechRecognizer.ERROR_CLIENT -> UNKNOWN
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NO_SPEECH
            SpeechRecognizer.ERROR_NO_MATCH -> NO_MATCH
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> BUSY
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> PERMISSION_DENIED
            ERROR_TOO_MANY_REQUESTS -> RATE_LIMITED
            ERROR_SERVER_DISCONNECTED -> SERVICE
            ERROR_LANGUAGE_NOT_SUPPORTED -> LANGUAGE_UNAVAILABLE
            ERROR_LANGUAGE_UNAVAILABLE -> LANGUAGE_UNAVAILABLE
            ERROR_CANNOT_CHECK_SUPPORT -> UNAVAILABLE
            ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> UNAVAILABLE
            else -> UNKNOWN
        }

        /** `SpeechRecognizer.ERROR_TOO_MANY_REQUESTS`, API 33. */
        const val ERROR_TOO_MANY_REQUESTS: Int = 10

        /** `SpeechRecognizer.ERROR_SERVER_DISCONNECTED`, API 33. */
        const val ERROR_SERVER_DISCONNECTED: Int = 11

        /** `SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED`, API 33. */
        const val ERROR_LANGUAGE_NOT_SUPPORTED: Int = 12

        /** `SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE`, API 33. */
        const val ERROR_LANGUAGE_UNAVAILABLE: Int = 13

        /** `SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT`, API 33. */
        const val ERROR_CANNOT_CHECK_SUPPORT: Int = 14

        /** `SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS`, API 34. */
        const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS: Int = 15
    }
}

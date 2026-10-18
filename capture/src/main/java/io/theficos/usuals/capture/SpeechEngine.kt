package io.theficos.usuals.capture

/**
 * The slice of `android.speech.SpeechRecognizer` that [SpeechCapture] actually
 * uses.
 *
 * It exists so the capture logic can be tested without a recogniser.
 * `SpeechRecognizer` cannot be constructed off the main looper, cannot be
 * constructed at all without a real recognition service, and delivers its
 * results through a listener with fourteen methods, twelve of which we ignore.
 * Faking that in every test would mean writing a fake framework; faking three
 * methods is a page.
 *
 * The contract is deliberately narrow. An engine begins delivering to a sink
 * when [start] is called, keeps delivering until it reports a final result or
 * an error, and is single-use: [destroy] ends it and the instance is not
 * started again. That matches how a press-and-hold gesture works and removes
 * the state machine that a reusable engine would need.
 */
interface SpeechEngine {

    /**
     * Begin listening and deliver everything to [sink].
     *
     * @param locale a BCP 47 tag such as `es-ES`. The recogniser may not have a
     *   model for it, which surfaces as an error rather than an exception.
     */
    fun start(locale: String, sink: Sink)

    /**
     * Stop capturing audio, keep the result.
     *
     * This is what releasing the band does. The engine has heard everything it
     * is going to hear and now finishes turning it into text, so a final result
     * or an error still arrives after this returns. Calling it before [start],
     * or twice, does nothing.
     */
    fun stopListening()

    /** Release the underlying recogniser. Idempotent. */
    fun destroy()

    /** Where an engine's results go. Implemented by [SpeechCapture], not by callers. */
    interface Sink {

        /** Recognition so far. May be called any number of times, including none. */
        fun onPartial(text: String)

        /** The settled transcription. Terminal. */
        fun onFinal(text: String)

        /**
         * Terminal failure, as a raw `SpeechRecognizer.ERROR_*` code.
         *
         * The code is passed through unmapped so the mapping lives in exactly
         * one place, [CaptureFailure.fromRecognizerError], where it can be
         * tested as a table rather than reimplemented per engine.
         */
        fun onError(errorCode: Int)
    }
}

/**
 * Builds an engine, or reports that this device cannot.
 *
 * The nullable return is the seam that carries design section 12's "STT
 * unavailable" row: a device with no recogniser produces null here, and
 * [SpeechCapture] turns that into a [CaptureFailure.UNAVAILABLE] event rather
 * than an exception, so the band degrades to a text field instead of the
 * capture path throwing at the moment somebody presses it.
 */
fun interface SpeechEngineFactory {

    /** A fresh single-use engine, or null when speech recognition is not available. */
    fun create(): SpeechEngine?
}

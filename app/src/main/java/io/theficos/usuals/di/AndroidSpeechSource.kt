package io.theficos.usuals.di

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.theficos.usuals.capture.CaptureFailure
import io.theficos.usuals.ui.list.SpeechSource
import io.theficos.usuals.ui.list.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

/**
 * `SpeechRecognizer` behind the [SpeechSource] port.
 *
 * On API 33 and later this asks for the on-device recogniser first, which is
 * the whole reason capture can claim to be private: the audio never leaves the
 * phone, it works on the metro, and it costs nothing per utterance. Where that
 * is not available it falls back to whatever recogniser the phone has, which on
 * most devices means Google's, and that is a cloud round trip. The fallback is
 * deliberate and it is worth naming in settings when settings grows a privacy
 * section, because "on device" has an edge here and the app is supposed to
 * state its edges.
 *
 * What counts as a failure is not decided here. `:capture` already collapses
 * the recogniser's fifteen error codes into the handful of situations that lead
 * to different behaviour, and that table has a test suite, so this class asks
 * it rather than keeping a second opinion. A second opinion is exactly what
 * this file used to hold, and it got two cases wrong in the direction that
 * hurts: another app holding the microphone, or a recognition service having a
 * bad moment, both ended dictation for the rest of the session.
 *
 * Threading: `SpeechRecognizer` must be created, started and stopped on the
 * main thread. [listen] runs its whole body there through `flowOn`, and [stop]
 * is only ever called from the view model, which runs on the main dispatcher.
 */
class AndroidSpeechSource(private val context: Context) : SpeechSource {

    private var recognizer: SpeechRecognizer? = null

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun listen(): Flow<Transcript> = callbackFlow {
        val speech = createRecognizer()
        recognizer = speech

        speech.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                val heard = bestGuess(partialResults)
                if (heard.isNotBlank()) trySend(Transcript(heard, isFinal = false))
            }

            override fun onResults(results: Bundle?) {
                trySend(Transcript(bestGuess(results), isFinal = true))
                close()
            }

            override fun onError(error: Int) {
                close(endOfCapture(error))
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        speech.startListening(recognitionIntent())

        awaitClose {
            speech.stopListening()
            speech.destroy()
            if (recognizer === speech) recognizer = null
        }
    }.flowOn(Dispatchers.Main.immediate)

    override fun stop() {
        recognizer?.stopListening()
    }

    private fun createRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    /**
     * No `EXTRA_LANGUAGE`: the recogniser uses the phone's language, which is
     * the right default and the only one that does not need a settings screen
     * to correct. A household that writes its list in a language the phone is
     * not set to will need that setting, and it belongs in settings rather than
     * hardcoded here.
     */
    private fun recognitionIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun bestGuess(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
}

/**
 * How a capture ends, given the code the recogniser reported.
 *
 * Null means the flow closes quietly, the partial that already arrived is
 * committed, and the band keeps offering the microphone. An exception means the
 * view model stops offering it and opens the keyboard instead, for the rest of
 * the session.
 *
 * The classification is `:capture`'s, not this file's. Its
 * [CaptureFailure.fallsBackToKeyboard] already answers exactly the question
 * being asked here, "is the microphone worth offering again", and it is pinned
 * by a test suite. The two cases that used to be wrong are the two that hurt
 * most in a shop: another app holding the microphone, and a recognition service
 * having a bad moment, both of which killed dictation for the rest of the
 * session over something that would have worked on the next press.
 *
 * The one code read locally rather than through the table is
 * `ERROR_CLIENT`. Several recognisers report the stop that immediately follows
 * a start as a client error, so it is the ordinary outcome of a mistap, and
 * `:capture` can only see it as an unknown code from a listener it does not
 * own. Turning a short press into a phone that cannot listen would be the worse
 * mistake of the two.
 */
internal fun endOfCapture(errorCode: Int): SpeechRecognitionFailed? {
    if (errorCode == SpeechRecognizer.ERROR_CLIENT) return null
    val failure = CaptureFailure.fromRecognizerError(errorCode)
    return if (failure.fallsBackToKeyboard) SpeechRecognitionFailed(errorCode, failure) else null
}

/**
 * A recogniser that cannot listen, as opposed to one that heard nothing. The
 * view model treats this as the signal to stop offering dictation and open the
 * keyboard instead, so the code is worth carrying rather than swallowing.
 *
 * [failure] is `:capture`'s reading of [code]: which of the handful of real
 * situations this was, as a structured code. The UI can localize a specific
 * failure without storing display copy in the capture module.
 */
class SpeechRecognitionFailed(
    val code: Int,
    val failure: CaptureFailure = CaptureFailure.fromRecognizerError(code),
) : Exception("speech recognition failed, code $code, read as $failure")

package io.theficos.comanda.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The [SpeechEngineFactory] that builds real recognisers.
 *
 * Availability is checked here rather than inside the engine, so that "this
 * phone cannot do speech" is answered before a capture starts and can be
 * rendered as a keyboard band instead of a failed gesture. Design section 12.
 */
class AndroidSpeechEngineFactory(
    context: Context,
    private val preferOnDevice: Boolean = true,
) : SpeechEngineFactory {

    private val appContext = context.applicationContext

    override fun create(): SpeechEngine? {
        if (!SpeechCapture.isRecognitionAvailable(appContext)) return null
        val onDevice = preferOnDevice && SpeechCapture.isOnDeviceRecognitionAvailable(appContext)
        return AndroidSpeechEngine(appContext, onDevice)
    }
}

/**
 * One press-and-hold, over `android.speech.SpeechRecognizer`.
 *
 * Everything awkward about the platform class is contained here, which is why
 * [SpeechCapture] can be tested without it.
 *
 * `SpeechRecognizer` must be created and driven on the main looper, and it
 * throws if it is not, so every call in this class hops there. The alternative,
 * requiring callers to collect the capture flow on `Dispatchers.Main`, is a
 * rule that holds until the first time someone does not know about it.
 *
 * The engine is single-use and terminal-once. The platform can deliver an error
 * after a result, or two errors, particularly on OEM implementations, and a
 * capture that emitted twice would put two items on the list from one
 * utterance. [terminated] makes the first terminal event the only one.
 */
internal class AndroidSpeechEngine(
    private val context: Context,
    private val onDevice: Boolean,
) : SpeechEngine {

    private val main = Handler(Looper.getMainLooper())
    private val terminated = AtomicBoolean(false)

    private val lock = Any()
    private var recognizer: SpeechRecognizer? = null

    /** Set when [stopListening] or [destroy] arrives before the recogniser exists. */
    private var stopPending = false
    private var destroyed = false

    override fun start(locale: String, sink: SpeechEngine.Sink) {
        onMain {
            val alreadyDone = synchronized(lock) { destroyed || recognizer != null }
            if (alreadyDone) return@onMain

            val created = runCatching { createRecognizer() }.getOrNull()
            if (created == null) {
                // The factory said a recogniser was available and the platform
                // then declined to build one. Nothing here is retryable, so
                // report the generic client failure, which falls back to the
                // keyboard.
                deliver(sink) { it.onError(SpeechRecognizer.ERROR_CLIENT) }
                return@onMain
            }

            created.setRecognitionListener(Listener(sink))

            val shouldStopImmediately = synchronized(lock) {
                recognizer = created
                stopPending
            }

            val started = runCatching { created.startListening(intentFor(locale)) }.isSuccess
            if (!started) {
                deliver(sink) { it.onError(SpeechRecognizer.ERROR_CLIENT) }
                return@onMain
            }

            // The band was released before the recogniser finished starting.
            // Rare, and exactly what happens on a very short tap.
            if (shouldStopImmediately) runCatching { created.stopListening() }
        }
    }

    override fun stopListening() {
        onMain {
            val current = synchronized(lock) {
                stopPending = true
                recognizer
            }
            runCatching { current?.stopListening() }
        }
    }

    override fun destroy() {
        onMain {
            // Null when this is a second destroy, which makes the call
            // idempotent without a second exit path.
            val current = synchronized(lock) {
                if (destroyed) {
                    null
                } else {
                    destroyed = true
                    val existing = recognizer
                    recognizer = null
                    existing
                }
            }
            runCatching { current?.destroy() }
        }
    }

    private fun createRecognizer(): SpeechRecognizer? =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    private fun intentFor(locale: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // One alternative is all we want. The list takes what was said, and
            // an n-best list would only invite a disambiguation UI that gets in
            // the way of the two-second capture.
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Ask the network recogniser to stay offline too, when it can. On a
            // phone with an on-device model this is redundant; on one without,
            // it is the difference between working in a basement and not.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
        }

    /** Runs [block] only if no terminal event has been delivered yet. */
    private fun deliver(sink: SpeechEngine.Sink, block: (SpeechEngine.Sink) -> Unit) {
        if (terminated.compareAndSet(false, true)) block(sink)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private inner class Listener(private val sink: SpeechEngine.Sink) : RecognitionListener {

        override fun onPartialResults(partialResults: Bundle?) {
            if (terminated.get()) return
            firstResult(partialResults)?.let(sink::onPartial)
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            if (text == null) {
                // A final result with nothing in it is the platform's way of
                // saying it heard nothing it could turn into words.
                deliver(sink) { it.onError(SpeechRecognizer.ERROR_NO_MATCH) }
            } else {
                deliver(sink) { it.onFinal(text) }
            }
        }

        override fun onError(error: Int) {
            deliver(sink) { it.onError(error) }
        }

        private fun firstResult(bundle: Bundle?): String? = bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

        // The rest of the listener surface is audio-level and lifecycle noise.
        // The band does not render a waveform, so none of it is used.
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

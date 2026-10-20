package io.theficos.usuals.capture

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Speech capture as a flow: press the band, speak, release, get a line of text.
 *
 * The primary job in design section 1 is capturing an item in under two
 * seconds, hands wet, at an open fridge. Everything here follows from that.
 * Partial results are emitted as they arrive so the row grows while you speak.
 * One [listen] call is one utterance, so the flow completes on the first final
 * result and the caller never has to reason about whether a later result amends
 * an earlier one. And nothing throws: every way this can fail arrives as a
 * [CaptureEvent.Failed] carrying a reason the band can show, because a capture
 * path that throws loses the thing the person just said.
 *
 * The recogniser is injected as a [SpeechEngineFactory] rather than built here,
 * which is what makes the whole class testable on the JVM. Use [forContext] to
 * get the real one.
 *
 * Threading: [listen] can be collected from any dispatcher. The Android engine
 * marshals itself onto the main looper, because `SpeechRecognizer` insists.
 */
class SpeechCapture(private val engines: SpeechEngineFactory) {

    /**
     * The engine of the capture currently in flight, so [stop] can reach it.
     *
     * Atomic rather than a plain field because the release gesture and the flow
     * teardown arrive from different threads, and the failure mode of losing
     * that race is a recogniser that keeps the microphone open after the user
     * has moved on.
     */
    private val active = AtomicReference<SpeechEngine?>(null)

    /**
     * Capture one utterance.
     *
     * Collect this while the band is held. Release the band by calling [stop],
     * which lets the recogniser finish and deliver its final result; cancelling
     * the collection instead abandons the utterance, which is what "slide away
     * to cancel" would do.
     *
     * @param locale BCP 47. Defaults to Spanish, the household language the
     *   product is designed around.
     */
    fun listen(locale: String = DEFAULT_LOCALE): Flow<CaptureEvent> = callbackFlow {
        val engine = engines.create()
        if (engine == null) {
            // Design section 12: no recogniser is a state, not a crash.
            trySend(CaptureEvent.Failed(CaptureFailure.UNAVAILABLE))
            close()
            return@callbackFlow
        }

        if (!active.compareAndSet(null, engine)) {
            // A second capture while one is in flight. The microphone is a
            // single resource and the platform would refuse anyway, so refuse
            // here where we can say something useful about it.
            engine.destroy()
            trySend(CaptureEvent.Failed(CaptureFailure.BUSY))
            close()
            return@callbackFlow
        }

        val sink = object : SpeechEngine.Sink {
            override fun onPartial(text: String) {
                // Blank partials are common while the recogniser warms up, and
                // an empty row flickering into the list is worse than no row.
                if (text.isNotBlank()) trySend(CaptureEvent.Partial(text))
            }

            override fun onFinal(text: String) {
                trySend(CaptureEvent.Final(text))
                close()
            }

            override fun onError(errorCode: Int) {
                trySend(CaptureEvent.Failed(CaptureFailure.fromRecognizerError(errorCode)))
                close()
            }
        }

        engine.start(locale, sink)

        awaitClose {
            active.compareAndSet(engine, null)
            engine.destroy()
        }
    }

    /**
     * Release the band.
     *
     * Audio capture stops; the final result still arrives. No-op when nothing
     * is being captured, so a stray release after a failure is harmless.
     */
    fun stop() {
        active.get()?.stopListening()
    }

    companion object {

        /** Spanish, the household language design section 11 writes the list in. */
        const val DEFAULT_LOCALE: String = "es-ES"

        /**
         * A [SpeechCapture] over the platform recogniser.
         *
         * @param preferOnDevice ask for the on-device recogniser when the
         *   platform has one. Defaults to true: design section 4 puts speech
         *   capture on device because it is free, private, and works on the
         *   metro, and this is where that preference is expressed.
         */
        fun forContext(context: Context, preferOnDevice: Boolean = true): SpeechCapture =
            SpeechCapture(AndroidSpeechEngineFactory(context, preferOnDevice))

        /**
         * Whether this device can recognise speech without a network round
         * trip.
         *
         * On-device recognition is an API 33 feature, so the version gate comes
         * first and the platform call is never reached below it. The result is
         * wrapped because this is a query about an optional system service and
         * an OEM build that answers it badly should degrade the capture band,
         * not take down the settings screen that asked.
         */
        fun isOnDeviceRecognitionAvailable(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            return runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            }.getOrDefault(false)
        }

        /** Whether this device has any recogniser at all, on device or not. */
        fun isRecognitionAvailable(context: Context): Boolean =
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

        /**
         * What the capture band should offer here, decided before the user
         * touches it.
         *
         * Design section 12 wants the keyboard fallback to be the same row with
         * no modal, which means the band has to know its state in advance
         * rather than discovering it by failing mid-gesture.
         */
        fun captureMode(context: Context): CaptureMode =
            captureMode(
                onDeviceAvailable = isOnDeviceRecognitionAvailable(context),
                recognitionAvailable = isRecognitionAvailable(context),
            )

        /**
         * The decision itself, with the two platform queries already made.
         *
         * Split out so the truth table can be tested without a device and
         * without a test framework's idea of what an absent recognition
         * service looks like. The platform is asked in the overload above;
         * this is the part that has to be right.
         *
         * On-device wins when it is there. A device that reports an on-device
         * recogniser but no recognition service at all is contradicting
         * itself, and the honest answer to a contradiction is the fallback
         * that always works.
         */
        internal fun captureMode(
            onDeviceAvailable: Boolean,
            recognitionAvailable: Boolean,
        ): CaptureMode = when {
            !recognitionAvailable -> CaptureMode.KEYBOARD_ONLY
            onDeviceAvailable -> CaptureMode.ON_DEVICE
            else -> CaptureMode.NETWORK
        }
    }
}

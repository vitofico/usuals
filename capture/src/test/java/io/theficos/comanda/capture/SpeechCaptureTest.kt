package io.theficos.comanda.capture

import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Capture with no recogniser anywhere in sight.
 *
 * `SpeechRecognizer` cannot be built off the main looper and needs a real
 * recognition service, so the injected [SpeechEngineFactory] is what makes
 * these tests possible at all. What is under test is the part with the
 * behaviour in it: which events come out, in what order, when the flow ends,
 * and whether the microphone is released afterwards.
 */
class SpeechCaptureTest {

    /** A recogniser the test drives by hand. */
    private class FakeSpeechEngine : SpeechEngine {

        var startedLocale: String? = null
            private set
        var stopCount = 0
            private set
        var destroyCount = 0
            private set

        private var sink: SpeechEngine.Sink? = null

        override fun start(locale: String, sink: SpeechEngine.Sink) {
            startedLocale = locale
            this.sink = sink
        }

        override fun stopListening() {
            stopCount++
        }

        override fun destroy() {
            destroyCount++
        }

        fun hear(text: String) = sink?.onPartial(text)
        fun settle(text: String) = sink?.onFinal(text)
        fun fail(errorCode: Int) = sink?.onError(errorCode)
    }

    /** Hands out engines in order, then reports that there are none left. */
    private class QueuedEngines(vararg engines: SpeechEngine?) : SpeechEngineFactory {
        private val queue = ArrayDeque(engines.toList())
        override fun create(): SpeechEngine? =
            if (queue.isEmpty()) null else queue.removeFirst()
    }

    @Test
    fun `emits partials as they arrive and a final at the end`() = runTest {
        val engine = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(engine))

        val events = async { capture.listen().toList() }
        runCurrent()

        engine.hear("dos")
        engine.hear("dos litros")
        engine.settle("dos litros de leche")

        assertThat(events.await()).containsExactly(
            CaptureEvent.Partial("dos"),
            CaptureEvent.Partial("dos litros"),
            CaptureEvent.Final("dos litros de leche"),
        ).inOrder()
    }

    @Test
    fun `the flow ends after the final result`() = runTest {
        val engine = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(engine))

        val events = async { capture.listen().toList() }
        runCurrent()
        engine.settle("pan de payés")

        // Completing is the contract: one listen is one utterance, so a caller
        // never has to decide whether a later result amends this one.
        assertThat(events.await()).containsExactly(CaptureEvent.Final("pan de payés"))
    }

    @Test
    fun `anything the engine says after the final result is dropped`() = runTest {
        val engine = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(engine))

        val events = async { capture.listen().toList() }
        runCurrent()
        engine.settle("tomates")
        engine.hear("tomates cherry")
        engine.fail(SpeechRecognizer.ERROR_SERVER)

        assertThat(events.await()).containsExactly(CaptureEvent.Final("tomates"))
    }

    /**
     * A recogniser warming up emits empty partials. An empty row flickering
     * into the list is worse than no row.
     */
    @Test
    fun `blank partials are not emitted`() = runTest {
        val engine = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(engine))

        val events = async { capture.listen().toList() }
        runCurrent()
        engine.hear("")
        engine.hear("   ")
        engine.hear("huevos")
        engine.settle("huevos")

        assertThat(events.await()).containsExactly(
            CaptureEvent.Partial("huevos"),
            CaptureEvent.Final("huevos"),
        ).inOrder()
    }

    @Test
    fun `an engine error becomes a failure event and ends the flow`() = runTest {
        val engine = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(engine))

        val events = async { capture.listen().toList() }
        runCurrent()
        engine.hear("sal")
        engine.fail(SpeechRecognizer.ERROR_NO_MATCH)

        assertThat(events.await()).containsExactly(
            CaptureEvent.Partial("sal"),
            CaptureEvent.Failed(CaptureFailure.NO_MATCH),
        ).inOrder()
    }

    /**
     * Design section 12: an unavailable recogniser is a state, not a crash.
     * Nothing here throws, and the failure carries the flag the band uses to
     * turn itself into a text field.
     */
    @Test
    fun `no recogniser produces a keyboard fallback rather than an exception`() = runTest {
        val capture = SpeechCapture(QueuedEngines(null))

        val events = capture.listen().toList()

        assertThat(events).containsExactly(CaptureEvent.Failed(CaptureFailure.UNAVAILABLE))
        assertThat(CaptureFailure.UNAVAILABLE.fallsBackToKeyboard).isTrue()
    }

    @Test
    fun `the locale reaches the engine and defaults to spanish`() = runTest {
        val engine = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(engine))

        val events = async { capture.listen().toList() }
        runCurrent()
        assertThat(engine.startedLocale).isEqualTo("es-ES")
        assertThat(SpeechCapture.DEFAULT_LOCALE).isEqualTo("es-ES")
        engine.settle("leche")
        events.await()

        val english = FakeSpeechEngine()
        val other = SpeechCapture(QueuedEngines(english))
        val more = async { other.listen(locale = "en-GB").toList() }
        runCurrent()
        assertThat(english.startedLocale).isEqualTo("en-GB")
        english.settle("milk")
        more.await()
    }

    @Test
    fun `stop releases the band without ending the capture`() = runTest {
        val engine = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(engine))

        val events = async { capture.listen().toList() }
        runCurrent()
        engine.hear("aceite de")
        capture.stop()

        assertThat(engine.stopCount).isEqualTo(1)

        // Releasing the band stops the microphone, not the recognition. The
        // final result still arrives, which is the whole reason stop() exists
        // instead of cancelling the collection.
        engine.settle("aceite de oliva")

        assertThat(events.await()).containsExactly(
            CaptureEvent.Partial("aceite de"),
            CaptureEvent.Final("aceite de oliva"),
        ).inOrder()
    }

    @Test
    fun `stop with nothing in flight does nothing`() {
        val engine = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(engine))

        capture.stop()

        assertThat(engine.stopCount).isEqualTo(0)
    }

    @Test
    fun `the engine is destroyed when the capture finishes`() = runTest {
        val engine = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(engine))

        val events = async { capture.listen().toList() }
        runCurrent()
        engine.settle("pan")
        events.await()

        assertThat(engine.destroyCount).isEqualTo(1)
    }

    /**
     * Abandoning a capture, which is what sliding a thumb off the band would
     * do. The microphone has to come back, or the next press is refused by the
     * platform and the band looks broken.
     */
    @Test
    fun `cancelling the collection releases the recogniser`() = runTest {
        val engine = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(engine))

        val job = launch { capture.listen().toList() }
        runCurrent()
        engine.hear("tomat")
        job.cancelAndJoin()

        assertThat(engine.destroyCount).isEqualTo(1)
    }

    /**
     * The microphone is one resource. A second capture while one is in flight
     * is refused here, where something useful can be said about it, rather than
     * by the platform throwing on the analysis thread.
     */
    @Test
    fun `a second capture while one is in flight is refused as busy`() = runTest {
        val first = FakeSpeechEngine()
        val second = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(first, second))

        val firstEvents = async { capture.listen().toList() }
        runCurrent()

        val secondEvents = capture.listen().toList()

        assertThat(secondEvents).containsExactly(CaptureEvent.Failed(CaptureFailure.BUSY))
        // The engine that could not be used is released rather than left
        // holding a recogniser nobody will ever start.
        assertThat(second.destroyCount).isEqualTo(1)
        assertThat(second.startedLocale).isNull()

        first.settle("leche")
        assertThat(firstEvents.await()).containsExactly(CaptureEvent.Final("leche"))
    }

    @Test
    fun `a capture can be started again once the previous one finished`() = runTest {
        val first = FakeSpeechEngine()
        val second = FakeSpeechEngine()
        val capture = SpeechCapture(QueuedEngines(first, second))

        val firstEvents = async { capture.listen().toList() }
        runCurrent()
        first.settle("leche")
        firstEvents.await()

        val secondEvents = async { capture.listen().toList() }
        runCurrent()
        second.settle("pan")

        assertThat(secondEvents.await()).containsExactly(CaptureEvent.Final("pan"))
    }
}

package io.theficos.usuals.ui.list

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** One reading of what the person is saying. */
data class Transcript(val text: String, val isFinal: Boolean)

/**
 * Dictation, reduced to the three things the capture band does with it.
 *
 * Press and hold calls [listen] and collects partials; release calls [stop],
 * after which the recogniser delivers its last result and the flow completes.
 * The band never waits on a callback to end the gesture: whatever has arrived
 * by the time the flow completes is what gets parsed, so a recogniser that goes
 * quiet loses the tail of an utterance rather than losing the whole capture.
 *
 * [isAvailable] is a claim about this phone right now, not a version check.
 * When it is false the band opens a text field in the same place instead, with
 * no modal and no apology.
 */
interface SpeechSource {

    fun isAvailable(): Boolean

    /**
     * Starts recognition and emits transcripts until [stop] is called, the
     * recogniser gives up, or the collecting coroutine is cancelled. At most
     * one transcript has `isFinal = true`, and it is the last one.
     */
    fun listen(): Flow<Transcript>

    fun stop()
}

/**
 * A phone with no recogniser, for a preview or a test that wants the keyboard
 * path. Saying honestly that it cannot listen is what drives the fallback, so
 * this is a working degradation rather than a stub: everything still captures,
 * through a text field in the same place on screen.
 */
object NoSpeechSource : SpeechSource {
    override fun isAvailable(): Boolean = false
    override fun listen(): Flow<Transcript> = emptyFlow()
    override fun stop() = Unit
}

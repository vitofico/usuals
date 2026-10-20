package io.theficos.usuals.capture

/**
 * What one press-and-hold of the capture band produces.
 *
 * The band is held down, words appear in the list as they are recognised, and
 * on release one row is left behind. That is three kinds of event and no more:
 * text that is still changing, text that is settled, and a reason there will
 * not be any.
 *
 * A stream of these ends after the first [Final] or [Failed]. One utterance,
 * one flow, so a caller never has to decide whether a second [Final] amends the
 * first or starts a new item.
 */
sealed interface CaptureEvent {

    /**
     * Recognition so far, which will change. This is what grows the row while
     * somebody is still speaking, per the visual direction in design section
     * 11, and it is never what gets committed to the list.
     */
    data class Partial(val text: String) : CaptureEvent

    /** The settled transcription. The last event of a successful capture. */
    data class Final(val text: String) : CaptureEvent

    /**
     * Recognition will not produce anything this time. Carries the reason so
     * the band can say what happened and what to do, per design section 11's
     * copy rules, instead of a generic shrug.
     */
    data class Failed(val reason: CaptureFailure) : CaptureEvent
}

/**
 * What the capture band should offer on this device, decided before anyone
 * presses anything.
 *
 * Design section 12 says an unavailable recogniser falls back to the keyboard
 * in the same row with no modal. That only works if the fallback is known up
 * front rather than discovered by failing, which is what this is for: the band
 * renders one of these three states and never has to apologise mid-gesture.
 */
enum class CaptureMode {

    /**
     * Speech recognition that runs on the phone. API 33 and up with a model
     * installed for the language, per design section 4: free, private, and it
     * works on the metro.
     */
    ON_DEVICE,

    /**
     * Speech recognition that goes through the network. Still the platform
     * recogniser, so still no key and no cost to us, but it is a round trip and
     * it stops working in a basement.
     */
    NETWORK,

    /**
     * No usable recogniser. The band becomes a text field. This is a normal
     * state, not an error state: nothing about the list product depends on
     * speech.
     */
    KEYBOARD_ONLY,
}

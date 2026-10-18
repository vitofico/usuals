package io.theficos.comanda.capture

import io.theficos.comanda.model.Gtin

/**
 * Decides whether a decoded barcode is a new scan or the same one still being
 * held in front of the lens.
 *
 * The camera hands over frames at thirty a second and a hand holding a carton
 * steady produces a clean decode in most of them. Without this, one carton of
 * milk is thirty items on the list, which is a worse outcome than not scanning
 * at all.
 *
 * The rule has two halves and the second one matters:
 *
 * - The same code, again, within [windowMillis], is the same scan. Ignored.
 * - A different code is always a new scan, immediately. Somebody working
 *   through a bag of shopping moves from one item to the next in well under a
 *   second, and making them wait out a timer between different products would
 *   be the app fighting them.
 *
 * Time comes in through [clock] rather than being read here, so a test can hold
 * a barcode in frame for a simulated two seconds without sleeping for two real
 * ones, and so the window's behaviour is a property rather than a flake.
 */
class ScanDebounce(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private var lastGtin: Gtin? = null
    private var lastAtMillis: Long = 0L

    /**
     * True when [gtin] should be reported to the caller.
     *
     * Accepting updates the window, so a code held continuously in frame keeps
     * being suppressed rather than resurfacing once the window since the first
     * frame elapses. Deliberately scanning the same product twice works, it
     * just needs the code out of frame, or a pause, for the window.
     */
    fun accept(gtin: Gtin): Boolean = synchronized(lock) {
        val now = clock()
        val repeat = gtin == lastGtin && now - lastAtMillis < windowMillis
        lastGtin = gtin
        lastAtMillis = now
        !repeat
    }

    /**
     * Forget what was last seen, so the next decode reports whatever it is.
     *
     * For the point where a scan has been dealt with and the scanner is ready
     * for a fresh one, such as reopening the scanner after adding the item.
     */
    fun reset() {
        synchronized(lock) {
            lastGtin = null
            lastAtMillis = 0L
        }
    }

    companion object {
        /**
         * Long enough to cover holding a carton steady while the row animates
         * in, short enough that putting the same product down and picking it up
         * again is not mysteriously ignored.
         */
        const val DEFAULT_WINDOW_MILLIS: Long = 2_000L
    }
}

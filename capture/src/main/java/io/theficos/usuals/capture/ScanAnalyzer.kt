package io.theficos.usuals.capture

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import io.theficos.usuals.model.Gtin
import java.nio.ByteBuffer

/**
 * The CameraX end of the scanner, and the only file in this module that knows
 * CameraX exists.
 *
 * It delegates the reading: pull the luminance plane out of
 * a frame, hand it to [BarcodeDecoder], confirm matching frames, and let
 * [ScanDebounce] decide whether it is the same carton still in frame.
 * The stride arithmetic, decode, confirmation and debounce can all be tested
 * on the JVM against buffers a test drew itself.
 *
 * The frame is YUV_420_888, whose first plane is luminance: one byte of
 * brightness per pixel, which is exactly what a barcode reader wants and what
 * the colour planes would only get in the way of.
 *
 * [onScanned] is called on the analysis executor, not the main thread. Anything
 * that touches UI state has to hop, and the caller does that rather than this
 * class assuming a looper it was not given.
 */
class ScanAnalyzer(
    private val decoder: BarcodeDecoder = BarcodeDecoder(),
    private val debounce: ScanDebounce = ScanDebounce(),
    private val onScanned: (Gtin) -> Unit,
) : ImageAnalysis.Analyzer {

    private var candidate: Gtin? = null
    private var matchingFrames = 0
    private var missedFrames = 0

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull()
            if (plane != null) {
                onFrame(
                    plane = plane.buffer,
                    width = image.width,
                    height = image.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                )
            }
        } catch (_: Throwable) {
            // Never let a decode failure escape into CameraX. It would be
            // thrown once per frame on the analysis thread, which is thirty
            // stack traces a second drowning out whatever actually broke, and
            // a frame that cannot be read is a no-read, not an incident.
        } finally {
            // Always close, whatever happened. An unclosed proxy stalls the
            // pipeline after a single frame under STRATEGY_KEEP_ONLY_LATEST,
            // and the symptom is a preview that renders while the scanner
            // quietly stops seeing anything.
            image.close()
        }
    }

    /**
     * The analyzer without CameraX in the way: everything [analyze] does once
     * the frame has been unwrapped.
     *
     * Internal so `ScanAnalyzerTest` can drive a whole scan session, decode,
     * debounce and callback, from plain buffers.
     */
    internal fun onFrame(
        plane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ) {
        val gtin = decoder.decode(plane, width, height, rowStride, pixelStride)
        if (gtin == null) {
            // A small or curved barcode is a marginal read that succeeds in
            // some frames only. Misses between agreeing reads keep the count;
            // only a sustained gap means the product has left the frame.
            if (++missedFrames >= FORGET_AFTER_MISSES) {
                candidate = null
                matchingFrames = 0
            }
            return
        }
        missedFrames = 0
        if (gtin != candidate) {
            candidate = gtin
            matchingFrames = 1
        } else {
            matchingFrames = (matchingFrames + 1).coerceAtMost(REQUIRED_MATCHING_FRAMES)
        }
        // Motion can produce a wrong code with a valid checksum in one frame.
        // Only repeated agreement, with no other code between, may report it.
        if (matchingFrames == REQUIRED_MATCHING_FRAMES && debounce.accept(gtin)) {
            onScanned(gtin)
        }
    }

    /** Forget the previous confirmation and debounce window. */
    fun reset() {
        candidate = null
        matchingFrames = 0
        missedFrames = 0
        debounce.reset()
    }

    private companion object {
        const val REQUIRED_MATCHING_FRAMES = 3
        /** About half a second of frames. */
        const val FORGET_AFTER_MISSES = 15
    }
}

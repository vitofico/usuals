package io.theficos.comanda.capture

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.theficos.comanda.model.Gtin
import java.nio.ByteBuffer

/**
 * Turns a frame of luminance into a canonical [Gtin], or nothing.
 *
 * Plain ZXing, no ML Kit, because ML Kit drags in Play Services and design
 * section 13 has already decided against that: F-Droid distribution is the
 * plan, and a Play Services dependency is cheap to avoid from commit one and an
 * excavation later. quire made the same call for the same reason.
 *
 * There is deliberately no CameraX import in this file. That is what lets a
 * barcode be decoded in a JVM unit test from a bitmap the test drew itself,
 * which in turn is what makes the scanner something we can be confident about
 * without holding a carton of milk up to a phone. [ScanAnalyzer] is the piece
 * that touches CameraX, and it is thin because this is not.
 *
 * A decoder instance is not safe to share across threads: `MultiFormatReader`
 * carries per-decode state. One analysis pipeline, one decoder, which is how
 * [ScanAnalyzer] uses it.
 */
class BarcodeDecoder {

    private val reader = MultiFormatReader()

    /**
     * Decode a tightly-packed luminance plane.
     *
     * @param luminances one byte of brightness per pixel, `width * height` of
     *   them, row-major. Extra bytes past that are ignored; fewer is a no-read.
     * @return the canonical EAN-13, or null when the frame holds no barcode we
     *   accept. A code that decodes but fails its check digit is a no-read too:
     *   [Gtin.parse] is the only way a [Gtin] comes into existence, and
     *   [ScanAnalyzer] confirms repeated camera reads before insertion.
     */
    fun decode(luminances: ByteArray, width: Int, height: Int): Gtin? {
        if (width <= 0 || height <= 0) return null
        if (luminances.size < width * height) return null

        decodeText(PlanarYUVLuminanceSource(luminances, width, height, 0, 0, width, height, false))
            ?.let { return Gtin.parse(it) }

        // On a miss, try the frame transposed.
        //
        // ZXing's EAN readers scan horizontal rows and a single decode() does
        // not try rotated variants, so a barcode that landed sideways in the
        // buffer is a permanent no-read rather than a hard one. That is the
        // normal case, not an edge one: a phone held in portrait has its sensor
        // mounted landscape, so a barcode held the natural way across a carton
        // arrives rotated a quarter turn. PlanarYUVLuminanceSource refuses to
        // rotate itself in ZXing 3.5, so we transpose the bytes.
        val rotated = transpose(luminances, width, height)
        val rotatedSource =
            PlanarYUVLuminanceSource(rotated, height, width, 0, 0, height, width, false)
        return decodeText(rotatedSource)?.let { Gtin.parse(it) }
    }

    /**
     * Decode straight from a camera plane, honouring its strides.
     *
     * Takes a `java.nio.ByteBuffer` rather than a CameraX type on purpose: the
     * stride arithmetic is where this goes wrong in practice, and keeping it on
     * this side of the CameraX boundary means it can be tested with a buffer a
     * test built by hand.
     *
     * @param rowStride bytes from the start of one row to the start of the
     *   next. Generally larger than [width]: the hardware pads rows for
     *   alignment, so the buffer is not a tight `width * height` array and
     *   treating it as one shears the image into diagonal noise.
     * @param pixelStride bytes between adjacent luminance samples, usually 1.
     */
    fun decode(
        plane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): Gtin? {
        val packed = pack(plane, width, height, rowStride, pixelStride) ?: return null
        return decode(packed, width, height)
    }

    private fun decodeText(source: LuminanceSource): String? {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decode(bitmap, HINTS).text
        } catch (_: Exception) {
            // A no-read is the common case, not an error: most frames of a
            // moving camera contain no barcode. ZXing signals it by throwing,
            // so this is where that convention is translated into a null.
            null
        } finally {
            reader.reset()
        }
    }

    internal companion object {

        /**
         * The three symbologies a consumer grocery product carries, matching
         * what [Gtin] accepts. Restricting the set is not only about speed: an
         * unrestricted reader will happily return a Code 128 shipping label or
         * a QR code from the packaging, and neither is a product identity.
         *
         * Hints are passed to `decode(bitmap, hints)` on every call rather than
         * stashed once with `setHints`, because the single-argument `decode`
         * calls `setHints(null)` internally and would silently discard them.
         */
        private val HINTS: Map<DecodeHintType, Any> = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
            ),
            // A carton held at arm's length in kitchen light is a hard read.
            // The analyzer confirms matching frames before accepting a scan.
            DecodeHintType.TRY_HARDER to true,
        )

        /**
         * Copy a strided plane into a tight `width * height` array, or null if
         * the buffer is too short for the geometry it claims.
         */
        internal fun pack(
            plane: ByteBuffer,
            width: Int,
            height: Int,
            rowStride: Int,
            pixelStride: Int,
        ): ByteArray? {
            if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null
            // A stride that cannot hold a row describes no image at all, and
            // reading it would fold each row into the next.
            if (rowStride < width * pixelStride) return null

            val lastByte = (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride
            if (lastByte >= plane.limit()) return null

            // Absolute reads throughout, including the common tightly-packed
            // case. A bulk copy would be marginally faster and would be a
            // second path through the only arithmetic in this file that is easy
            // to get wrong. One path, exercised by every test here, is worth
            // more than the microseconds.
            //
            // Reading absolutely also leaves the caller's buffer position
            // untouched, so nothing depends on where CameraX happened to leave
            // it and two decodes of the same plane agree.
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                var source = y * rowStride
                val destination = y * width
                for (x in 0 until width) {
                    out[destination + x] = plane.get(source)
                    source += pixelStride
                }
            }
            return out
        }

        /**
         * Transpose a `width * height` luminance array into a `height * width`
         * one, so that `dst[x, y] == src[y, x]`. A horizontal feature in the
         * source becomes a vertical one, which is exactly what presents a
         * sideways barcode upright to a row scanner.
         */
        internal fun transpose(source: ByteArray, width: Int, height: Int): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                val rowBase = y * width
                for (x in 0 until width) {
                    // The destination is `height` wide: row x, column y.
                    out[x * height + y] = source[rowBase + x]
                }
            }
            return out
        }
    }
}

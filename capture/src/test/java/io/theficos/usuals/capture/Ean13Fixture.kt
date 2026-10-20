package io.theficos.usuals.capture

import java.nio.ByteBuffer

/** A tightly-packed luminance frame, one byte of brightness per pixel. */
internal class LuminanceFrame(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Draws EAN-13 barcodes.
 *
 * The scanner is the highest-quality entry the alias cache can receive (design
 * section 5), so it is worth being sure it works, and being sure means decoding
 * a barcode rather than asserting that a decoder was called. Photographing a
 * carton is not something a unit test can do, but rendering the symbol is: the
 * EAN-13 encoding is a fixed table and a parity rule, and the result is exactly
 * what a camera would see if it were pointed at a very well printed label.
 *
 * The symbol is 95 modules: a `101` guard, six left digits of seven modules
 * each, a `01010` centre guard, six right digits, and a closing `101`. The
 * first digit is not drawn at all; it is encoded in which of two alphabets the
 * six left digits use, which is why [PARITY] exists.
 */
internal object Ean13Fixture {

    /** Left-hand odd-parity alphabet, digits 0 through 9. */
    private val L = arrayOf(
        "0001101", "0011001", "0010011", "0111101", "0100011",
        "0110001", "0101111", "0111011", "0110111", "0001011",
    )

    /** Left-hand even-parity alphabet. */
    private val G = arrayOf(
        "0100111", "0110011", "0011011", "0100001", "0011101",
        "0111001", "0000101", "0010001", "0001001", "0010111",
    )

    /** Right-hand alphabet, the bitwise complement of [L]. */
    private val R = arrayOf(
        "1110010", "1100110", "1101100", "1000010", "1011100",
        "1001110", "1010000", "1000100", "1001000", "1110100",
    )

    /**
     * Which alphabet each of the six left digits uses, indexed by the first
     * digit. This is the whole of how thirteen digits fit into twelve slots.
     */
    private val PARITY = arrayOf(
        "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
        "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL",
    )

    private const val BLACK: Byte = 0
    private const val WHITE: Byte = -1 // 0xFF as a signed byte.

    /** The 95-module bar pattern for [ean13], as '1' for bar and '0' for space. */
    fun modulePattern(ean13: String): String {
        require(ean13.length == 13) { "EAN-13 has 13 digits, got '${ean13}'" }
        val digits = ean13.map { it - '0' }
        require(digits.all { it in 0..9 }) { "non-digit in '$ean13'" }

        val parity = PARITY[digits[0]]
        val bars = StringBuilder(95)
        bars.append("101")
        for (i in 1..6) {
            bars.append(if (parity[i - 1] == 'L') L[digits[i]] else G[digits[i]])
        }
        bars.append("01010")
        for (i in 7..12) {
            bars.append(R[digits[i]])
        }
        bars.append("101")
        return bars.toString()
    }

    /**
     * Render [ean13] as a luminance frame: black bars on white, uniform down
     * every row, with a quiet zone either side.
     *
     * The quiet zone is not decoration. A 1D reader needs white space before
     * the start guard to find the edge of the symbol, and a barcode drawn flush
     * to the frame edge is a no-read on a real label too.
     */
    fun frame(
        ean13: String,
        moduleWidth: Int = 3,
        height: Int = 96,
        quietModules: Int = 12,
    ): LuminanceFrame {
        val bars = modulePattern(ean13)
        val width = (bars.length + 2 * quietModules) * moduleWidth

        val row = ByteArray(width) { WHITE }
        var x = quietModules * moduleWidth
        for (bar in bars) {
            val value = if (bar == '1') BLACK else WHITE
            repeat(moduleWidth) { row[x++] = value }
        }

        val bytes = ByteArray(width * height)
        for (y in 0 until height) {
            System.arraycopy(row, 0, bytes, y * width, width)
        }
        return LuminanceFrame(bytes, width, height)
    }

    /**
     * The same frame turned a quarter turn, which is how a barcode held the
     * natural way across a carton arrives from a portrait-held phone.
     */
    fun transposed(frame: LuminanceFrame): LuminanceFrame {
        val out = ByteArray(frame.width * frame.height)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                out[x * frame.height + y] = frame.bytes[y * frame.width + x]
            }
        }
        return LuminanceFrame(out, frame.height, frame.width)
    }

    /**
     * Wrap [frame] in a buffer shaped like a real camera plane: rows padded to
     * [rowStride] and samples [pixelStride] apart, with the gaps filled with
     * something conspicuous so that an implementation which ignores the strides
     * produces sheared noise rather than a slightly wrong but decodable image.
     */
    fun strided(
        frame: LuminanceFrame,
        rowStride: Int,
        pixelStride: Int = 1,
        filler: Byte = 0x5A,
    ): ByteBuffer {
        require(rowStride >= frame.width * pixelStride) { "rowStride is too small" }
        val out = ByteArray(rowStride * frame.height) { filler }
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                out[y * rowStride + x * pixelStride] = frame.bytes[y * frame.width + x]
            }
        }
        return ByteBuffer.wrap(out)
    }

    /** A buffer over the frame exactly as it is, no padding. */
    fun tight(frame: LuminanceFrame): ByteBuffer = ByteBuffer.wrap(frame.bytes)
}

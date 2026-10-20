package io.theficos.usuals.capture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.theficos.usuals.model.Gtin
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

/**
 * Decodes barcodes the test drew itself.
 *
 * No CameraX, no camera, no fixture image checked into the repo that nobody can
 * regenerate. The EAN-13 encoding is deterministic, so the frame these tests
 * decode is the same frame every time, on every machine.
 */
class BarcodeDecoderTest {

    private val decoder = BarcodeDecoder()

    @Test
    fun `decodes an EAN-13 to its canonical form`() {
        val frame = Ean13Fixture.frame(LECHE_EAN13)

        val gtin = decoder.decode(frame.bytes, frame.width, frame.height)

        assertThat(gtin).isEqualTo(Gtin.parse(LECHE_EAN13))
        assertThat(gtin?.value).isEqualTo(LECHE_EAN13)
    }

    /**
     * A UPC-A symbol is an EAN-13 whose first digit is zero, and ZXing hands it
     * back as twelve digits. [Gtin] widens it, which is the point of having one
     * canonical identity: the same jar scanned from Spanish and American
     * packaging keys the alias cache the same way.
     */
    @Test
    fun `widens a UPC-A to thirteen digits`() {
        val frame = Ean13Fixture.frame(UPC_A_AS_EAN13)

        val gtin = decoder.decode(frame.bytes, frame.width, frame.height)

        assertThat(gtin?.value).isEqualTo(UPC_A_AS_EAN13)
        assertThat(gtin?.value).hasLength(13)
    }

    /**
     * The rotation path, which is the normal case rather than an edge one: a
     * phone held in portrait has a landscape sensor, so a barcode running
     * across a carton lands sideways in the buffer. ZXing scans rows and does
     * not rotate for us.
     */
    @Test
    fun `decodes a barcode that landed sideways in the frame`() {
        val sideways = Ean13Fixture.transposed(Ean13Fixture.frame(LECHE_EAN13))

        val gtin = decoder.decode(sideways.bytes, sideways.width, sideways.height)

        assertThat(gtin?.value).isEqualTo(LECHE_EAN13)
    }

    @Test
    fun `decodes across a range of module widths`() {
        for (moduleWidth in listOf(2, 3, 4, 6)) {
            val frame = Ean13Fixture.frame(LECHE_EAN13, moduleWidth = moduleWidth)

            assertWithMessage("moduleWidth=$moduleWidth")
                .that(decoder.decode(frame.bytes, frame.width, frame.height)?.value)
                .isEqualTo(LECHE_EAN13)
        }
    }

    @Test
    fun `a decoder can be reused across frames`() {
        val first = Ean13Fixture.frame(LECHE_EAN13)
        val second = Ean13Fixture.frame(ACEITE_EAN13)

        assertThat(decoder.decode(first.bytes, first.width, first.height)?.value)
            .isEqualTo(LECHE_EAN13)
        assertThat(decoder.decode(second.bytes, second.width, second.height)?.value)
            .isEqualTo(ACEITE_EAN13)
        assertThat(decoder.decode(first.bytes, first.width, first.height)?.value)
            .isEqualTo(LECHE_EAN13)
    }

    @Test
    fun `noise is a no-read, not a wrong read`() {
        // Fixed seed: this frame is the same on every machine and every run.
        val random = Random(20260910L)
        val width = 360
        val height = 96
        val noise = ByteArray(width * height)
        random.nextBytes(noise)

        assertThat(decoder.decode(noise, width, height)).isNull()
    }

    @Test
    fun `a blank frame is a no-read`() {
        val width = 360
        val height = 96

        assertThat(decoder.decode(ByteArray(width * height), width, height)).isNull()
        assertThat(decoder.decode(ByteArray(width * height) { -1 }, width, height)).isNull()
    }

    @Test
    fun `degenerate geometry is a no-read rather than a crash`() {
        val frame = Ean13Fixture.frame(LECHE_EAN13)

        assertThat(decoder.decode(frame.bytes, 0, frame.height)).isNull()
        assertThat(decoder.decode(frame.bytes, frame.width, 0)).isNull()
        assertThat(decoder.decode(frame.bytes, -1, -1)).isNull()
        assertThat(decoder.decode(ByteArray(0), frame.width, frame.height)).isNull()
        // A buffer one byte short of the geometry it claims. ZXing would read
        // off the end of it.
        assertThat(
            decoder.decode(
                ByteArray(frame.width * frame.height - 1),
                frame.width,
                frame.height,
            ),
        ).isNull()
    }

    // ---- camera-plane geometry -------------------------------------------

    @Test
    fun `decodes from a tightly packed plane buffer`() {
        val frame = Ean13Fixture.frame(LECHE_EAN13)
        val plane = Ean13Fixture.tight(frame)

        val gtin = decoder.decode(plane, frame.width, frame.height, frame.width, 1)

        assertThat(gtin?.value).isEqualTo(LECHE_EAN13)
    }

    /**
     * The bug this module would otherwise ship with. Camera hardware pads each
     * row for alignment, so the plane is wider than the image; reading it as a
     * tight array shifts every row a little further left than the last and
     * turns a barcode into diagonal hash.
     */
    @Test
    fun `decodes from a plane whose rows are padded`() {
        val frame = Ean13Fixture.frame(LECHE_EAN13)
        val plane = Ean13Fixture.strided(frame, rowStride = frame.width + 37)

        val gtin = decoder.decode(plane, frame.width, frame.height, frame.width + 37, 1)

        assertThat(gtin?.value).isEqualTo(LECHE_EAN13)
    }

    @Test
    fun `decodes from a plane with interleaved samples`() {
        val frame = Ean13Fixture.frame(LECHE_EAN13)
        val rowStride = frame.width * 2 + 16
        val plane = Ean13Fixture.strided(frame, rowStride = rowStride, pixelStride = 2)

        val gtin = decoder.decode(plane, frame.width, frame.height, rowStride, 2)

        assertThat(gtin?.value).isEqualTo(LECHE_EAN13)
    }

    @Test
    fun `reading a plane leaves the caller's buffer position alone`() {
        val frame = Ean13Fixture.frame(LECHE_EAN13)
        val rowStride = frame.width + 8
        val plane = Ean13Fixture.strided(frame, rowStride = rowStride)
        plane.position(11)

        decoder.decode(plane, frame.width, frame.height, rowStride, 1)

        assertThat(plane.position()).isEqualTo(11)
    }

    @Test
    fun `a plane too short for its declared geometry is a no-read`() {
        val frame = Ean13Fixture.frame(LECHE_EAN13)
        val short = ByteBuffer.wrap(ByteArray(frame.width * 4))

        assertThat(decoder.decode(short, frame.width, frame.height, frame.width, 1)).isNull()
        // A row stride that cannot hold a row is incoherent, not merely tight.
        assertThat(
            decoder.decode(
                Ean13Fixture.tight(frame),
                frame.width,
                frame.height,
                frame.width - 1,
                1,
            ),
        ).isNull()
    }

    private companion object {
        /**
         * Spanish GS1 prefix (84), valid check digit. Stands in for a carton of
         * leche entera. The last test in this class re-derives that validity
         * through production code rather than taking it on trust.
         */
        const val LECHE_EAN13 = "8480000123459"

        /** Another valid EAN-13, aceite de oliva, for the reuse test. */
        const val ACEITE_EAN13 = "8410010101017"

        /** The classic UPC-A "012345678905", widened. */
        const val UPC_A_AS_EAN13 = "0012345678905"
    }

    /**
     * The fixtures have to be genuine barcodes or every test above is decoding
     * something that could never be printed. [Gtin.parse] is the production
     * check digit, so this asserts against the same rule the app uses.
     */
    @Test
    fun `fixtures are real barcodes`() {
        for (code in listOf(LECHE_EAN13, ACEITE_EAN13, UPC_A_AS_EAN13)) {
            assertWithMessage("fixture '$code' fails its check digit")
                .that(Gtin.parse(code))
                .isNotNull()
        }
    }
}

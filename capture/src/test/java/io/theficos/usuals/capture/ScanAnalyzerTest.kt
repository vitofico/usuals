package io.theficos.usuals.capture

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.Gtin
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

/**
 * The scanner end to end, minus CameraX.
 *
 * `ScanAnalyzer.analyze` does exactly two things a test cannot usefully check
 * without a camera: it pulls the luminance plane out of an `ImageProxy` and it
 * closes the proxy afterwards. Everything downstream of that is
 * `ScanAnalyzer.onFrame`, and driving it from buffers this test builds covers
 * the decode, the stride handling and the debounce as one piece, which is how
 * they run in the kitchen.
 */
class ScanAnalyzerTest {

    private class FakeClock(var nowMillis: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = nowMillis
        fun advance(millis: Long) {
            nowMillis += millis
        }
    }

    private val scanned = mutableListOf<Gtin>()
    private val clock = FakeClock()

    private fun analyzer(windowMillis: Long = 2_000L) = ScanAnalyzer(
        decoder = BarcodeDecoder(),
        debounce = ScanDebounce(windowMillis = windowMillis, clock = clock),
        onScanned = { scanned += it },
    )

    /** Feed one tightly-packed frame, the way a camera with no row padding would. */
    private fun ScanAnalyzer.feed(frame: LuminanceFrame) {
        onFrame(
            plane = Ean13Fixture.tight(frame),
            width = frame.width,
            height = frame.height,
            rowStride = frame.width,
            pixelStride = 1,
        )
    }

    private fun ScanAnalyzer.confirm(frame: LuminanceFrame) {
        repeat(3) { feed(frame) }
    }

    @Test
    fun `isolated valid checksum misreads do not insert phantom products`() {
        val subject = analyzer()

        for (code in listOf("9000384333931", "7006401333931", "8006462333931")) {
            val frame = Ean13Fixture.frame(code)
            assertThat(BarcodeDecoder().decode(frame.bytes, frame.width, frame.height)?.value)
                .isEqualTo(code)
            subject.feed(frame)
        }
        assertThat(scanned).isEmpty()

        subject.confirm(Ean13Fixture.frame(LECHE))
        subject.feed(Ean13Fixture.frame(ACEITE))
        subject.confirm(Ean13Fixture.frame(LECHE))

        assertThat(scanned.map { it.value }).containsExactly(LECHE)
    }

    @Test
    fun `a no-read interrupts a partial confirmation`() {
        val subject = analyzer()
        val frame = Ean13Fixture.frame(LECHE)
        repeat(2) { subject.feed(frame) }
        subject.onFrame(ByteBuffer.wrap(ByteArray(64)), frame.width, frame.height, frame.width, 1)
        repeat(2) { subject.feed(frame) }
        assertThat(scanned).isEmpty()

        subject.feed(frame)
        assertThat(scanned.map { it.value }).containsExactly(LECHE)
    }

    @Test
    fun `reset discards a partial confirmation`() {
        val subject = analyzer()
        val frame = Ean13Fixture.frame(LECHE)
        repeat(2) { subject.feed(frame) }
        subject.reset()
        subject.feed(frame)
        assertThat(scanned).isEmpty()

        repeat(2) { subject.feed(frame) }
        assertThat(scanned.map { it.value }).containsExactly(LECHE)
    }

    @Test
    fun `reports the canonical gtin after three matching frames`() {
        val subject = analyzer()

        subject.confirm(Ean13Fixture.frame(LECHE))

        assertThat(scanned.map { it.value }).containsExactly(LECHE)
    }

    /**
     * Thirty frames a second of the same carton is one item. This is the whole
     * reason the analyzer holds a debounce rather than just a decoder.
     */
    @Test
    fun `a carton held in frame produces one item`() {
        val subject = analyzer()
        val frame = Ean13Fixture.frame(LECHE)

        repeat(150) {
            subject.feed(frame)
            clock.advance(33L)
        }

        assertThat(scanned.map { it.value }).containsExactly(LECHE)
    }

    @Test
    fun `moving to another product reports it after confirmation without a timer`() {
        val subject = analyzer()

        subject.confirm(Ean13Fixture.frame(LECHE))
        clock.advance(120L)
        subject.confirm(Ean13Fixture.frame(ACEITE))

        assertThat(scanned.map { it.value }).containsExactly(LECHE, ACEITE).inOrder()
    }

    @Test
    fun `frames with no barcode report nothing`() {
        val subject = analyzer()
        val random = Random(20260910L)
        val width = 360
        val height = 96
        val noise = ByteArray(width * height).also { random.nextBytes(it) }

        subject.onFrame(ByteBuffer.wrap(noise), width, height, width, 1)
        subject.onFrame(ByteBuffer.wrap(ByteArray(width * height)), width, height, width, 1)

        assertThat(scanned).isEmpty()
    }

    /**
     * A frame shaped the way real camera hardware delivers one: rows padded to
     * an aligned stride. If the analyzer ignored the stride this would decode
     * as nothing at all, and the scanner would look like it had simply stopped
     * working.
     */
    @Test
    fun `decodes a padded camera plane`() {
        val subject = analyzer()
        val frame = Ean13Fixture.frame(LECHE)
        val rowStride = frame.width + 48

        repeat(3) {
            subject.onFrame(
                plane = Ean13Fixture.strided(frame, rowStride = rowStride),
                width = frame.width,
                height = frame.height,
                rowStride = rowStride,
                pixelStride = 1,
            )
        }

        assertThat(scanned.map { it.value }).containsExactly(LECHE)
    }

    @Test
    fun `a sideways carton still scans`() {
        val subject = analyzer()
        val sideways = Ean13Fixture.transposed(Ean13Fixture.frame(LECHE))

        subject.confirm(sideways)

        assertThat(scanned.map { it.value }).containsExactly(LECHE)
    }

    @Test
    fun `reset lets the same product be scanned again`() {
        val subject = analyzer()
        val frame = Ean13Fixture.frame(LECHE)

        subject.confirm(frame)
        subject.confirm(frame)
        subject.reset()
        subject.confirm(frame)

        assertThat(scanned.map { it.value }).containsExactly(LECHE, LECHE)
    }

    @Test
    fun `scanning a second carton of the same milk works after the window`() {
        val subject = analyzer(windowMillis = 2_000L)
        val frame = Ean13Fixture.frame(LECHE)

        subject.confirm(frame)
        clock.advance(2_500L)
        subject.confirm(frame)

        assertThat(scanned).hasSize(2)
    }

    @Test
    fun `an incoherent frame is ignored rather than thrown`() {
        val subject = analyzer()
        val frame = Ean13Fixture.frame(LECHE)

        // A buffer that cannot hold the geometry it claims, which is what a
        // truncated or half-written frame looks like.
        subject.onFrame(ByteBuffer.wrap(ByteArray(64)), frame.width, frame.height, frame.width, 1)
        subject.onFrame(Ean13Fixture.tight(frame), 0, 0, 0, 0)

        assertThat(scanned).isEmpty()
    }

    private companion object {
        const val LECHE = "8480000123459"
        const val ACEITE = "8410010101017"
    }
}

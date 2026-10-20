package io.theficos.usuals.capture

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

/** Invitation QR contents never enter the GTIN capture pipeline. */
class QrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader()
    private var delivered = false
    override fun analyze(image: ImageProxy) {
        try {
            if (delivered) return
            val plane = image.planes.firstOrNull() ?: return
            val bytes = BarcodeDecoder.pack(plane.buffer, image.width, image.height, plane.rowStride, plane.pixelStride) ?: return
            val text = decode(bytes, image.width, image.height) ?: return
            if (text.length <= 4096 && (text.startsWith("usuals://join#") || text.startsWith("usuals://device#"))) {
                delivered = true
                onCode(text)
            }
        } catch (_: Exception) {
            // Unreadable frames are expected; the proxy must still be released.
        } finally { image.close() }
    }
    internal fun decode(bytes: ByteArray, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
        reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)).text
    } catch (_: ReaderException) { null } finally { reader.reset() }
}

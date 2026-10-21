package io.theficos.usuals.capture

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test

class QrAnalyzerTest {
    private fun frame(text: String): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480)
        return ByteArray(480 * 480) { if (matrix[it % 480, it / 480]) 0 else 255.toByte() }
    }

    @Test fun readsVerificationQrAndRejectsAnEmptyFrame() {
        val text = "usuals://device#synthetic-public-fixture"
        val analyzer = QrAnalyzer({ true }) { error("No camera callback in decoder test") }
        assertThat(analyzer.decode(frame(text), 480, 480)).isEqualTo(text)
        assertThat(analyzer.decode(ByteArray(480 * 480) { 255.toByte() }, 480, 480)).isNull()
    }

    @Test fun deliversAcceptedHttpsServerLinkOnce() {
        val delivered = mutableListOf<String>()
        val analyzer = QrAnalyzer({ it.startsWith("https://") }) { delivered += it }
        analyzer.offer(frame("https://usuals.example/connect"), 480, 480)
        analyzer.offer(frame("https://usuals.example/connect"), 480, 480)
        assertThat(delivered).containsExactly("https://usuals.example/connect")
    }

    @Test fun unrelatedCodesKeepTheCameraReading() {
        val delivered = mutableListOf<String>()
        val analyzer = QrAnalyzer({ it.startsWith("https://usuals.example") }) { delivered += it }
        analyzer.offer(frame("https://shop.example/product/42"), 480, 480)
        assertThat(delivered).isEmpty()
        analyzer.offer(frame("https://usuals.example/connect"), 480, 480)
        assertThat(delivered).containsExactly("https://usuals.example/connect")
    }
}

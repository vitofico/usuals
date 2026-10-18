package io.theficos.comanda.capture

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test

class QrAnalyzerTest {
    @Test fun readsVerificationQrAndRejectsAnEmptyFrame() {
        val text = "comanda://device#synthetic-public-fixture"
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480)
        val frame = ByteArray(480 * 480) { if (matrix[it % 480, it / 480]) 0 else 255.toByte() }
        val analyzer = QrAnalyzer { error("No camera callback in decoder test") }
        assertThat(analyzer.decode(frame, 480, 480)).isEqualTo(text)
        assertThat(analyzer.decode(ByteArray(480 * 480) { 255.toByte() }, 480, 480)).isNull()
    }
}

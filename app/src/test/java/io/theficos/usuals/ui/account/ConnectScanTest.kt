package io.theficos.usuals.ui.account

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.Wire
import org.junit.Test

class ConnectScanTest {
    private fun code(bytes: Int) = "https://usuals.example.org/connect#1." + Wire.encode(ByteArray(bytes) { it.toByte() })

    @Test fun theWebAppQrCarriesAnOfferIdAndSecretOf48Bytes() {
        val scan = ConnectScan.read(code(48))
        assertThat(scan).isInstanceOf(ConnectScan.Offer::class.java)
        assertThat((scan as ConnectScan.Offer).origin).isEqualTo("https://usuals.example.org")
        assertThat(scan.toString()).doesNotContain("connect#")
    }

    @Test fun theAppLinkFormWrapsTheSameCode() {
        val link = "usuals://connect#" + Wire.encode(code(48).toByteArray())
        assertThat(ConnectScan.read(link)).isEqualTo(ConnectScan.Offer(link, "https://usuals.example.org"))
    }

    @Test fun aCodeOfAnyOtherLengthIsRejectedAsOutdated() {
        assertThat(ConnectScan.read(code(112))).isEqualTo(ConnectScan.Outdated)
        assertThat(ConnectScan.read(code(47))).isEqualTo(ConnectScan.Outdated)
        assertThat(ConnectScan.read(code(49))).isEqualTo(ConnectScan.Outdated)
        assertThat(ConnectScan.recognizes(code(112))).isTrue()
    }

    @Test fun aServerAddressSignsInThroughTheBrowserAndAnythingElseIsUnknown() {
        assertThat(ConnectScan.read(" https://usuals.example.org ")).isEqualTo(ConnectScan.Server("https://usuals.example.org"))
        assertThat(ConnectScan.read("not a code")).isEqualTo(ConnectScan.Unknown)
        assertThat(ConnectScan.read("https://usuals.example.org/connect#1.!!!")).isEqualTo(ConnectScan.Unknown)
        assertThat(ConnectScan.recognizes("x".repeat(600))).isFalse()
    }
}

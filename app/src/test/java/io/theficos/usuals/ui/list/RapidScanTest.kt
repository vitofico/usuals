package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.ui.scan.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class RapidScanTest {
    @get:Rule val main = MainDispatcherRule()
    private val milk = Gtin.parse("4006381333931")!!
    private val cocoa = Gtin.parse("3017620422003")!!

    @Test fun `enabling names retries only the lookup and skipping ignores a late retry`() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        var enabled = false
        var delayed = false
        val gate = CompletableDeferred<Unit>()
        val scans = object : ScanStore {
            override suspend fun lookup(gtin: Gtin): ScanNameOffer {
                if (delayed) gate.await()
                return ScanNameOffer(gtin, if (enabled) "milk" else null, null, enabled,
                    if (enabled) null else ScanNameProblem.LOOKUP_DISABLED)
            }
            override suspend fun add(offer: ScanNameOffer, name: String): ItemId = error("nothing is confirmed here")
        }
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), scanStore = scans, compute = main.dispatcher)
        vm.onScanned(milk)
        assertThat(vm.state.value.scanNames.single().problem).isEqualTo(ScanNameProblem.LOOKUP_DISABLED)
        enabled = true
        vm.onRetryScanName(milk)
        assertThat(vm.state.value.scanNames.single().name).isEqualTo("milk")
        delayed = true
        vm.onRetryScanName(milk)
        assertThat(vm.state.value.scanNames).isEmpty()
        assertThat(vm.state.value.scanPending).containsExactly(milk)
        vm.onSkipScanNames()
        gate.complete(Unit)
        assertThat(vm.state.value.scanNames).isEmpty()
        assertThat(vm.state.value.scanPending).isEmpty()
        assertThat(store.current).isEmpty()
    }

    @Test fun `rapid scans ask once per barcode and add only what is confirmed`() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        var lookups = 0
        val confirmed = mutableListOf<Pair<Gtin, String>>()
        val scans = object : ScanStore {
            override suspend fun lookup(gtin: Gtin): ScanNameOffer { lookups++; return ScanNameOffer(gtin, null, null, false) }
            override suspend fun add(offer: ScanNameOffer, name: String): ItemId {
                confirmed += offer.gtin to name
                return ItemId("scan")
            }
        }
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), scanStore = scans, compute = main.dispatcher)
        repeat(10) { vm.onScanned(milk) }
        vm.onScanned(cocoa)
        assertThat(lookups).isEqualTo(2)
        assertThat(vm.state.value.scanNames.map { it.gtin }).containsExactly(milk, cocoa).inOrder()
        assertThat(store.current).isEmpty()

        vm.onConfirmScanName(milk, milk.value)
        assertThat(confirmed).isEmpty()
        vm.onConfirmScanName(milk, " milk ")
        assertThat(confirmed).containsExactly(milk to " milk ")
        assertThat(vm.state.value.lastScan).isEqualTo(ScanResult(milk, "milk"))

        vm.onSkipScanName(cocoa)
        assertThat(vm.state.value.lastScan).isEqualTo(ScanResult(cocoa, null))
        assertThat(vm.state.value.scanNames).isEmpty()
        assertThat(confirmed).hasSize(1)
    }
}

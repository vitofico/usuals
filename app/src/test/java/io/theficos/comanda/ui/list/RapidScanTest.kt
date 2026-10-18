package io.theficos.comanda.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.comanda.model.*
import io.theficos.comanda.parse.LocalUtteranceParser
import io.theficos.comanda.ui.scan.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class RapidScanTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun `enabling names retries only the lookup and skipping ignores a late retry`() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        var added = 0
        var enabled = false
        var delayed = false
        val gate = CompletableDeferred<Unit>()
        val id = ItemId("scan")
        val scans = object : ScanStore {
            override suspend fun add(gtin: Gtin): ItemId {
                added++
                store.add(listOf(Item(id, gtin.value, gtin.value, gtin.value, gtin = gtin, createdAt = 0, updatedAt = 0)))
                return id
            }
            override suspend fun lookup(id: ItemId, gtin: Gtin): ScanNameOffer {
                if (delayed) gate.await()
                return ScanNameOffer(id, gtin, if (enabled) "milk" else null, null, enabled,
                    if (enabled) null else ScanNameProblem.LOOKUP_DISABLED)
            }
            override suspend fun confirm(offer: ScanNameOffer, name: String) = true
        }
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), scanStore = scans)
        vm.onScanned(Gtin.parse("4006381333931")!!)
        assertThat(vm.state.value.scanNames.single().problem).isEqualTo(ScanNameProblem.LOOKUP_DISABLED)
        enabled = true
        vm.onRetryScanName(id)
        assertThat(vm.state.value.scanNames.single().name).isEqualTo("milk")
        assertThat(added).isEqualTo(1)
        delayed = true
        vm.onRetryScanName(id)
        assertThat(vm.state.value.scanNames).isEmpty()
        vm.onSkipScanNames()
        gate.complete(Unit)
        assertThat(vm.state.value.scanNames).isEmpty()
        assertThat(added).isEqualTo(1)
    }
    @Test fun `rapid scan rows are visible while naming waits and skip all ignores late offers`() = runTest(main.dispatcher) {
        val store = FakeItemStore()
        val gate = CompletableDeferred<Unit>()
        var count = 0
        val scans = object : ScanStore {
            override suspend fun add(gtin: Gtin): ItemId {
                val id = ItemId("scan-${++count}")
                store.add(listOf(Item(id, gtin.value, gtin.value, gtin.value, gtin = gtin, createdAt = 0, updatedAt = 0)))
                return id
            }
            override suspend fun lookup(id: ItemId, gtin: Gtin): ScanNameOffer { if (id.value.substringAfterLast('-').toInt() > 2) gate.await(); return ScanNameOffer(id, gtin, null, null, false) }
            override suspend fun confirm(offer: ScanNameOffer, name: String) = true
        }
        val vm = ListViewModel(store, LocalUtteranceParser(), FakeSpeechSource(false), scanStore = scans)
        repeat(10) { vm.onScanned(Gtin.parse("4006381333931")!!) }
        assertThat(vm.state.value.pending).hasSize(10)
        assertThat(vm.state.value.scanNames).hasSize(2)
        vm.onSkipScanNames()
        gate.complete(Unit)
        assertThat(vm.state.value.scanNames).isEmpty()
    }
}

package io.theficos.usuals.wiring

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.data.local.ListEntity
import io.theficos.usuals.di.ServiceLocator
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.testing.FakeAndroidKeyStore
import io.theficos.usuals.ui.history.HistoryDeletionUnsupported
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryDeletionWiringTest {
    @Before fun init() {
        FakeAndroidKeyStore.setup()
        ServiceLocator.init(ApplicationProvider.getApplicationContext())
    }

    @After fun reset() = ServiceLocator.reset()

    @Test fun offlineListKeepsHistoryDeletion(): Unit = runBlocking {
        val scope = seedLocalHistory()
        val shop = ServiceLocator.historyRepository.snapshot().trips.single()
        assertThat(ServiceLocator.historyStore.deleteTrip(shop.id, scope)).isTrue()
        val remaining = ServiceLocator.historyRepository.snapshot()
        assertThat(remaining.trips).isEmpty()
        assertThat(remaining.purchases).isEmpty()
    }

    @Test fun syncedListWithoutRelayCapabilityPreservesHistory(): Unit = runBlocking {
        val scope = seedLocalHistory()
        val before = ServiceLocator.historyRepository.snapshot()
        val list = requireNotNull(ServiceLocator.database.listDao().find(scope))
        ServiceLocator.database.listDao().update(list.copy(synced = true))
        val error = runCatching { ServiceLocator.historyStore.deleteTrip(before.trips.single().id, scope) }.exceptionOrNull()
        assertThat(error).isInstanceOf(HistoryDeletionUnsupported::class.java)
        assertThat(ServiceLocator.historyRepository.snapshot().trips).containsExactlyElementsIn(before.trips)
        assertThat(ServiceLocator.historyRepository.snapshot().purchases).containsExactlyElementsIn(before.purchases)
    }

    private suspend fun seedLocalHistory(): String {
        val scope = "history-wiring"
        ServiceLocator.database.listDao().insert(ListEntity(scope, "Groceries"))
        ServiceLocator.listRepository.select(scope)
        ServiceLocator.historyRepository.importPast(listOf(ParsedItem("Milk")), 1_700_000_000_000L)
        return scope
    }
}

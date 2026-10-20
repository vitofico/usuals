package io.theficos.usuals.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.parse.ParsedItem
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InstallationTransferTest {
    @Test fun independentInstallationsRestoreSeparatelyAndKeepGeneratedIdsOnReopen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val names = listOf("source.db", "destination.db")
        names.forEach { context.deleteDatabase(it) }
        val source = UsualsDatabase.build(context, names[0])
        var destination = UsualsDatabase.build(context, names[1])
        try {
            val a = source.listDao().selected()
            val b = destination.listDao().selected()
            val sourceHousehold = source.listDao().all().single().householdId
            val targetHousehold = destination.listDao().all().single().householdId
            assertThat(java.util.UUID.fromString(a)).isNotEqualTo(java.util.UUID.fromString(b))
            assertThat(sourceHousehold).isNotEqualTo(targetHousehold)
            val sourceItems = ItemRepository(source)
            sourceItems.add(ParsedItem("milk"))
            val archive = HistoryRepository(source, sourceItems).exportJson()
            val history = HistoryRepository(destination, ItemRepository(destination))
            history.importJson(archive)
            history.importJson(archive)
            assertThat(destination.listDao().all().map { it.id }).containsExactly(a, b)
            assertThat(destination.listDao().find(a)?.householdId).isEqualTo(targetHousehold)
            assertThat(destination.listDao().find(a)?.archiveSource).isEqualTo(a)
            assertThat(destination.listDao().selected()).isEqualTo(a)
            assertThat(destination.itemDao().active(b)).isEmpty()
            assertThat(destination.itemDao().active(a).single().name).isEqualTo("milk")
            ListRepository(destination).select(b)
            ItemRepository(destination).add(ParsedItem("bread"))
            history.importJson(archive)
            assertThat(destination.listDao().selected()).isEqualTo(b)
            destination.close()
            destination = UsualsDatabase.build(context, names[1])
            assertThat(destination.listDao().selected()).isEqualTo(b)
            assertThat(destination.listDao().all()).hasSize(2)
            val camping = ListRepository(destination).create("Camping")
            assertThat(destination.listDao().find(camping)?.householdId).isEqualTo(targetHousehold)
        } finally {
            source.close(); destination.close(); names.forEach { context.deleteDatabase(it) }
        }
    }
}

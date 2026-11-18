package io.theficos.usuals.di

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.data.local.*
import io.theficos.usuals.parse.ParsedItem
import io.theficos.usuals.sync.Member
import io.theficos.usuals.sync.Wire
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RepositoryListDirectoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), UsualsDatabase::class.java).allowMainThreadQueries().build()
    private val replica = LocalReplica(db)
    private val lists = ListRepository(db)

    @After fun close() = db.close()

    @Test fun theBlankStartingListIsUnusedUntilSomethingIsAddedToIt() = runTest {
        db.listDao().insert(ListEntity("blank", "")); lists.select("blank")
        val directory = RepositoryListDirectory(lists, replica)
        assertThat(directory.observe().first().unused).isTrue()
        ItemRepository(db, replica = replica).add(ParsedItem("Milk"))
        assertThat(directory.observe().first().unused).isFalse()
    }

    @Test fun soloJoinedAndOwnedListsKeepTheirSharingLabels() = runTest {
        val owner = Member("owner", "Owner", "owner")
        val editor = Member("editor", "Editor", "editor")
        val sharedRoster = Wire.json.encodeToString(listOf(owner, editor))
        db.listDao().insert(ListEntity("solo", "Solo", role = "owner", members = Wire.json.encodeToString(listOf(owner))))
        db.listDao().insert(ListEntity("joined", "Joined", role = "editor", members = sharedRoster))
        db.listDao().insert(ListEntity("owned", "Owned", role = "owner", members = sharedRoster))
        lists.select("solo")

        val flags = RepositoryListDirectory(lists, replica).observe().first().lists
            .associate { it.id to (it.shared to it.editor) }
        assertThat(flags).containsExactly("solo", false to false, "joined", true to true, "owned", true to false)
    }
}

package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.model.*
import io.theficos.usuals.parse.LocalUtteranceParser
import io.theficos.usuals.smart.RepeatPlanner
import io.theficos.usuals.smart.ReviewRanker
import io.theficos.usuals.ui.history.NoHistoryStore
import io.theficos.usuals.ui.history.ScopedHistoryStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private var now = 100 * RepeatPlanner.DAY_MILLIS
    private val history = HistorySnapshot(listOf(Trip("past", now - 7 * RepeatPlanner.DAY_MILLIS)),
        (1..13).map { Purchase(ItemId("old-$it"), "past", "item $it", "item $it", boughtAt = now - 7 * RepeatPlanner.DAY_MILLIS) })
    private val snapshots = MutableStateFlow(ShoppingSnapshot("source", history = history))
    private val lists = MutableStateFlow(ListDirectory(selected = "source"))
    private var gate: CompletableDeferred<Unit>? = null
    private var fail = false
    private var calls = 0
    private var ids = 0
    private val smart = object : SmartStore by NoSmartStore {
        override fun review(now: Long, history: HistorySnapshot, active: List<Item>) = ReviewRanker().rank(now, history, active)
        override suspend fun addReviewed(key: String, scope: String, now: Long): ReviewAddResult {
            calls++
            gate?.await()
            check(!fail)
            val snapshot = snapshots.value
            if (snapshot.listId != scope) return ReviewAddResult.Unavailable
            if (snapshot.items.any { it.normalizedName == key }) return ReviewAddResult.AlreadyPresent
            val id = "added-${++ids}"
            val version = ItemVersion(scope, id, emptyMap(), id, null, emptyMap(), emptyMap(), "{}")
            val item = Item(ItemId(id), key, key, key, fromSuggestion = true, createdAt = now, updatedAt = now).also { it.version = version }
            snapshots.value = snapshot.copy(items = snapshot.items + item)
            return ReviewAddResult.Added(version)
        }
        override suspend fun undoReviewed(version: ItemVersion): Boolean {
            val snapshot = snapshots.value
            if (snapshot.listId != version.list || snapshot.items.none { it.version == version }) return false
            snapshots.value = snapshot.copy(items = snapshot.items.filterNot { it.id.value == version.id })
            return true
        }
        override suspend fun muteSuggestion(key: String, scope: String) {
            check(snapshots.value.listId == scope)
            snapshots.value = snapshots.value.copy(history = snapshots.value.history.copy(preferences = listOf(ItemPreference(key, muted = true))))
        }
        override suspend fun muteReviewed(key: String, scope: String, now: Long): Boolean {
            if (snapshots.value.listId != scope || review(now, snapshots.value.history, snapshots.value.items).none { it.key == key }) return false
            muteSuggestion(key, scope)
            return true
        }
    }
    private fun vm(): ListViewModel {
        val history = object : ScopedHistoryStore, io.theficos.usuals.ui.history.HistoryStore by NoHistoryStore {
            override fun observeShopping() = snapshots
        }
        val directory = object : ListDirectoryStore by NoListDirectory { override fun observe() = lists }
        return ListViewModel(FakeItemStore(), LocalUtteranceParser(), FakeSpeechSource(), clock = { now },
            historyStore = history, smartStore = smart, directoryStore = directory, compute = main.dispatcher)
    }

    @Test fun `skip undo and bounded rounds keep history and preferences unchanged`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onOpenReview()
        val original = vm.state.value.review!!.queue
        assertThat(vm.state.value.review!!.limit).isEqualTo(10)
        vm.onSkipReview()
        assertThat(vm.state.value.review!!.cursor).isEqualTo(1)
        vm.onUndoReview()
        assertThat(vm.state.value.review!!.current).isEqualTo(original.first())
        repeat(10) { vm.onSkipReview() }
        assertThat(vm.state.value.review!!.current).isNull()
        assertThat(vm.state.value.review!!.hasMore).isTrue()
        vm.onMoreReview()
        assertThat(vm.state.value.review!!.limit).isEqualTo(13)
        repeat(3) { vm.onSkipReview() }
        assertThat(vm.state.value.review!!.hasMore).isFalse()
        assertThat(snapshots.value.history).isEqualTo(history)
        assertThat(snapshots.value.items).isEmpty()
    }

    @Test fun `double add is serialized undo restores the card and closing retains additions`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onOpenReview()
        val first = vm.state.value.review!!.current!!
        gate = CompletableDeferred()
        vm.onAddReview(); vm.onAddReview(); vm.onSkipReview()
        assertThat(calls).isEqualTo(1)
        assertThat(vm.state.value.review!!.busy).isTrue()
        gate!!.complete(Unit)
        runCurrent()
        assertThat(vm.state.value.review!!.added).isEqualTo(1)
        assertThat(snapshots.value.items.single().name).isEqualTo(first.key)
        vm.onUndoReview()
        runCurrent()
        assertThat(snapshots.value.items).isEmpty()
        assertThat(vm.state.value.review!!.current!!.key).isEqualTo(first.key)
        vm.onAddReview(); runCurrent(); vm.onCloseReview()
        assertThat(vm.state.value.review).isNull()
        assertThat(snapshots.value.items).hasSize(1)
    }

    @Test fun `failed add stays on the card and can be retried`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onOpenReview()
        val first = vm.state.value.review!!.current
        fail = true
        vm.onAddReview()
        assertThat(vm.state.value.review!!.current).isEqualTo(first)
        assertThat(vm.state.value.review!!.problem).isEqualTo(ReviewProblem.SAVE)
        assertThat(vm.state.value.review!!.busy).isFalse()
        fail = false
        vm.onAddReview(); runCurrent()
        assertThat(snapshots.value.items).hasSize(1)
        assertThat(vm.state.value.review!!.problem).isNull()
    }

    @Test fun `live additions advance without reordering and changing lists closes review`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onOpenReview()
        val initial = vm.state.value.review!!
        val key = initial.current!!.key
        snapshots.value = snapshots.value.copy(items = listOf(Item(ItemId("partner"), key, key, key, checked = true, createdAt = now, updatedAt = now)))
        runCurrent()
        assertThat(vm.state.value.review!!.current).isEqualTo(initial.queue[1])
        assertThat(vm.state.value.review!!.queue).isEqualTo(initial.queue)
        lists.value = ListDirectory(selected = "target")
        runCurrent()
        assertThat(vm.state.value.review).isNull()
    }

    @Test fun `mute persists but undo refuses an item changed after adding`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onOpenReview()
        val muted = vm.state.value.review!!.current!!.key
        vm.onMuteReview(); runCurrent()
        assertThat(snapshots.value.history.preferences.single()).isEqualTo(ItemPreference(muted, muted = true))
        assertThat(vm.state.value.review!!.last).isNull()
        vm.onAddReview(); runCurrent()
        val row = snapshots.value.items.single()
        snapshots.value = snapshots.value.copy(items = listOf(row.copy(note = "partner edit").also {
            it.version = row.version!!.copy(fields = mapOf("note" to "changed"))
        }))
        runCurrent()
        vm.onUndoReview()
        assertThat(snapshots.value.items.single().note).isEqualTo("partner edit")
        assertThat(vm.state.value.review!!.problem).isEqualTo(ReviewProblem.UNDO_CHANGED)
        assertThat(vm.state.value.review!!.added).isEqualTo(1)
    }

    @Test fun `closing during an add cannot reopen or overwrite a new session`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onOpenReview()
        gate = CompletableDeferred()
        vm.onAddReview()
        vm.onCloseReview()
        vm.onOpenReview()
        gate!!.complete(Unit)
        runCurrent()
        assertThat(vm.state.value.review!!.added).isEqualTo(0)
        assertThat(snapshots.value.items).hasSize(1)
        assertThat(vm.state.value.review!!.cursor).isEqualTo(1)
    }

    @Test fun `a partner addition during a pending write is removed when the write completes`() = runTest(main.dispatcher) {
        val vm = vm()
        vm.onOpenReview()
        val queue = vm.state.value.review!!.queue
        gate = CompletableDeferred()
        vm.onAddReview()
        val next = queue[1].key
        snapshots.value = snapshots.value.copy(items = listOf(Item(ItemId("partner"), next, next, next, createdAt = now, updatedAt = now)))
        runCurrent()
        gate!!.complete(Unit)
        runCurrent()
        assertThat(vm.state.value.review!!.current).isEqualTo(queue[2])
        assertThat(vm.state.value.review!!.added).isEqualTo(1)
        assertThat(snapshots.value.items).hasSize(2)
    }

    @Test fun `returning after the freshness boundary refreshes the entry without a database write`() = runTest(main.dispatcher) {
        val boughtAt = now - RepeatPlanner.DAY_MILLIS + 1
        snapshots.value = snapshots.value.copy(history = HistorySnapshot(listOf(Trip("recent", boughtAt)),
            listOf(Purchase(ItemId("recent"), "recent", "milk", "milk", boughtAt = boughtAt))))
        val vm = vm()
        assertThat(vm.state.value.reviewCandidates).isEmpty()
        now++
        vm.onOpened()
        runCurrent()
        assertThat(vm.state.value.reviewCandidates.single().key).isEqualTo("milk")
    }
}

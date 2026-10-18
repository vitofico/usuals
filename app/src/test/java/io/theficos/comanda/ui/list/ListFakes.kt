package io.theficos.comanda.ui.list

import io.theficos.comanda.model.Household
import io.theficos.comanda.model.Item
import io.theficos.comanda.model.ItemId
import io.theficos.comanda.model.MemberId
import io.theficos.comanda.model.Quantity
import io.theficos.comanda.parse.Normalizer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * An in-memory list, standing in for Room.
 *
 * It records the shape of the writes as well as their result, because "one
 * utterance is one batch" is a property worth asserting: three items said in
 * one breath arriving as three separate emissions would animate into the list
 * one at a time.
 */
class FakeItemStore(
    initial: List<Item> = emptyList(),
    household: Household? = null,
) : ItemStore {

    private val rows = MutableStateFlow(initial)
    private val house = MutableStateFlow(household)

    /** Every call to [add], kept as batches rather than flattened. */
    val batches = mutableListOf<List<Item>>()
    val removed = mutableListOf<ItemId>()

    val current: List<Item> get() = rows.value

    override fun items(): Flow<List<Item>> = rows

    override fun household(): Flow<Household?> = house

    override suspend fun add(items: List<Item>) {
        batches += items
        rows.update { it + items }
    }

    override suspend fun setChecked(id: ItemId, checked: Boolean) {
        rows.update { list -> list.map { if (it.id == id) it.copy(checked = checked) else it } }
    }

    override suspend fun remove(id: ItemId) {
        removed += id
        rows.update { list -> list.filterNot { it.id == id } }
    }

    fun setHousehold(household: Household?) {
        house.value = household
    }
}

/**
 * A recogniser the test drives by hand: [hear] for a partial, [finish] for the
 * final result, [fail] for a recogniser that dies mid-gesture.
 */
class FakeSpeechSource(private val available: Boolean = true) : SpeechSource {

    private var channel = Channel<Transcript>(Channel.UNLIMITED)

    var listenCount: Int = 0
        private set
    var stopCount: Int = 0
        private set

    override fun isAvailable(): Boolean = available

    override fun listen(): Flow<Transcript> {
        listenCount++
        channel = Channel(Channel.UNLIMITED)
        return channel.receiveAsFlow()
    }

    override fun stop() {
        stopCount++
    }

    fun hear(partial: String) {
        channel.trySend(Transcript(partial, isFinal = false))
    }

    /** What the recogniser settles on. Ends the flow, the way a real one does. */
    fun finish(text: String) {
        channel.trySend(Transcript(text, isFinal = true))
        channel.close()
    }

    fun fail(cause: Throwable) {
        channel.close(cause)
    }
}

/**
 * An [Item] built at a fixed instant, so nothing in a test depends on when it
 * ran. [normalizedName] is derived the same way the real capture path derives
 * it, rather than hand-written, so a change to normalization does not quietly
 * make these fixtures lie.
 */
fun item(
    id: String,
    name: String,
    quantity: Quantity? = null,
    note: String? = null,
    checked: Boolean = false,
    position: Double = 0.0,
    addedBy: MemberId? = null,
    createdAt: Long = 1_000L,
): Item = Item(
    id = ItemId(id),
    rawText = name,
    name = name,
    normalizedName = Normalizer.normalize(name),
    quantity = quantity,
    note = note,
    gtin = null,
    checked = checked,
    position = position,
    addedBy = addedBy,
    createdAt = createdAt,
    updatedAt = createdAt,
)

/**
 * `viewModelScope` posts to the main dispatcher, which does not exist on the
 * JVM. Unconfined rather than a queued dispatcher because nothing in the view
 * model is time-dependent: a test should be able to press the band and read the
 * state on the next line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

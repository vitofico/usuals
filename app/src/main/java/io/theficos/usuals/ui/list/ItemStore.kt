package io.theficos.usuals.ui.list

import io.theficos.usuals.model.Item
import io.theficos.usuals.model.ItemId
import kotlinx.coroutines.flow.Flow

/**
 * What the list surface needs from storage, and nothing else.
 *
 * This is a port, not a repository. `:data:local` owns the real repository and
 * the Room database behind it, and `di/RepositoryItemStore` adapts one to the
 * other in a dozen lines. The indirection buys two things worth more than the
 * file it costs: the list screen and its view model compile and are testable
 * without Room or an Android context, and a change to the repository's shape
 * lands in one adapter rather than in the view model.
 *
 * Every read is a [Flow] because the list is offline-first: the UI reads from
 * disk and the writes come back through the same query, so a local write never
 * waits on the network and never has to be echoed into the UI by hand.
 */
interface ItemStore {

    /** Every item in the list, checked and unchecked, in any order. */
    fun items(): Flow<List<Item>>

    /**
     * Appends items to the list. Takes a batch because one utterance can
     * produce several ("leche, huevos y pan"), and they should land together
     * rather than as three separate emissions that animate in one at a time.
     */
    suspend fun add(items: List<Item>)

    suspend fun setChecked(id: ItemId, checked: Boolean)

    suspend fun setChecked(id: ItemId, checked: Boolean, version: io.theficos.usuals.model.ItemVersion?) = setChecked(id, checked)
    suspend fun remove(id: ItemId, version: io.theficos.usuals.model.ItemVersion?) = remove(id)
    suspend fun remove(id: ItemId)
}

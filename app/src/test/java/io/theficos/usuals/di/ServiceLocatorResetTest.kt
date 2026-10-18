package io.theficos.usuals.di

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That [ServiceLocator.reset] actually hands out a new graph.
 *
 * This is the guard behind a guard. `AppWiringTest` is the class that notices a
 * route nothing can reach, and it was quarantined whole because the locator
 * held one Room connection for the life of the test JVM while Robolectric gave
 * every test method a different temporary directory.
 * If reset ever stops emptying the fields, that class starts failing in a way
 * that points at the wrong test, so it is worth asserting the reset directly
 * rather than only through its effect on something else.
 */
@RunWith(RobolectricTestRunner::class)
class ServiceLocatorResetTest {

    @After
    fun dropTheGraph() {
        ServiceLocator.reset()
    }

    private fun init() =
        ServiceLocator.init(ApplicationProvider.getApplicationContext())

    @Test
    fun a_field_is_built_once_and_held_within_one_graph() {
        init()

        assertThat(ServiceLocator.itemRepository).isSameInstanceAs(ServiceLocator.itemRepository)
    }

    @Test
    fun reset_replaces_the_database_and_closes_the_old_one() {
        init()
        val first = ServiceLocator.database
        // Room opens on the first query, not on build, so a read is what makes
        // "closed" mean anything below.
        runBlocking { ServiceLocator.itemRepository.items() }

        ServiceLocator.reset()
        init()

        assertThat(ServiceLocator.database).isNotSameInstanceAs(first)
        assertThat(first.isOpen).isFalse()
    }

    /** The regression itself: a read after a reset, against a fresh graph. */
    @Test
    fun the_graph_still_works_after_a_reset() {
        init()
        runBlocking { ServiceLocator.itemRepository.items() }

        ServiceLocator.reset()
        init()

        assertThat(runBlocking { ServiceLocator.itemRepository.items() }).isEmpty()
    }

    @Test
    fun reset_forgets_the_context_so_a_later_read_says_so() {
        init()
        ServiceLocator.database

        ServiceLocator.reset()

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ServiceLocator.database
        }
        assertThat(thrown).hasMessageThat().contains("init")
    }

    /** Resetting a graph nothing ever touched must not build one to tear down. */
    @Test
    fun reset_on_an_untouched_locator_is_harmless() {
        ServiceLocator.reset()
    }
}

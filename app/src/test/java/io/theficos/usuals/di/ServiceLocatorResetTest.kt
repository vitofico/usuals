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
    fun reset_replaces_the_graph_and_closes_the_old_database() {
        ServiceLocator.reset()
        init()
        assertThat(ServiceLocator.itemRepository).isSameInstanceAs(ServiceLocator.itemRepository)
        val first = ServiceLocator.database
        runBlocking { ServiceLocator.itemRepository.items() }

        ServiceLocator.reset()

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ServiceLocator.database
        }
        assertThat(thrown).hasMessageThat().contains("init")
        assertThat(first.isOpen).isFalse()

        init()
        assertThat(ServiceLocator.database).isNotSameInstanceAs(first)
        assertThat(runBlocking { ServiceLocator.itemRepository.items() }).isEmpty()
    }
}

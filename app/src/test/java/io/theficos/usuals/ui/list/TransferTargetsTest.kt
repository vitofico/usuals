package io.theficos.usuals.ui.list

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TransferTargetsTest {
    @Test fun onlyOtherActiveListsAreOfferedWithTheirHumanNames() {
        val directory = ListDirectory("home", listOf(NamedList("home", "Home", false), NamedList("trip", "Camping", true, editor = true),
            NamedList("old", "Old list", false, archived = true)))
        assertThat(transferTargets(directory, "home")).containsExactly(NamedList("trip", "Camping", true, editor = true))
    }
    @Test fun staleSourceAfterSwitchOrArchiveHasNoTargets() {
        val lists = listOf(NamedList("home", "Home", false), NamedList("trip", "Camping", false))
        assertThat(transferTargets(ListDirectory("trip", lists), "home")).isEmpty()
        assertThat(transferTargets(ListDirectory("home", lists.map { if (it.id == "home") it.copy(archived = true) else it }), "home")).isEmpty()
    }
}

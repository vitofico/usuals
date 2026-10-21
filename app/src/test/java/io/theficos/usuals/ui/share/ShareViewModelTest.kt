package io.theficos.usuals.ui.share

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.sync.RelayProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ana = SharePerson("ana", "Ana", "ana@usuals.example.org")
    private val bo = SharePerson("bo", "Bo")
    private val owner = ShareMember("me", "Me", owner = true)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeStore(target: ShareTarget?) : ShareStore {
        val target = MutableStateFlow(target)
        val calls = mutableListOf<String>()
        val queries = mutableListOf<String>()
        var people = listOf(SharePerson("ana", "Ana"), SharePerson("bo", "Bo"))
        var failure: Exception? = null
        override fun observe(list: String) = target
        override suspend fun people(query: String): List<SharePerson> { queries += query; failure?.let { throw it }; return people.filter { query in it.name } }
        override suspend fun add(list: String, person: String) { failure?.let { throw it }; calls += "add $list $person" }
        override suspend fun remove(list: String, person: String) { failure?.let { throw it }; calls += "remove $list $person" }
    }
    private fun TestScope.open(store: FakeStore) = ShareViewModel("home", store, worker = dispatcher).also { advanceUntilIdle() }

    @Test fun theSheetOffersTheFirstPeopleBeforeAnythingIsTypedAndHidesWhoAlreadyHasAccess() = runTest(dispatcher) {
        val store = FakeStore(ShareTarget("Home", listOf(owner, ShareMember("bo", "Bo", owner = false)), "me"))
        val sheet = open(store)
        assertThat(store.queries).containsExactly("")
        assertThat(sheet.state.value.owner).isTrue()
        assertThat(sheet.state.value.candidates.map { it.id }).containsExactly("ana")
    }

    @Test fun typingSearchesOnceThePauseIsOver() = runTest(dispatcher) {
        val store = FakeStore(ShareTarget("Home", listOf(owner), "me"))
        store.people = listOf(ana, bo)
        val sheet = open(store)
        sheet.query("A"); advanceTimeBy(100); sheet.query("An ")
        advanceUntilIdle()
        assertThat(store.queries).containsExactly("", "An").inOrder()
        assertThat(sheet.state.value.query).isEqualTo("An ")
        assertThat(sheet.state.value.people).containsExactly(ana)
    }

    @Test fun tappingAPersonAddsThemAtOnce() = runTest(dispatcher) {
        val store = FakeStore(ShareTarget("Home", emptyList(), "me"))
        val sheet = open(store)
        assertThat(sheet.state.value.owner).isTrue()
        sheet.add(ana); advanceUntilIdle()
        assertThat(store.calls).containsExactly("add home ana")
        assertThat(sheet.state.value.busy).isFalse()
    }

    @Test fun theOwnerRemovesAnEditorButNeverThemselvesAndCannotLeave() = runTest(dispatcher) {
        val editor = ShareMember("ana", "Ana", owner = false)
        val store = FakeStore(ShareTarget("Home", listOf(owner, editor), "me"))
        val sheet = open(store)
        sheet.remove(owner); sheet.leave(); advanceUntilIdle()
        assertThat(store.calls).isEmpty()
        sheet.remove(editor); advanceUntilIdle()
        assertThat(store.calls).containsExactly("remove home ana")
    }

    @Test fun anEditorLeavesButCannotRemoveAnyone() = runTest(dispatcher) {
        val store = FakeStore(ShareTarget("Camping", listOf(ShareMember("ana", "Ana", owner = true), ShareMember("me", "Me", owner = false), ShareMember("bo", "Bo", owner = false)), "me"))
        val sheet = open(store)
        assertThat(sheet.state.value.owner).isFalse()
        sheet.remove(ShareMember("bo", "Bo", owner = false)); advanceUntilIdle()
        assertThat(store.calls).isEmpty()
        sheet.leave(); advanceUntilIdle()
        assertThat(store.calls).containsExactly("remove home me")
        store.target.value = null; advanceUntilIdle()
        assertThat(sheet.state.value.gone).isTrue()
    }

    @Test fun signedOutTheSheetSearchesNothingAndChangesNothing() = runTest(dispatcher) {
        val store = FakeStore(ShareTarget("Home", emptyList(), me = null))
        val sheet = open(store)
        sheet.add(ana); sheet.leave(); advanceUntilIdle()
        assertThat(sheet.state.value.signedIn).isFalse()
        assertThat(sheet.state.value.owner).isFalse()
        assertThat(store.queries).isEmpty()
        assertThat(store.calls).isEmpty()
    }

    @Test fun serverAnswersBecomeProblemsThePersonCanActOn() = runTest(dispatcher) {
        val store = FakeStore(ShareTarget("Home", listOf(owner), "me"))
        val sheet = open(store)
        store.failure = RelayProblem("limit_reached", 409)
        sheet.add(ana); advanceUntilIdle()
        assertThat(sheet.state.value.problem).isEqualTo(ShareProblem.FULL)
        assertThat(sheet.state.value.busy).isFalse()
        store.failure = RelayProblem("unauthenticated", 401)
        sheet.add(ana); advanceUntilIdle()
        assertThat(sheet.state.value.problem).isEqualTo(ShareProblem.SIGN_IN)
        store.failure = java.io.IOException("no route")
        sheet.query("B"); advanceUntilIdle()
        assertThat(sheet.state.value.problem).isEqualTo(ShareProblem.OFFLINE)
    }
}

package io.theficos.usuals.ui.history

import com.google.common.truth.Truth.assertThat
import io.theficos.usuals.llm.ModelImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiptEditorTest {
    private class Store : ReceiptStore by NoReceiptStore {
        var reads = 0
        var saves = 0
        var gate: CompletableDeferred<String>? = null
        var savedText: String? = null
        override suspend fun photo(uri: String) = ModelImage("image/jpeg", byteArrayOf(1))
        override suspend fun read(image: ModelImage): String { reads++; return gate?.await() ?: "2 milk\nbread" }
        override suspend fun save(scope: String, trip: String?, image: ModelImage, text: String?, date: Long): String {
            saves++; savedText = text; return "shop"
        }
    }
    @Test fun photo_stays_local_until_read_and_only_edited_lines_are_saved() = runTest {
        val store = Store()
        val saved = mutableListOf<String>()
        val editor = ReceiptEditor(this, store, { "2026-10-25" }, ZoneId.of("UTC"), saved::add)
        editor.open("list"); editor.photo("content://qa/receipt"); runCurrent()
        assertThat(store.reads).isEqualTo(0)
        assertThat(editor.state.value!!.image).isNotNull()
        editor.read(); runCurrent()
        assertThat(store.saves).isEqualTo(0)
        assertThat(editor.state.value!!.text).isEqualTo("2 milk\nbread")
        editor.text("1 milk"); editor.save(); editor.save(); runCurrent()
        assertThat(store.saves).isEqualTo(1)
        assertThat(store.savedText).isEqualTo("1 milk")
        assertThat(saved).containsExactly("list")
        assertThat(editor.state.value).isNull()
    }
    @Test fun switching_lists_cancels_a_read_and_cannot_replace_a_new_draft() = runTest {
        val store = Store().apply { gate = CompletableDeferred() }
        val editor = ReceiptEditor(this, store, { "2026-10-25" }, ZoneId.of("UTC")) {}
        editor.open("old"); editor.photo("content://qa/receipt"); runCurrent(); editor.read(); runCurrent()
        editor.scopeChanged("new"); editor.open("new")
        store.gate!!.complete("old answer"); runCurrent()
        assertThat(editor.state.value!!.scope).isEqualTo("new")
        assertThat(editor.state.value!!.text).isNull()
        assertThat(store.saves).isEqualTo(0)
    }
    @Test fun future_date_cannot_create_purchases_and_manual_entry_needs_no_model() = runTest {
        val store = Store()
        val editor = ReceiptEditor(this, store, { "2026-10-25" }, ZoneId.of("UTC")) {}
        editor.open("list"); editor.photo("content://qa/receipt"); runCurrent()
        editor.text("milk"); editor.date("2026-10-26"); editor.save(); runCurrent()
        assertThat(editor.state.value!!.problem).isEqualTo(ReceiptProblem.DATE)
        assertThat(store.saves).isEqualTo(0)
        editor.date("2026-10-24"); editor.save(); runCurrent()
        assertThat(store.saves).isEqualTo(1)
        assertThat(store.reads).isEqualTo(0)
    }
}

package io.theficos.usuals.ui.shopping

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import io.theficos.usuals.smart.Category

class ShoppingToolsTest {
    @Test fun `personal choices survive reload without changing household facts`() {
        var disk: String? = null
        val store = ShoppingToolsStore(write = { disk = it })
        val favorite = SavedShoppingItem("favorite", "Milk", 2.0, "LITRE")
        store.pin(favorite); store.mode(true); store.keepAwake(true); store.haptics(true)
        store.order("market", Category.entries.reversed())
        val restored = ShoppingToolsStore(read = { disk })
        assertThat(restored.state.value).isEqualTo(store.state.value)
        assertThat(restored.state.value.portable.categoryOrder("market").first()).isEqualTo(Category.OTHER)
        assertThat(restored.state.value.portable.categoryOrder("other")).isEqualTo(Category.entries)
    }
    @Test fun `deferrals survive restart are scoped and expire only on a new trip`() {
        var disk: String? = null
        val store = ShoppingToolsStore(write = { disk = it })
        store.defer("home", "milk", "capture", setOf("old"))
        store.defer("work", "milk", "other-capture", emptySet())
        val restored = ShoppingToolsStore(read = { disk }, write = { disk = it })
        restored.releaseAfterShop("home", setOf("old"))
        assertThat(restored.state.value.deferred.getValue("home").rows).containsEntry("milk", "capture")
        restored.releaseAfterShop("home", setOf("old", "new"))
        assertThat(restored.state.value.deferred).doesNotContainKey("home")
        assertThat(restored.state.value.deferred).containsKey("work")
        assertThat(restored.state.value.portable.favorites).isEmpty()
    }
    @Test fun `failed persistence and malformed or future settings cannot overwrite saved preferences`() {
        val broken = ShoppingToolsStore(read = { "not json" }, write = { error("must not write") })
        assertThat(broken.state.value.unavailable).isTrue()
        assertThrows(IllegalStateException::class.java) { broken.mode(true) }
        val future = ShoppingToolsStore(read = { """{"version":2}""" })
        assertThat(future.state.value.unavailable).isTrue()
        val store = ShoppingToolsStore(write = { error("disk") })
        assertThrows(IllegalStateException::class.java) { store.mode(true) }
        assertThat(store.state.value.mode).isFalse()
    }
    @Test fun `portable import is idempotent preserves existing layouts and rejects conflicting ids`() {
        val store = ShoppingToolsStore()
        store.order("market", listOf(Category.FISH))
        val source = PortableShoppingPreferences(listOf(SavedShoppingItem("id", "Milk")), mapOf("market" to listOf("PRODUCE")))
        store.importPortable(source); store.importPortable(source)
        assertThat(store.state.value.portable.favorites).hasSize(1)
        assertThat(store.state.value.portable.orders.getValue("market")).containsExactly("FISH")
        assertThrows(IllegalArgumentException::class.java) { store.importPortable(source.copy(favorites = listOf(SavedShoppingItem("id", "Bread")))) }
        assertThrows(IllegalArgumentException::class.java) { store.pin(SavedShoppingItem("bad", "Milk", Double.NaN, "LITRE")) }
        assertThat(store.state.value.portable.favorites).hasSize(1)
    }
}

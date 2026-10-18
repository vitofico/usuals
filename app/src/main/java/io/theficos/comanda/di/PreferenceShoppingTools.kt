package io.theficos.comanda.di

import android.content.Context
import io.theficos.comanda.ui.shopping.ShoppingToolsStore

fun preferenceShoppingTools(context: Context): ShoppingToolsStore {
    val prefs = context.getSharedPreferences("shopping_tools", Context.MODE_PRIVATE)
    return ShoppingToolsStore(read = { prefs.getString("state", null) }, write = {
        check(prefs.edit().putString("state", it).commit())
    })
}

package io.theficos.usuals.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import io.theficos.usuals.di.ServiceLocator
import kotlinx.coroutines.*

const val EXTRA_OPEN_LIST = "io.theficos.usuals.OPEN_LIST"

class ShoppingListWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServiceLocator.init(context)
                ServiceLocator.widgetUpdater.start()
                ServiceLocator.widgetUpdater.refresh()
            } catch (_: Exception) {
                // Keep the last rendered list if local storage cannot be read.
            } finally { pending?.finish() }
        }
    }
    override fun onDisabled(context: Context) {
        ServiceLocator.init(context)
        ServiceLocator.widgetUpdater.stop()
    }
}

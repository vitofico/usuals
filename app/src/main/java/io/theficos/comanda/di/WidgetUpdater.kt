package io.theficos.comanda.di

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.theficos.comanda.MainActivity
import io.theficos.comanda.R
import io.theficos.comanda.data.local.ItemRepository
import io.theficos.comanda.model.Item
import io.theficos.comanda.ui.list.formatQuantity
import io.theficos.comanda.widget.ShoppingListWidget
import io.theficos.comanda.widget.EXTRA_OPEN_LIST
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch

class WidgetUpdater(private val context: Context, private val items: ItemRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: Job? = null
    private fun ids(): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ShoppingListWidget::class.java))
    @Synchronized fun start() {
        if (watcher?.isActive == true || ids().isEmpty()) return
        watcher = scope.launch { items.observeItems().catch { emit(emptyList()) }.collect { refresh(it) } }
    }
    @Synchronized fun stop() { watcher?.cancel(); watcher = null }
    fun close() { scope.cancel() }
    suspend fun refresh() = refresh(items.items())
    private fun refresh(rows: List<Item>) {
        val widgets = ids()
        if (widgets.isEmpty()) return
        val pending = rows.filterNot { it.checked }
        val lines = pending.take(6).map { row -> context.getString(R.string.widget_item, row.name,
            row.quantity?.let { formatQuantity(context, it) }.orEmpty()).trim() }.toMutableList()
        if (pending.size > 6) lines += context.resources.getQuantityString(R.plurals.widget_more, pending.size - 6, pending.size - 6)
        val views = RemoteViews(context.packageName, R.layout.shopping_widget)
        views.setTextViewText(R.id.widget_items, if (lines.isEmpty()) context.getString(R.string.widget_empty) else lines.joinToString("\n"))
        views.setTextViewText(R.id.widget_count, context.resources.getQuantityString(R.plurals.widget_count, pending.size, pending.size))
        val intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_LIST, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        AppWidgetManager.getInstance(context).updateAppWidget(widgets, views)
    }
}

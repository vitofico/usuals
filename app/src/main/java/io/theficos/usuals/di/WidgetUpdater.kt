package io.theficos.usuals.di

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.theficos.usuals.MainActivity
import io.theficos.usuals.R
import io.theficos.usuals.data.local.ItemRepository
import io.theficos.usuals.model.Item
import io.theficos.usuals.ui.list.formatQuantity
import io.theficos.usuals.widget.ShoppingListWidget
import io.theficos.usuals.widget.EXTRA_OPEN_LIST
import io.theficos.usuals.ui.theme.AppPalette
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine

class WidgetUpdater(private val context: Context, private val items: ItemRepository, private val themes: ThemePreferences) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: Job? = null
    private fun ids(): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ShoppingListWidget::class.java))
    @Synchronized fun start() {
        if (watcher?.isActive == true || ids().isEmpty()) return
        watcher = scope.launch {
            combine(items.observeItems().catch { emit(emptyList()) }, themes.palette) { rows, palette -> rows to palette }
                .collect { (rows, palette) -> refresh(rows, palette) }
        }
    }
    @Synchronized fun stop() { watcher?.cancel(); watcher = null }
    fun close() { scope.cancel() }
    suspend fun refresh() = refresh(items.items(), themes.palette.value)
    private fun refresh(rows: List<Item>, palette: AppPalette) {
        val widgets = ids()
        if (widgets.isEmpty()) return
        val pending = rows.filterNot { it.checked }
        val lines = pending.take(6).map { row -> context.getString(R.string.widget_item, row.name,
            row.quantity?.let { formatQuantity(context, it) }.orEmpty()).trim() }.toMutableList()
        if (pending.size > 6) lines += context.resources.getQuantityString(R.plurals.widget_more, pending.size - 6, pending.size - 6)
        val layout = when (palette) {
            AppPalette.PLUM -> R.layout.shopping_widget
            AppPalette.GROVE -> R.layout.shopping_widget_grove
            AppPalette.TIDE -> R.layout.shopping_widget_tide
        }
        val views = RemoteViews(context.packageName, layout)
        views.setTextViewText(R.id.widget_items, if (lines.isEmpty()) context.getString(R.string.widget_empty) else lines.joinToString("\n"))
        views.setTextViewText(R.id.widget_count, context.resources.getQuantityString(R.plurals.widget_count, pending.size, pending.size))
        val intent = Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_LIST, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        AppWidgetManager.getInstance(context).updateAppWidget(widgets, views)
    }
}

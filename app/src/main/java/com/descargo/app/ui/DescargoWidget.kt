package com.descargo.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.descargo.app.R
import com.descargo.app.data.HistoryStore
import com.descargo.app.data.Status

/** Home-screen widget: "Descargar copiado" button + how many downloads are running. */
class DescargoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        fun refresh(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, DescargoWidget::class.java))
            if (ids.isEmpty()) return
            HistoryStore.init(ctx)
            val items = HistoryStore.items.value
            val running = items.count { it.isActive }
            val paused = items.count { it.status == Status.PAUSED }
            val status = when {
                running > 0 -> if (running == 1) "1 descarga en curso" else "$running descargas en curso"
                paused > 0 -> "$paused en pausa"
                else -> "${items.count { it.status == Status.DONE }} en tu biblioteca"
            }
            val paste = PendingIntent.getActivity(
                ctx, 1,
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_CLIPBOARD, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val views = RemoteViews(ctx.packageName, R.layout.widget_descargo).apply {
                setTextViewText(R.id.widget_status, status)
                setOnClickPendingIntent(R.id.widget_paste, paste)
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}

package com.example.myapplication

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb

// ============================================================================
// ВИДЖЕТ НА РАБОЧИЙ СТОЛ: балл спроса (из кэша прогноза), текущая смена с таймером и кнопка «+ заказ».
// Кнопка добавляет заказ на последнюю использованную сумму, не открывая приложение.
// ============================================================================

class CourierWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_ADD_ORDER) {
            val s = SettingsManager(context)
            s.addLiveOrder(s.getLastOrderPrice(), 0)
            updateAll(context)
            ShiftNotification.sync(context)
        }
    }

    companion object {
        const val ACTION_ADD_ORDER = "com.komodist.kurerzhivi.ADD_ORDER"

        fun updateAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, CourierWidget::class.java))
                if (ids.isEmpty()) return
                val views = buildViews(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            } catch (_: Exception) {
            }
        }

        private fun buildViews(context: Context): RemoteViews {
            val s = SettingsManager(context)
            val v = RemoteViews(context.packageName, R.layout.widget_courier)
            val region = s.getSelectedRegion()
            val cached = s.loadWeatherCache(region.id)
            if (cached != null && cached.current.weather != Weather.UNKNOWN) {
                val score = calculateScore100(cached.current, emptyList(), cityCalendar(cached.current), s.getTransport())
                val isStale = System.currentTimeMillis() - cached.fetchedAtMs > 2 * 3_600_000L
                v.setTextViewText(R.id.w_score, score.totalScore.toString())
                v.setTextColor(R.id.w_score, score.color.toArgb())
                v.setTextViewText(
                    R.id.w_verdict,
                    "${region.name} • ${cached.current.temp}°C ${cached.current.weather.emoji}" + if (isStale) " • кэш" else ""
                )
            } else {
                v.setTextViewText(R.id.w_score, "—")
                v.setTextViewText(R.id.w_verdict, "Откройте приложение для прогноза")
            }

            val orders = s.getLiveOrders()
            val gross = s.getLiveGross()
            val start = s.getShiftStart()
            val pausedAt = s.getShiftPausedAt()
            val pausedTotal = s.getShiftPausedTotal()
            val statusText = when {
                orders > 0 -> "$orders зак. • $gross ₽"
                start > 0 && pausedAt > 0 -> "На паузе • 0 зак."
                start > 0 -> "На линии • 0 зак."
                else -> "Смена не начата"
            }
            v.setTextViewText(R.id.w_live, statusText)
            if (start > 0) {
                val activeMs = activeShiftElapsedMs(start, pausedAt, pausedTotal)
                val base = SystemClock.elapsedRealtime() - activeMs
                val running = pausedAt == 0L
                v.setChronometer(R.id.w_timer, base, if (running) "⏱ %s" else "⏸ %s", running)
                v.setViewVisibility(R.id.w_timer, android.view.View.VISIBLE)
            } else {
                v.setChronometer(R.id.w_timer, SystemClock.elapsedRealtime(), null, false)
                v.setViewVisibility(R.id.w_timer, android.view.View.GONE)
            }
            v.setTextViewText(R.id.w_add, "+ ${s.getLastOrderPrice()} ₽")

            val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_TAB, if (start > 0) 1 else 0)
            }
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(context, 10, open, immutable))
            val add = Intent(context, CourierWidget::class.java).setAction(ACTION_ADD_ORDER)
            v.setOnClickPendingIntent(R.id.w_add, PendingIntent.getBroadcast(context, 11, add, immutable))
            return v
        }
    }
}

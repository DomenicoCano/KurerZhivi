package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

// ============================================================================
// ЗАКРЕПЛЁННОЕ УВЕДОМЛЕНИЕ ТЕКУЩЕЙ СМЕНЫ В ШТОРКЕ (+ ЗАКАЗ / ВВОД СУММЫ / ПАУЗА)
// ============================================================================

const val NOTIF_ID_LIVE_SHIFT = 1003
const val EXTRA_OPEN_TAB = "open_tab"
const val ACTION_SHIFT_ADD_LAST = "com.komodist.kurerzhivi.SHIFT_ADD_LAST"
const val ACTION_SHIFT_CUSTOM_INPUT = "com.komodist.kurerzhivi.SHIFT_CUSTOM_INPUT"
const val ACTION_SHIFT_TOGGLE_PAUSE = "com.komodist.kurerzhivi.SHIFT_TOGGLE_PAUSE"
const val KEY_REMOTE_ORDER = "remote_order_text"

object ShiftNotification {
    private const val CHANNEL_ID = "live_shift_ch"

    fun sync(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val s = SettingsManager(context)
        val start = s.getShiftStart()
        if (start <= 0L) {
            manager.cancel(NOTIF_ID_LIVE_SHIFT)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Текущая смена", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            manager.createNotificationChannel(ch)
        }

        val pausedAt = s.getShiftPausedAt()
        val pausedTotal = s.getShiftPausedTotal()
        val activeMs = activeShiftElapsedMs(start, pausedAt, pausedTotal)
        val orders = s.getLiveOrders()
        val gross = s.getLiveGross()
        val tips = s.getLiveTips()
        val lastPrice = s.getLastOrderPrice()
        val liveNet = gross - taxFor(gross, s.getTaxRate())
        val perHour = if (activeMs >= 15 * 60_000L && liveNet > 0) (liveNet / (activeMs / 3_600_000.0)).roundToInt() else null

        val running = pausedAt == 0L
        val title = if (running) {
            "🟢 На линии • $orders зак. • $gross ₽" + (perHour?.let { " (~$it ₽/ч)" } ?: "")
        } else {
            "⏸ Пауза • $orders зак. • $gross ₽"
        }
        val text = if (running) {
            "Чистыми: $liveNet ₽" + (if (tips > 0) " • Чаевые: $tips ₽" else "")
        } else {
            "Активно: ${formatShiftDuration(activeMs)} • Чистыми: $liveNet ₽"
        }

        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val mutable = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_TAB, 1)
        }
        val contentPi = PendingIntent.getActivity(context, 20, openIntent, immutable)

        val addLastPi = PendingIntent.getBroadcast(
            context, 21,
            Intent(context, ShiftActionReceiver::class.java).setAction(ACTION_SHIFT_ADD_LAST),
            immutable
        )
        val customPi = PendingIntent.getBroadcast(
            context, 22,
            Intent(context, ShiftActionReceiver::class.java).setAction(ACTION_SHIFT_CUSTOM_INPUT),
            mutable
        )
        val pausePi = PendingIntent.getBroadcast(
            context, 23,
            Intent(context, ShiftActionReceiver::class.java).setAction(ACTION_SHIFT_TOGGLE_PAUSE),
            immutable
        )

        val remoteInput = RemoteInput.Builder(KEY_REMOTE_ORDER)
            .setLabel("Напр. 320, 320+50 или +100")
            .build()
        val customAction = NotificationCompat.Action.Builder(0, "+ Сумма/Чай", customPi)
            .addRemoteInput(remoteInput)
            .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (running) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, "+ $lastPrice ₽", addLastPi)
            .addAction(customAction)
            .addAction(0, if (running) "⏸ Пауза" else "▶ В линию", pausePi)

        if (running) {
            builder.setWhen(System.currentTimeMillis() - activeMs).setUsesChronometer(true).setShowWhen(true)
        } else {
            builder.setUsesChronometer(false).setShowWhen(false)
        }

        manager.notify(NOTIF_ID_LIVE_SHIFT, builder.build())
    }
}

class ShiftActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = SettingsManager(context)
        when (intent.action) {
            ACTION_SHIFT_ADD_LAST -> {
                s.addLiveOrder(s.getLastOrderPrice(), 0)
            }
            ACTION_SHIFT_CUSTOM_INPUT -> {
                val raw = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REMOTE_ORDER)?.toString() ?: ""
                val parsed = parseQuickOrderInput(raw)
                if (parsed != null) s.addLiveOrder(parsed.first, parsed.second)
            }
            ACTION_SHIFT_TOGGLE_PAUSE -> {
                s.toggleShiftPause()
            }
            else -> return
        }
        ShiftNotification.sync(context)
        CourierWidget.updateAll(context)
    }
}

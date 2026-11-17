package com.example.myapplication

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// ============================================================================
// WORK MANAGER (ФОНОВЫЕ УВЕДОМЛЕНИЯ)
// ============================================================================

val extremeHighNormalMessages = listOf(
    "Огромный спрос! Идеальное время для работы.",
    "Заказов очень много. Выходи на линию, заработки на пике!",
    "В городе ажиотаж. Курьеров не хватает, тарифы горят.",
    "Горячие часы: клиенты ждут, спрос максимальный.",
    "Отличная возможность заработать. Районы в красной зоне!",
    "Пик заказов! Хватай сумку и вперед.",
    "Спрос бьет рекорды. Не упусти свой шанс заработать.",
    "Рестораны перегружены заказами. Твой выход!",
    "Множество заказов ждут доставки. Выходи на смену.",
    "Бонусное время! Заказов море, выходи на линию."
)

val extremeHighMatMessages = listOf(
    "ПИЗДЕЦ, БАЛЛ ВЫСОКИЙ! ЗАКАЗОВ ДОХУЯ, ВЫХОДИ СЕЙЧАС ЖЕ!",
    "Ебать-копать, спрос просто космос! Пиздуй работать!",
    "Хули сидишь? Там заказов ебнешься сколько! Быстро на линию!",
    "Ахуеть какой спрос! Хватай сумку и ебашь, пока кэфы горят!",
    "Бля, весь город в заказах! Подрывайся и лутай бабки!",
    "Ебаный в рот, заказы сыпятся! Давай хуярь на смену!",
    "Спрос ебейший! Не проеби вспышку, выходи работать!",
    "Там рестораны охуевают от заказов. Пиздуй спасать ситуацию!",
    "Блядский ажиотаж! Кэфы ебнутые, бегом на линию!",
    "Ну пиздец, бабки сами в руки летят! Поднимай жопу!"
)

val extremeLowNormalMessages = listOf(
    "Спрос упал до минимума. Лучше переждать.",
    "Сейчас затишье. Заказов почти нет.",
    "Мертвая зона. Нет смысла кататься впустую.",
    "Спрос на нуле. Самое время для перерыва.",
    "Заказов нет. Отдохни пару часов.",
    "Линия пустая. Возвращайся домой греться.",
    "Тухляк полнейший. Подожди вечернего пика.",
    "Затишье перед бурей. Пока можно отдохнуть.",
    "Никто не заказывает. Расслабься.",
    "Слабый спрос. Не трать свои силы."
)

val extremeLowMatMessages = listOf(
    "Братан, заказов нахуй нет. Сиди дома, не трать бензин зря.",
    "Пиздец тухляк. Хуй без соли доедаем, заказов ноль.",
    "Мертвечина ебаная. Скручивай удочки и пиздуй домой.",
    "Заказов нихуя нет. Наливай чай и ложись на диван.",
    "Бля, ну и хуета. Смысла торчать на улице вообще нет.",
    "Полный пиздец, тишина. Отдыхай, заебал.",
    "Хуй кто что заказывает. Можешь смело забить болт.",
    "Нихуя не падает. Забей хуй на эту смену.",
    "Ебаный тухляк. Сворачивайся нахуй.",
    "Да ну его в пизду такой спрос. Иди поспи."
)

class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsManager(applicationContext)
        // Пользователь мог выключить уведомления, пока задача ждала запуска — иначе она бы перепланировала сама себя
        if (!settings.getNotifEnabled()) return Result.success()
        val region = settings.getSelectedRegion()
        val transport = settings.getTransport()
        val weatherData = fetchExpandedWeather(region.lat, region.lon, settings.getYandexKey(), transport, settings.getSafeSources(), region.id)
        val effectiveWeather = if (weatherData.current.weather != Weather.UNKNOWN) {
            settings.saveWeatherCache(weatherData, region.id)
            CourierWidget.updateAll(applicationContext)
            weatherData
        } else if (runAttemptCount < 3) {
            // Нет сети — не шлём сводку по пустым данным, а пробуем ещё раз (WorkManager повторит с паузой)
            return Result.retry()
        } else {
            val cached = settings.loadWeatherCache(region.id)
            if (cached == null) {
                scheduleDaily(applicationContext, settings.getNotifHour(), settings.getNotifMinute())
                return Result.success()
            }
            cached
        }
        val news = try { fetchLocalNews(region, settings.getSafeSources()) } catch (_: Exception) { emptyList() }
        val score = calculateScore100(effectiveWeather.current, news, cityCalendar(effectiveWeather.current), transport)
        val bestSlot = effectiveWeather.hourlyTimeline.maxByOrNull { it.score }
        val slotHint = if (bestSlot != null) " Пик сегодня в ${bestSlot.timeStr} (${bestSlot.score}/100)." else ""
        sendNotification(applicationContext, "Сводка курьера (${region.name})", "Спрос: ${score.totalScore}/100. ${score.verdict}$slotHint", NOTIF_ID_DAILY)
        scheduleDaily(applicationContext, settings.getNotifHour(), settings.getNotifMinute())
        return Result.success()
    }
    companion object {
        fun scheduleDaily(context: Context, hour: Int, minute: Int) {
            val nowCal = Calendar.getInstance()
            val targetCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (before(nowCal) || equals(nowCal)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val req = OneTimeWorkRequestBuilder<DailyWorker>().setInitialDelay((targetCal.timeInMillis - nowCal.timeInMillis) / 1000, TimeUnit.SECONDS).addTag("DAILY_WORK").build()
            WorkManager.getInstance(context).enqueueUniqueWork("DAILY_WORK", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

class ExtremeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsManager(applicationContext)
        if (!settings.getExtremeNotifEnabled()) return Result.success()
        val nowMs = System.currentTimeMillis()
        if (nowMs - settings.getLastExtremeNotifTime() < 3 * 60 * 60 * 1000L) return Result.success()
        // Тихие часы: ночью не будим пользователя
        if (settings.getQuietHours() && isQuietHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) return Result.success()

        val region = settings.getSelectedRegion()
        val transport = settings.getTransport()
        val weatherData = fetchExpandedWeather(region.lat, region.lon, settings.getYandexKey(), transport, settings.getSafeSources(), region.id)
        // Если сеть пропала — не присылаем ложную «Мёртвую зону» из-за UNKNOWN-погоды
        if (weatherData.current.weather == Weather.UNKNOWN) return Result.success()
        settings.saveWeatherCache(weatherData, region.id)
        CourierWidget.updateAll(applicationContext)
        val news = try { fetchLocalNews(region, settings.getSafeSources()) } catch (_: Exception) { emptyList() }
        val score = calculateScore100(weatherData.current, news, cityCalendar(weatherData.current), transport)
        val tone = settings.getNotifTone()
        if (score.totalScore >= 85) {
            sendNotification(applicationContext, "ЭКСТРЕМАЛЬНЫЙ СПРОС (${score.totalScore}/100)!", if (tone == NotificationTone.MAT) extremeHighMatMessages.random() else extremeHighNormalMessages.random(), NOTIF_ID_EXTREME)
            settings.setLastExtremeNotifTime(nowMs)
        } else if (score.totalScore <= 25) {
            sendNotification(applicationContext, "Мертвая зона (${score.totalScore}/100)", if (tone == NotificationTone.MAT) extremeLowMatMessages.random() else extremeLowNormalMessages.random(), NOTIF_ID_EXTREME)
            settings.setLastExtremeNotifTime(nowMs)
        }
        return Result.success()
    }
    companion object {
        fun scheduleExtreme(context: Context) {
            val req = PeriodicWorkRequestBuilder<ExtremeWorker>(30, TimeUnit.MINUTES).addTag("EXTREME_WORK").build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("EXTREME_WORK", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

const val NOTIF_ID_DAILY = 1001
const val NOTIF_ID_EXTREME = 1002

/** [id]: фиксированный id заменяет прошлое уведомление того же типа, а не копит их в шторке. */
fun sendNotification(context: Context, title: String, text: String, id: Int = Random.nextInt()) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(NotificationChannel("courier_ch", "Сводка курьера", NotificationManager.IMPORTANCE_HIGH))
    }
    // Без CLEAR_TASK: тап по уведомлению не должен убивать открытое приложение (и несохранённый ввод)
    val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
    val pi = PendingIntent.getActivity(context, 0, intent, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    val notif = NotificationCompat.Builder(context, "courier_ch")
        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        manager.notify(id, notif)
    }
}

package com.example.myapplication

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
// НАСТРОЙКИ (SharedPreferences). Смены и домофоны живут в Room (см. CourierRepository).
// ============================================================================

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("courier_settings", Context.MODE_PRIVATE)
    private val secretPrefs = context.getSharedPreferences("courier_secrets", Context.MODE_PRIVATE)


    // ---------- Регион и недавние города ----------
    fun saveRegion(region: Region) {
        prefs.edit().putString("region_id", region.id).putString("region_name", region.name)
            .putString("region_lat", region.lat.toString()).putString("region_lon", region.lon.toString()).apply()
        addRecentRegion(region)
    }

    fun getSelectedRegion(): Region {
        val id = prefs.getString("region_id", "msk") ?: "msk"
        availableRegionsList.find { it.id == id }?.let { return it }
        return Region(
            id, prefs.getString("region_name", "Москва") ?: "Москва",
            prefs.getString("region_lat", "55.7558")?.toDoubleOrNull() ?: 55.7558,
            prefs.getString("region_lon", "37.6173")?.toDoubleOrNull() ?: 37.6173
        )
    }

    fun getRecentRegions(): List<Region> = try {
        val arr = JSONArray(prefs.getString("recent_regions", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Region(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
        }
    } catch (_: Exception) { emptyList() }

    private fun addRecentRegion(region: Region) {
        if (availableRegionsList.any { it.id == region.id }) return
        val updated = (listOf(region) + getRecentRegions().filter { it.id != region.id }).take(5)
        val arr = JSONArray()
        updated.forEach { r -> arr.put(JSONObject().put("id", r.id).put("name", r.name).put("lat", r.lat).put("lon", r.lon)) }
        prefs.edit().putString("recent_regions", arr.toString()).apply()
    }

    // ---------- Внешний вид ----------
    fun saveTransport(t: TransportType) = prefs.edit().putString("transport", t.name).apply()
    fun getTransport(): TransportType = try { TransportType.valueOf(prefs.getString("transport", TransportType.EBIKE.name)!!) } catch (_: Exception) { TransportType.EBIKE }

    fun saveTheme(theme: AppThemeMode) = prefs.edit().putString("theme", theme.name).apply()
    fun getTheme(): AppThemeMode = try { AppThemeMode.valueOf(prefs.getString("theme", AppThemeMode.SYSTEM.name)!!) } catch (_: Exception) { AppThemeMode.SYSTEM }

    fun saveAmbientEnabled(enabled: Boolean) = prefs.edit().putBoolean("ambient", enabled).apply()
    fun isAmbientEnabled(): Boolean = prefs.getBoolean("ambient", true)
    fun saveAmbientBrightness(value: Float) = prefs.edit().putFloat("ambient_brightness", value).apply()
    fun getAmbientBrightness(): Float = prefs.getFloat("ambient_brightness", 0.28f)
    fun saveAmbientSpeed(value: Float) = prefs.edit().putFloat("ambient_speed", value).apply()
    fun getAmbientSpeed(): Float = prefs.getFloat("ambient_speed", 1.0f)
    fun saveAmbientRadius(value: Float) = prefs.edit().putFloat("ambient_radius", value).apply()
    fun getAmbientRadius(): Float = prefs.getFloat("ambient_radius", 1500f)

    // ---------- Текущая смена ----------
    fun saveLiveShift(orders: Int, gross: Int) = prefs.edit().putInt("live_orders", orders).putInt("live_gross", gross).apply()
    /** Полное состояние текущей смены (сброс/отмена). */
    fun saveLiveState(orders: Int, gross: Int, tips: Int, startMs: Long, pausedAtMs: Long = 0L, pausedTotalMs: Long = 0L) = prefs.edit()
        .putInt("live_orders", orders).putInt("live_gross", gross).putInt("live_tips", tips)
        .putLong("shift_start_ms", startMs).putLong("shift_paused_at_ms", pausedAtMs).putLong("shift_paused_total_ms", pausedTotalMs).commit()
    fun getLiveOrders(): Int = prefs.getInt("live_orders", 0)
    fun getLiveGross(): Int = prefs.getInt("live_gross", 0)
    fun saveLiveTips(tips: Int) = prefs.edit().putInt("live_tips", tips).apply()
    fun getLiveTips(): Int = prefs.getInt("live_tips", 0)
    /** Момент старта смены (таймер), 0 — смена не начата. */
    fun saveShiftStart(ms: Long) = prefs.edit().putLong("shift_start_ms", ms).putLong("shift_paused_at_ms", 0L).putLong("shift_paused_total_ms", 0L).commit()
    fun getShiftStart(): Long = prefs.getLong("shift_start_ms", 0L)
    fun getShiftPausedAt(): Long = prefs.getLong("shift_paused_at_ms", 0L)
    fun getShiftPausedTotal(): Long = prefs.getLong("shift_paused_total_ms", 0L)
    /** Последняя сумма заказа — для кнопки виджета и подстановки в диалог. */
    fun saveLastOrderPrice(p: Int) = prefs.edit().putInt("last_order_price", p).apply()
    fun getLastOrderPrice(): Int = prefs.getInt("last_order_price", 250)

    /** Поставить смену на паузу или снять с паузы. */
    @Synchronized
    fun toggleShiftPause(nowMs: Long = System.currentTimeMillis()) {
        val start = getShiftStart()
        if (start <= 0L) return
        val pausedAt = getShiftPausedAt()
        val pausedTotal = getShiftPausedTotal()
        if (pausedAt == 0L) {
            prefs.edit().putLong("shift_paused_at_ms", nowMs).commit()
        } else {
            val delta = (nowMs - pausedAt).coerceAtLeast(0L)
            prefs.edit().putLong("shift_paused_at_ms", 0L).putLong("shift_paused_total_ms", pausedTotal + delta).commit()
        }
    }

    /** Добавить заказ (или только чаевые при price = 0) в текущую смену. Таймер стартует сам. */
    @Synchronized
    fun addLiveOrder(price: Int, tips: Int, nowMs: Long = System.currentTimeMillis()) {
        val p = price.coerceAtLeast(0)
        val t = tips.coerceAtLeast(0)
        if (p == 0 && t == 0) return
        val e = prefs.edit()
            .putInt("live_gross", getLiveGross() + p + t)
            .putInt("live_tips", getLiveTips() + t)
        if (p > 0) {
            e.putInt("live_orders", getLiveOrders() + 1)
            e.putInt("last_order_price", p)
        }
        if (getShiftStart() == 0L) {
            e.putLong("shift_start_ms", nowMs)
                .putLong("shift_paused_at_ms", 0L)
                .putLong("shift_paused_total_ms", 0L)
        }
        e.commit()
    }

    // ---------- Уведомления ----------
    fun saveNotifEnabled(enabled: Boolean) = prefs.edit().putBoolean("notif_enabled", enabled).apply()
    fun getNotifEnabled(): Boolean = prefs.getBoolean("notif_enabled", false)
    fun saveExtremeNotifEnabled(enabled: Boolean) = prefs.edit().putBoolean("notif_ext_enabled", enabled).apply()
    fun getExtremeNotifEnabled(): Boolean = prefs.getBoolean("notif_ext_enabled", false)
    fun saveNotifTime(hour: Int, minute: Int) = prefs.edit().putInt("notif_hour", hour).putInt("notif_minute", minute).apply()
    fun getNotifHour(): Int = prefs.getInt("notif_hour", 8)
    fun getNotifMinute(): Int = prefs.getInt("notif_minute", 0)
    fun saveNotifTone(tone: NotificationTone) = prefs.edit().putString("notif_tone", tone.name).apply()
    fun getNotifTone(): NotificationTone = try { NotificationTone.valueOf(prefs.getString("notif_tone", NotificationTone.NORMAL.name)!!) } catch (_: Exception) { NotificationTone.NORMAL }

    /** Тихие часы: экстремальные уведомления не приходят в это время (23:00–07:00). */
    fun saveQuietHours(enabled: Boolean) = prefs.edit().putBoolean("quiet_enabled", enabled).apply()
    fun getQuietHours(): Boolean = prefs.getBoolean("quiet_enabled", true)

    fun getLastExtremeNotifTime(): Long = prefs.getLong("last_ext_notif_ms", 0L)
    fun setLastExtremeNotifTime(ms: Long) = prefs.edit().putLong("last_ext_notif_ms", ms).apply()

    // ---------- Источники / ключи / цель ----------
    fun saveSafeSources(enabled: Boolean) = prefs.edit().putBoolean("safe_sources", enabled).apply()
    fun getSafeSources(): Boolean = prefs.getBoolean("safe_sources", false)
    fun saveYandexKey(key: String) {
        secretPrefs.edit().putString("yandex_key", key).apply()
        if (prefs.contains("yandex_key")) prefs.edit().remove("yandex_key").apply()
    }
    fun getYandexKey(): String {
        val secret = secretPrefs.getString("yandex_key", null)
        if (secret != null) return secret
        val legacy = prefs.getString("yandex_key", null)
        if (!legacy.isNullOrEmpty()) {
            secretPrefs.edit().putString("yandex_key", legacy).apply()
            prefs.edit().remove("yandex_key").apply()
            return legacy
        }
        return ""
    }
    fun saveMonthlyGoal(goal: Int) = prefs.edit().putInt("monthly_goal", goal).apply()
    fun getMonthlyGoal(): Int = prefs.getInt("monthly_goal", 80000)
    /** Налог самозанятого, %: 0 — не учитывать, 4 — с физлиц, 6 — с юрлиц/ИП. */
    fun saveTaxRate(rate: Int) = prefs.edit().putInt("tax_rate", rate).apply()
    fun getTaxRate(): Int = prefs.getInt("tax_rate", 0)

    // ---------- Автобэкап ----------
    fun saveAutoBackup(enabled: Boolean, treeUri: String?) =
        prefs.edit().putBoolean("auto_backup", enabled).putString("auto_backup_uri", treeUri).apply()
    fun isAutoBackupEnabled(): Boolean = prefs.getBoolean("auto_backup", false)
    fun getAutoBackupUri(): String? = prefs.getString("auto_backup_uri", null)
    fun saveAutoBackupResult(ms: Long, error: String?) =
        prefs.edit().putLong("auto_backup_last_ms", ms).putString("auto_backup_error", error).apply()
    fun getAutoBackupLastMs(): Long = prefs.getLong("auto_backup_last_ms", 0L)
    fun getAutoBackupError(): String? = prefs.getString("auto_backup_error", null)

    // ---------- Перенос из старого формата в Room ----------
    fun isLegacyMigrated(): Boolean = prefs.getBoolean("room_migrated", false)
    fun legacyShiftsJson(): String = prefs.getString("shifts_json", "[]") ?: "[]"
    fun legacyIntercomsJson(): String = prefs.getString("intercoms_json", "[]") ?: "[]"
    fun markLegacyMigrated() = prefs.edit().putBoolean("room_migrated", true).remove("shifts_json").remove("intercoms_json").apply()

    // ---------- Кэш прогноза (показывается, если нет сети) ----------
    fun saveWeatherCache(data: WeatherData, regionId: String) {
        try {
            val w = data.current
            val cur = JSONObject().apply {
                put("weather", w.weather.name); put("temp", w.temp); put("feelsLike", w.feelsLike); put("humidity", w.humidity)
                put("windSpeed", w.windSpeed); put("windGusts", w.windGusts); put("uvIndex", w.uvIndex)
                put("pressureMmHg", w.pressureMmHg); put("pressureDelta", w.pressureDeltaMmHg)
                put("isDay", w.isDay); put("hasIceRisk", w.hasIceRisk)
                put("sunrise", w.sunriseStr); put("sunset", w.sunsetStr); put("source", w.source)
                put("darkTitle", w.darknessBannerTitle); put("darkHint", w.darknessHint)
                if (w.utcOffsetSeconds != null) put("utcOffset", w.utcOffsetSeconds)
            }
            val fc = JSONArray().also { a ->
                data.forecast.forEach { a.put(JSONObject().put("d", it.dateStr).put("w", it.weather.name).put("min", it.minTemp).put("max", it.maxTemp)) }
            }
            val hr = JSONArray().also { a ->
                data.hourlyTimeline.forEach {
                    a.put(
                        JSONObject().put("h", it.hour).put("t", it.timeStr).put("temp", it.temp).put("w", it.weather.name)
                            .put("s", it.score).put("c", it.color.toArgb()).put("ms", it.timeMs)
                    )
                }
            }
            val root = JSONObject().put("region", regionId).put("at", data.fetchedAtMs).put("cur", cur).put("fc", fc).put("hr", hr)
            prefs.edit().putString("weather_cache", root.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun loadWeatherCache(regionId: String): WeatherData? = try {
        val raw = prefs.getString("weather_cache", null)
        val root = if (raw == null) null else JSONObject(raw)
        // кэш принадлежит городу — чужой не показываем
        if (root == null || root.optString("region") != regionId) null else {
            val c = root.getJSONObject("cur")
            val cur = DetailedWeather(
                weather = Weather.valueOf(c.getString("weather")), temp = c.getInt("temp"), feelsLike = c.getInt("feelsLike"),
                humidity = c.getInt("humidity"), windSpeed = c.getDouble("windSpeed"), windGusts = c.getDouble("windGusts"),
                uvIndex = c.getDouble("uvIndex"), pressureMmHg = c.getInt("pressureMmHg"), pressureDeltaMmHg = c.getInt("pressureDelta"),
                isDay = c.getBoolean("isDay"), hasIceRisk = c.getBoolean("hasIceRisk"),
                upcomingAlert = null, // прогноз осадков из кэша уже неактуален
                sunriseStr = c.getString("sunrise"), sunsetStr = c.getString("sunset"), source = c.getString("source"),
                darknessBannerTitle = c.optString("darkTitle", ""), darknessHint = c.optString("darkHint", ""),
                utcOffsetSeconds = if (c.has("utcOffset")) c.getInt("utcOffset") else null
            )
            val fa = root.getJSONArray("fc")
            val forecast = (0 until fa.length()).map {
                val o = fa.getJSONObject(it)
                DailyForecast(o.getString("d"), Weather.valueOf(o.getString("w")), o.getInt("min"), o.getInt("max"))
            }
            val ha = root.getJSONArray("hr")
            val hourly = (0 until ha.length()).map {
                val o = ha.getJSONObject(it)
                HourlyScoreItem(o.getInt("h"), o.getString("t"), o.getInt("temp"), Weather.valueOf(o.getString("w")), o.getInt("s"), Color(o.getInt("c")), o.getLong("ms"))
            }
            WeatherData(cur, forecast, hourly, root.getLong("at"))
        }
    } catch (_: Exception) {
        null
    }
}

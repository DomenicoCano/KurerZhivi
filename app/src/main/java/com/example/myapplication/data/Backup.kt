package com.example.myapplication

import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
// РЕЗЕРВНАЯ КОПИЯ (файл JSON). Формат v4: смены/домофоны — настоящие массивы,
// состояние живой смены (включая паузу и чаевые), настройки уведомлений и закрепление домофонов.
// Старые форматы (v1–v3, массивы строкой) тоже читаются.
// Ключ Яндекс.Погоды в новый бэкап НЕ попадает (файл может уйти в облако/мессенджер).
// ============================================================================

data class BackupData(
    val region: Region?,
    val transport: TransportType?,
    val theme: AppThemeMode?,
    val ambient: Boolean?,
    val ambientBrightness: Float?,
    val ambientSpeed: Float?,
    val ambientRadius: Float?,
    val monthlyGoal: Int?,
    val liveOrders: Int?,
    val liveGross: Int?,
    val safeSources: Boolean?,
    val taxRate: Int? = null,
    val yandexKey: String?,
    val shifts: List<ShiftEntry>?,
    val intercoms: List<IntercomNote>?,
    val liveTips: Int? = null,
    val shiftStartMs: Long? = null,
    val shiftPausedAtMs: Long? = null,
    val shiftPausedTotalMs: Long? = null,
    val lastOrderPrice: Int? = null,
    val notifEnabled: Boolean? = null,
    val extremeNotifEnabled: Boolean? = null,
    val quietHours: Boolean? = null,
    val notifHour: Int? = null,
    val notifMinute: Int? = null,
    val notifTone: NotificationTone? = null
)

object Backup {
    const val VERSION = 4

    fun export(settings: SettingsManager, shifts: List<ShiftEntry>, intercoms: List<IntercomNote>): String {
        val root = JSONObject()
        root.put("version", VERSION)
        val reg = settings.getSelectedRegion()
        root.put("region_id", reg.id); root.put("region_name", reg.name); root.put("region_lat", reg.lat); root.put("region_lon", reg.lon)
        root.put("transport", settings.getTransport().name)
        root.put("theme", settings.getTheme().name)
        root.put("ambient", settings.isAmbientEnabled())
        root.put("ambient_brightness", settings.getAmbientBrightness().toDouble())
        root.put("ambient_speed", settings.getAmbientSpeed().toDouble())
        root.put("ambient_radius", settings.getAmbientRadius().toDouble())
        root.put("monthly_goal", settings.getMonthlyGoal())
        root.put("live_orders", settings.getLiveOrders())
        root.put("live_gross", settings.getLiveGross())
        root.put("live_tips", settings.getLiveTips())
        root.put("shift_start_ms", settings.getShiftStart())
        root.put("shift_paused_at_ms", settings.getShiftPausedAt())
        root.put("shift_paused_total_ms", settings.getShiftPausedTotal())
        root.put("last_order_price", settings.getLastOrderPrice())
        root.put("notif_enabled", settings.getNotifEnabled())
        root.put("notif_ext_enabled", settings.getExtremeNotifEnabled())
        root.put("quiet_enabled", settings.getQuietHours())
        root.put("notif_hour", settings.getNotifHour())
        root.put("notif_minute", settings.getNotifMinute())
        root.put("notif_tone", settings.getNotifTone().name)
        root.put("safe_sources", settings.getSafeSources())
        root.put("tax_rate", settings.getTaxRate())
        root.put("shifts", JSONArray().also { a ->
            shifts.forEach { s ->
                a.put(
                    JSONObject().put("id", s.id).put("dateStr", s.dateStr).put("hours", s.hours).put("gross", s.gross)
                        .put("orders", s.orders).put("km", s.km).put("expFuel", s.expFuel).put("expRepair", s.expRepair).put("expOther", s.expOther).put("weather", s.weather)
                        .put("tips", s.tips).put("tax", s.tax).put("service", s.service)
                )
            }
        })
        root.put("intercoms", JSONArray().also { a ->
            intercoms.forEach { n ->
                a.put(
                    JSONObject().put("id", n.id).put("address", n.address).put("code", n.code)
                        .put("comment", n.comment).put("category", n.category).put("pinned", n.pinned)
                )
            }
        })
        return root.toString(2)
    }

    private fun array(root: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) {
            when (val v = root.opt(k)) {
                is JSONArray -> return v
                is String -> return JSONArray(v)
            }
        }
        return null
    }

    /** Полный разбор и проверка БЕЗ записи. Бросает исключение при некорректных данных. */
    fun parse(jsonStr: String): BackupData {
        val root = JSONObject(jsonStr.trim())
        val shifts = array(root, "shifts", "shifts_json")?.let { parseShiftsJson(it.toString()) }
        val intercoms = array(root, "intercoms", "intercoms_json")?.let { parseIntercomsJson(it.toString()) }
        val region = if (root.has("region_id")) {
            val lat = root.optDouble("region_lat", Double.NaN)
            val lon = root.optDouble("region_lon", Double.NaN)
            require(!lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0)
            Region(root.getString("region_id"), root.optString("region_name", "Москва"), lat, lon)
        } else null
        val data = BackupData(
            region = region,
            transport = if (root.has("transport")) TransportType.valueOf(root.getString("transport")) else null,
            theme = if (root.has("theme")) AppThemeMode.valueOf(root.getString("theme")) else null,
            ambient = if (root.has("ambient")) root.getBoolean("ambient") else null,
            ambientBrightness = if (root.has("ambient_brightness")) root.getDouble("ambient_brightness").toFloat() else null,
            ambientSpeed = if (root.has("ambient_speed")) root.getDouble("ambient_speed").toFloat() else null,
            ambientRadius = if (root.has("ambient_radius")) root.getDouble("ambient_radius").toFloat() else null,
            monthlyGoal = if (root.has("monthly_goal")) root.getInt("monthly_goal") else null,
            liveOrders = if (root.has("live_orders") && root.has("live_gross")) root.getInt("live_orders") else null,
            liveGross = if (root.has("live_orders") && root.has("live_gross")) root.getInt("live_gross") else null,
            safeSources = if (root.has("safe_sources")) root.getBoolean("safe_sources") else null,
            taxRate = if (root.has("tax_rate")) root.getInt("tax_rate").coerceIn(0, 20) else null,
            yandexKey = if (root.has("yandex_key")) root.getString("yandex_key") else null, // только из старых бэкапов
            shifts = shifts,
            intercoms = intercoms,
            liveTips = if (root.has("live_tips")) root.getInt("live_tips") else null,
            shiftStartMs = if (root.has("shift_start_ms")) root.getLong("shift_start_ms") else null,
            shiftPausedAtMs = if (root.has("shift_paused_at_ms")) root.getLong("shift_paused_at_ms") else null,
            shiftPausedTotalMs = if (root.has("shift_paused_total_ms")) root.getLong("shift_paused_total_ms") else null,
            lastOrderPrice = if (root.has("last_order_price")) root.getInt("last_order_price") else null,
            notifEnabled = if (root.has("notif_enabled")) root.getBoolean("notif_enabled") else null,
            extremeNotifEnabled = if (root.has("notif_ext_enabled")) root.getBoolean("notif_ext_enabled") else null,
            quietHours = if (root.has("quiet_enabled")) root.getBoolean("quiet_enabled") else null,
            notifHour = if (root.has("notif_hour")) root.getInt("notif_hour").coerceIn(0, 23) else null,
            notifMinute = if (root.has("notif_minute")) root.getInt("notif_minute").coerceIn(0, 59) else null,
            notifTone = if (root.has("notif_tone")) NotificationTone.valueOf(root.getString("notif_tone")) else null
        )
        require(data.shifts != null || data.intercoms != null || data.region != null) { "Пустой бэкап" }
        return data
    }

    /** Записывает в настройки (смены/домофоны пишет репозиторий). */
    fun applySettings(data: BackupData, s: SettingsManager) {
        data.region?.let { s.saveRegion(it) }
        data.transport?.let { s.saveTransport(it) }
        data.theme?.let { s.saveTheme(it) }
        data.ambient?.let { s.saveAmbientEnabled(it) }
        data.ambientBrightness?.let { s.saveAmbientBrightness(it.coerceIn(0.05f, 0.70f)) }
        data.ambientSpeed?.let { s.saveAmbientSpeed(it.coerceIn(0f, 3f)) }
        data.ambientRadius?.let { s.saveAmbientRadius(it.coerceIn(600f, 2600f)) }
        data.monthlyGoal?.let { s.saveMonthlyGoal(it) }
        if (data.liveOrders != null && data.liveGross != null) {
            s.saveLiveState(
                data.liveOrders, data.liveGross, data.liveTips ?: 0,
                data.shiftStartMs ?: 0L, data.shiftPausedAtMs ?: 0L, data.shiftPausedTotalMs ?: 0L
            )
        }
        data.lastOrderPrice?.let { if (it > 0) s.saveLastOrderPrice(it) }
        data.notifEnabled?.let { s.saveNotifEnabled(it) }
        data.extremeNotifEnabled?.let { s.saveExtremeNotifEnabled(it) }
        data.quietHours?.let { s.saveQuietHours(it) }
        if (data.notifHour != null || data.notifMinute != null) {
            s.saveNotifTime(data.notifHour ?: s.getNotifHour(), data.notifMinute ?: s.getNotifMinute())
        }
        data.notifTone?.let { s.saveNotifTone(it) }
        data.safeSources?.let { s.saveSafeSources(it) }
        data.taxRate?.let { s.saveTaxRate(it) }
        data.yandexKey?.let { s.saveYandexKey(it) }
    }
}

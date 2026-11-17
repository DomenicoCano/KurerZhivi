package com.example.myapplication

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

/** Единая точка доступа к смене/домофонам (Room). */
class CourierRepository(context: Context, private val settings: SettingsManager) {
    private val db = AppDatabase.get(context)
    private val shiftDao = db.shiftDao()
    private val intercomDao = db.intercomDao()

    val shifts: Flow<List<ShiftEntry>> = shiftDao.observeAll()
    val intercoms: Flow<List<IntercomNote>> = intercomDao.observeAll()

    suspend fun addShift(s: ShiftEntry) = shiftDao.insert(s)
    /** Замена смены с тем же id (REPLACE) — редактирование. */
    suspend fun updateShift(s: ShiftEntry) = shiftDao.insert(s)
    suspend fun deleteShift(id: Long) = shiftDao.delete(id)
    suspend fun allShifts() = shiftDao.getAll()

    suspend fun addIntercom(n: IntercomNote) = intercomDao.insert(n)
    suspend fun updateIntercom(n: IntercomNote) = intercomDao.insert(n)
    suspend fun deleteIntercom(id: Long) = intercomDao.delete(id)
    suspend fun allIntercoms() = intercomDao.getAll()

    /** Полная замена данных (восстановление из бэкапа). */
    suspend fun replaceAll(shifts: List<ShiftEntry>?, intercoms: List<IntercomNote>?) {
        // В одной транзакции: сбой посередине не оставит пустую базу
        db.withTransaction {
            if (shifts != null) { shiftDao.clear(); shiftDao.insertAll(shifts) }
            if (intercoms != null) { intercomDao.clear(); intercomDao.insertAll(intercoms) }
        }
    }

    /** Разовый перенос данных из старого формата (JSON в SharedPreferences) в Room. */
    suspend fun migrateLegacyIfNeeded() {
        if (settings.isLegacyMigrated()) return
        try {
            val oldShifts = parseShiftsJson(settings.legacyShiftsJson())
            val oldIntercoms = parseIntercomsJson(settings.legacyIntercomsJson())
            if (oldShifts.isNotEmpty()) shiftDao.insertAll(oldShifts)
            if (oldIntercoms.isNotEmpty()) intercomDao.insertAll(oldIntercoms)
            settings.markLegacyMigrated()
        } catch (_: Exception) {
            // оставляем старые данные нетронутыми — повторим при следующем запуске
        }
    }
}

// ---------- JSON-разбор (используется миграцией и импортом бэкапа) ----------

fun parseShiftsJson(raw: String): List<ShiftEntry> {
    val arr = JSONArray(raw)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val legacyExpenses = o.optInt("expenses", 0)
        ShiftEntry(
            id = o.getLong("id"), dateStr = o.getString("dateStr"), hours = o.getDouble("hours"),
            gross = o.getInt("gross"), orders = o.optInt("orders", 0), km = o.optDouble("km", 0.0),
            expFuel = o.optInt("expFuel", 0), expRepair = o.optInt("expRepair", 0),
            // старые записи имели одну сумму расходов — кладём в «прочее»
            expOther = if (o.has("expOther")) o.optInt("expOther", 0) else legacyExpenses,
            weather = o.optString("weather", ""),
            tips = o.optInt("tips", 0), tax = o.optInt("tax", 0), service = o.optString("service", "")
        )
    }
}

fun parseIntercomsJson(raw: String): List<IntercomNote> {
    val arr = JSONArray(raw)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        IntercomNote(
            o.getLong("id"), o.getString("address"), o.getString("code"),
            o.optString("comment", ""), o.optString("category", "Домофон / ЖК"),
            o.optBoolean("pinned", false)
        )
    }
}

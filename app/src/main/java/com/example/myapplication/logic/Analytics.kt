package com.example.myapplication

import java.util.Calendar
import java.util.Locale

// ============================================================================
// АНАЛИТИКА ЛИЧНЫХ ЗАРАБОТКОВ (по сменам из базы)
// ============================================================================

data class DayNet(val dayStartMs: Long, val label: String, val net: Int)
data class WeekdayStat(val isoDow: Int, val title: String, val avgPerHour: Int, val shifts: Int)
data class ShiftTotals(
    val shifts: Int, val net: Int, val gross: Int, val hours: Double, val orders: Int, val km: Double,
    val expFuel: Int, val expRepair: Int, val expOther: Int, val tips: Int = 0, val tax: Int = 0
) {
    val expenses: Int get() = expFuel + expRepair + expOther
    val perHour: Int get() = if (hours > 0) Math.round(net / hours).toInt() else 0
    val perKm: Double get() = if (km > 0) net / km else 0.0
    /** Расходы на 1 км (топливо + ремонт), ₽ */
    val costPerKm: Double get() = if (km > 0) (expFuel + expRepair) / km else 0.0
}

private val WEEKDAY_TITLES = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")

fun isoDayOfWeek(cal: Calendar): Int = if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else cal.get(Calendar.DAY_OF_WEEK) - 1

private fun startOfDay(ms: Long): Calendar = Calendar.getInstance().apply {
    timeInMillis = ms
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

/** Смены текущего календарного месяца (id смены = время создания в мс). */
fun shiftsOfMonth(shifts: List<ShiftEntry>, now: Calendar = Calendar.getInstance()): List<ShiftEntry> = shifts.filter {
    val c = Calendar.getInstance().apply { timeInMillis = it.id }
    c.get(Calendar.YEAR) == now.get(Calendar.YEAR) && c.get(Calendar.MONTH) == now.get(Calendar.MONTH)
}

fun totalsOf(shifts: List<ShiftEntry>): ShiftTotals = ShiftTotals(
    shifts = shifts.count { it.hours > 0 }, net = shifts.sumOf { it.net }, gross = shifts.sumOf { it.gross },
    hours = shifts.sumOf { it.hours }, orders = shifts.sumOf { it.orders }, km = shifts.sumOf { it.km },
    expFuel = shifts.sumOf { it.expFuel }, expRepair = shifts.sumOf { it.expRepair }, expOther = shifts.sumOf { it.expOther },
    tips = shifts.sumOf { it.tips }, tax = shifts.sumOf { it.tax }
)

/** Чистый доход по дням за последние [days] дней (включая сегодня), от старых к новым. */
fun dailyNet(shifts: List<ShiftEntry>, days: Int, now: Calendar = Calendar.getInstance()): List<DayNet> {
    val today = startOfDay(now.timeInMillis)
    val result = mutableListOf<DayNet>()
    for (back in days - 1 downTo 0) {
        val day = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -back) }
        val start = day.timeInMillis
        val end = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
        val net = shifts.filter { it.id in start until end }.sumOf { it.net }
        val label = String.format(Locale.US, "%02d", day.get(Calendar.DAY_OF_MONTH))
        result.add(DayNet(start, label, net))
    }
    return result
}

/** Средний чистый доход в час по дням недели; только дни, где есть смены. Лучшие — первыми. */
fun weekdayStats(shifts: List<ShiftEntry>): List<WeekdayStat> {
    val groups = shifts.filter { it.hours > 0 }.groupBy { isoDayOfWeek(Calendar.getInstance().apply { timeInMillis = it.id }) }
    return groups.map { (dow, list) ->
        val hours = list.sumOf { it.hours }
        WeekdayStat(dow, WEEKDAY_TITLES[dow - 1], Math.round(list.sumOf { it.net } / hours).toInt(), list.size)
    }.sortedByDescending { it.avgPerHour }
}

/** Итоги за последние 7 дней и за предыдущие 7 (для сравнения). */
fun weekVsPrevious(shifts: List<ShiftEntry>, now: Calendar = Calendar.getInstance()): Pair<ShiftTotals, ShiftTotals> {
    val today = startOfDay(now.timeInMillis)
    val endCur = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    val startCur = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -6) }.timeInMillis
    val startPrev = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -13) }.timeInMillis
    val cur = shifts.filter { it.id in startCur until endCur }
    val prev = shifts.filter { it.id in startPrev until startCur }
    return totalsOf(cur) to totalsOf(prev)
}

/** Сколько нужно зарабатывать в день, чтобы выполнить цель к концу месяца (включая сегодня). */
fun neededPerDay(goal: Int, earned: Int, now: Calendar = Calendar.getInstance()): Int {
    val remaining = (goal - earned).coerceAtLeast(0)
    if (remaining == 0) return 0
    val daysLeft = (now.getActualMaximum(Calendar.DAY_OF_MONTH) - now.get(Calendar.DAY_OF_MONTH) + 1).coerceAtLeast(1)
    return Math.round(remaining.toDouble() / daysLeft).toInt()
}

/** Налог самозанятого с суммы [gross] по ставке [ratePct] %, округление до рубля. */
fun taxFor(gross: Int, ratePct: Int): Int = if (ratePct <= 0 || gross <= 0) 0 else Math.round(gross * ratePct / 100.0).toInt()

/** Прогноз чистого заработка за месяц при текущем темпе (заработано / прошедшие дни × дней в месяце). */
fun monthForecast(earned: Int, now: Calendar = Calendar.getInstance()): Int {
    val day = now.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
    return Math.round(earned.toDouble() / day * now.getActualMaximum(Calendar.DAY_OF_MONTH)).toInt()
}

/** Реалистичный прогноз за месяц: в первые 2 дня месяца при < 2 сменах не показываем завышенную экстраполяцию. */
fun smartMonthForecast(earned: Int, monthShiftCount: Int, now: Calendar = Calendar.getInstance()): Int? {
    val day = now.get(Calendar.DAY_OF_MONTH)
    if (earned <= 0 || (day < 3 && monthShiftCount < 2)) return null
    return monthForecast(earned, now)
}

data class ServiceStat(val service: String, val perHour: Int, val net: Int, val shifts: Int, val avgCheck: Int, val tipsShare: Int)

/** Сравнение сервисов (агрегаторов): чистые ₽/ч, лучшие — первыми. Смены без сервиса не учитываются. */
fun serviceStats(shifts: List<ShiftEntry>): List<ServiceStat> =
    shifts.filter { it.service.isNotBlank() && it.hours > 0 }.groupBy { it.service.trim() }.map { (name, list) ->
        val t = totalsOf(list)
        ServiceStat(name, t.perHour, t.net, list.size, if (t.orders > 0) t.gross / t.orders else 0,
            if (t.gross > 0) Math.round(t.tips * 100.0 / t.gross).toInt() else 0)
    }.sortedByDescending { it.perHour }

/** Длительность в часах между [startMs] и [nowMs], округлённая до 0.1 ч (минимум 0.1). */
fun elapsedHours(startMs: Long, nowMs: Long): Double =
    if (startMs <= 0 || nowMs <= startMs) 0.0 else (Math.round((nowMs - startMs) / 360_000.0) / 10.0).coerceAtLeast(0.1)

/** Активное время на линии с учётом пауз (в мс). */
fun activeShiftElapsedMs(startMs: Long, pausedAtMs: Long, pausedTotalMs: Long, nowMs: Long = System.currentTimeMillis()): Long {
    if (startMs <= 0L) return 0L
    val end = if (pausedAtMs > 0L) pausedAtMs else nowMs
    return (end - startMs - pausedTotalMs).coerceAtLeast(0L)
}

/** Активные часы на линии с учётом пауз, округлённые до 0.1 ч (минимум 0.1 при ненулевом активном времени). */
fun activeElapsedHours(startMs: Long, pausedAtMs: Long, pausedTotalMs: Long, nowMs: Long = System.currentTimeMillis()): Double {
    val ms = activeShiftElapsedMs(startMs, pausedAtMs, pausedTotalMs, nowMs)
    return if (ms <= 0L) 0.0 else (Math.round(ms / 360_000.0) / 10.0).coerceAtLeast(0.1)
}

/** Форматирование длительности смены («2 ч 15 мин» или «45 мин»). */
fun formatShiftDuration(ms: Long): String {
    val totalMin = (ms / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "$h ч $m мин" else "$m мин"
}

/** Разбор быстрого ввода заказа из шторки уведомлений: «320», «320+50», «320 50», «+100» (только чаевые). */
fun parseQuickOrderInput(raw: String): Pair<Int, Int>? {
    val s = raw.trim()
    if (s.isEmpty()) return null
    if (s.startsWith("+") && !s.drop(1).contains('+')) {
        val tip = s.drop(1).trim().toIntOrNull() ?: return null
        return if (tip > 0) 0 to tip else null
    }
    val parts = s.split(Regex("[+\\s,;]+")).filter { it.isNotEmpty() }
    return when (parts.size) {
        1 -> {
            val a = parts[0].toIntOrNull() ?: return null
            if (a > 0) a to 0 else null
        }
        2 -> {
            val a = parts[0].toIntOrNull() ?: return null
            val b = parts[1].toIntOrNull() ?: return null
            if (a >= 0 && b >= 0 && (a > 0 || b > 0)) a to b else null
        }
        else -> null
    }
}

/**
 * CSV для Excel (русская локаль): разделитель «;», десятичная запятая, BOM для кириллицы.
 * Нужен для учёта и налоговой отчётности самозанятого.
 */
fun shiftsToCsv(shifts: List<ShiftEntry>): String {
    val sdf = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))
    fun d(v: Double) = String.format(Locale.US, "%.1f", v).replace('.', ',')
    fun q(s: String) = if (s.any { it == ';' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    val sb = StringBuilder("\uFEFF")
    sb.append("Дата;Сервис;Часы;Заказы;Грязными;в т.ч. чаевые;Топливо/зарядка;Ремонт/ТО;Прочее;Налог;Чистыми;Чистыми в час;Км\r\n")
    shifts.sortedBy { it.id }.forEach { s ->
        sb.append(listOf(
            sdf.format(java.util.Date(s.id)), q(s.service), d(s.hours), s.orders, s.gross, s.tips,
            s.expFuel, s.expRepair, s.expOther, s.tax, s.net, s.perHour, d(s.km)
        ).joinToString(";")).append("\r\n")
    }
    return sb.toString()
}

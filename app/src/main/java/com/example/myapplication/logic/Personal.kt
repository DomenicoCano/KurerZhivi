package com.example.myapplication

import java.util.Calendar

// ============================================================================
// ПЕРСОНАЛЬНАЯ КАЛИБРОВКА ПО СМЕНАМ ПОЛЬЗОВАТЕЛЯ
//
// Без смен приложение показывает приблизительные значения (общие данные и оценки).
// С несколькими сменами — считает реальные чистые ₽/ч пользователя по периодам дня,
// будням/выходным и погоде, и сравнивает со средним самого пользователя.
// Группа показывается только если в ней достаточно смен — иначе это шум, а не данные.
// Ограничения (отражаются в интерфейсе): время смены берётся по середине смены,
// погода — на момент сохранения смены.
// ============================================================================

const val MIN_SHIFTS_TOTAL = 5   // меньше — персональные выводы не показываем
const val MIN_SHIFTS_GROUP = 3   // меньше — группа не показывается

enum class DayPart(val title: String) {
    MORNING("Утро 05–11"), DAY("День 11–17"), EVENING("Вечер 17–23"), NIGHT("Ночь 23–05")
}

enum class WeatherClass(val title: String) {
    PRECIP("Осадки"), CLOUDY("Пасмурно"), CLEAR("Ясно"), FROST("Сильный мороз")
}

fun dayPartOf(hour: Int): DayPart = when (hour) {
    in 5..10 -> DayPart.MORNING
    in 11..16 -> DayPart.DAY
    in 17..22 -> DayPart.EVENING
    else -> DayPart.NIGHT
}

fun weatherClassOf(name: String): WeatherClass? = when (name) {
    Weather.LIGHT_RAIN.name, Weather.RAIN.name, Weather.STORM.name, Weather.SLEET.name, Weather.SNOW.name -> WeatherClass.PRECIP
    Weather.CLOUDY.name -> WeatherClass.CLOUDY
    Weather.SUN.name -> WeatherClass.CLEAR
    Weather.FROST.name -> WeatherClass.FROST
    else -> null // "" или UNKNOWN — не используем
}

/** Середина смены: id — момент сохранения (конец смены), смена длилась hours часов. */
fun shiftMidpoint(s: ShiftEntry): Calendar =
    Calendar.getInstance().apply { timeInMillis = s.id - (s.hours * 1_800_000.0).toLong() }

data class Insight(val label: String, val perHour: Int, val shifts: Int, val deltaPct: Int)

data class PersonalProfile(
    val totalShifts: Int,
    val overallPerHour: Int,
    val enough: Boolean,                 // достаточно смен для персональных выводов
    val shiftsToGo: Int,                 // сколько ещё смен до порога
    val byDayPart: List<Insight>,
    val byDayType: List<Insight>,
    val byWeather: List<Insight>
)

private fun perHour(list: List<ShiftEntry>): Int {
    val h = list.sumOf { it.hours }
    return if (h > 0) Math.round(list.sumOf { it.net } / h).toInt() else 0
}

private fun insights(shifts: List<ShiftEntry>, overall: Int, keyOf: (ShiftEntry) -> String?): List<Insight> =
    shifts.groupBy(keyOf).filterKeys { it != null }
        .filterValues { it.size >= MIN_SHIFTS_GROUP }
        .map { (label, list) ->
            val ph = perHour(list)
            val delta = if (overall > 0) Math.round((ph.toDouble() / overall - 1) * 100).toInt() else 0
            Insight(label!!, ph, list.size, delta)
        }
        .sortedByDescending { it.perHour }

fun personalProfile(shifts: List<ShiftEntry>): PersonalProfile {
    val valid = shifts.filter { it.hours > 0 }
    val overall = perHour(valid)
    val enough = valid.size >= MIN_SHIFTS_TOTAL
    if (!enough) return PersonalProfile(valid.size, overall, false, MIN_SHIFTS_TOTAL - valid.size, emptyList(), emptyList(), emptyList())
    return PersonalProfile(
        totalShifts = valid.size, overallPerHour = overall, enough = true, shiftsToGo = 0,
        byDayPart = insights(valid, overall) { dayPartOf(shiftMidpoint(it).get(Calendar.HOUR_OF_DAY)).title },
        byDayType = insights(valid, overall) { if (isWeekendDay(shiftMidpoint(it))) "Выходные" else "Будни" },
        byWeather = insights(valid, overall) { weatherClassOf(it.weather)?.title }
    )
}

/**
 * Подсказка «для этого момента»: ₽/ч пользователя в такое же время суток и тип дня.
 * Возвращает null, если смен в этой группе меньше порога — тогда показываем только общие оценки.
 */
fun personalHintNow(shifts: List<ShiftEntry>, now: Calendar = Calendar.getInstance()): String? {
    val valid = shifts.filter { it.hours > 0 }
    if (valid.size < MIN_SHIFTS_TOTAL) return null
    val part = dayPartOf(now.get(Calendar.HOUR_OF_DAY))
    val weekend = isWeekendDay(now)
    val group = valid.filter {
        val mid = shiftMidpoint(it)
        dayPartOf(mid.get(Calendar.HOUR_OF_DAY)) == part && isWeekendDay(mid) == weekend
    }
    if (group.size < MIN_SHIFTS_GROUP) return null
    val ph = perHour(group)
    val overall = perHour(valid)
    val delta = if (overall > 0) Math.round((ph.toDouble() / overall - 1) * 100).toInt() else 0
    val sign = if (delta >= 0) "+" else ""
    return "По вашим сменам (${if (weekend) "выходные" else "будни"}, ${part.title.lowercase()}): $ph ₽/ч чистыми, " +
        "$sign$delta% к вашему среднему (${group.size} смен)."
}

data class PersonalEstimate(
    val estimatedPerHour: Int,
    val rangeLow: Int,
    val rangeHigh: Int,
    val deltaPct: Int,
    val basisText: String,
    val isCalibrated: Boolean
)

/** Персональный ориентир чистого ₽/ч для текущего момента, погоды и балла спроса. */
fun estimatePersonalPerHour(
    shifts: List<ShiftEntry>,
    weather: DetailedWeather?,
    score: Int,
    now: Calendar = Calendar.getInstance()
): PersonalEstimate? {
    val valid = shifts.filter { it.hours > 0 }
    if (valid.isEmpty()) return null
    val overall = perHour(valid)
    if (overall <= 0) return null
    val scoreFactor = (1.0 + ((score - 55) * 0.004)).coerceIn(0.82, 1.22)
    if (valid.size < MIN_SHIFTS_TOTAL) {
        val est = Math.round(overall * scoreFactor).toInt().coerceAtLeast(50)
        val delta = Math.round((est.toDouble() / overall - 1.0) * 100).toInt()
        return PersonalEstimate(
            est, Math.round(est * 0.85).toInt(), Math.round(est * 1.15).toInt(), delta,
            "Предварительно по ${valid.size} см. (ещё ${MIN_SHIFTS_TOTAL - valid.size} до точной калибровки)",
            isCalibrated = false
        )
    }
    val part = dayPartOf(now.get(Calendar.HOUR_OF_DAY))
    val weekend = isWeekendDay(now)
    val wClass = weather?.weather?.name?.let { weatherClassOf(it) }
    val slotGroup = valid.filter {
        val mid = shiftMidpoint(it)
        dayPartOf(mid.get(Calendar.HOUR_OF_DAY)) == part && isWeekendDay(mid) == weekend
    }
    val weatherGroup = if (wClass != null) valid.filter { weatherClassOf(it.weather) == wClass } else emptyList()
    val baseEst = when {
        slotGroup.size >= MIN_SHIFTS_GROUP && weatherGroup.size >= MIN_SHIFTS_GROUP ->
            0.55 * perHour(slotGroup) + 0.25 * perHour(weatherGroup) + 0.20 * overall * scoreFactor
        slotGroup.size >= MIN_SHIFTS_GROUP ->
            0.70 * perHour(slotGroup) + 0.30 * overall * scoreFactor
        weatherGroup.size >= MIN_SHIFTS_GROUP ->
            0.55 * perHour(weatherGroup) + 0.45 * overall * scoreFactor
        else -> overall * scoreFactor
    }
    val est = Math.round(baseEst).toInt().coerceAtLeast(50)
    val delta = Math.round((est.toDouble() / overall - 1.0) * 100).toInt()
    val basis = if (slotGroup.size >= MIN_SHIFTS_GROUP) {
        "По вашим сменам (${if (weekend) "выходные" else "будни"}, ${part.title.lowercase()} • ${slotGroup.size} см.)"
    } else {
        "По вашим ${valid.size} сменам (средний $overall ₽/ч)"
    }
    return PersonalEstimate(est, Math.round(est * 0.88).toInt(), Math.round(est * 1.12).toInt(), delta, basis, isCalibrated = true)
}

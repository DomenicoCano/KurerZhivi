package com.example.myapplication

import java.util.Calendar

// ============================================================================
// СУТОЧНЫЙ РИТМ СПРОСА (будни / выходные)
//
// Принцип: каждая строка помечена, на чём основана.
//  • BASIS_CHIBBIS — исследование «Чиббис» (990 тыс. заказов, 200 городов, 2025; пересказ на retail.ru):
//    пик 18:00–19:00, большинство заказов 16:00–20:00, суббота — самый сильный день,
//    в воскресенье около трети заказов приходится на обеденный интервал.
//  • BASIS_CHRONO — исследования хронотипов («социальный джетлаг»): в выходные люди просыпаются позже.
//    Конкретных «часов начала заказов» в источниках нет — это качественный вывод.
//  • BASIS_HEURISTIC — разумная оценка без измерений. Так и подписывается в интерфейсе.
// Облачность в ритм НЕ входит: данных о её влиянии на заказы не найдено.
// Осадки повышают спрос на доставку (работы по китайским платформам) — это учтено в баллах погоды.
// ============================================================================

enum class DemandLevel(val title: String) { LOW("Низкий"), MEDIUM("Средний"), HIGH("Высокий"), PEAK("Пик") }

const val BASIS_CHIBBIS = "Исследование «Чиббис» 2025"
const val BASIS_CHRONO = "Хронотипы: в выходные встают позже"
const val BASIS_HEURISTIC = "Оценка без измерений"

data class RhythmSlot(
    val startHour: Int,
    val endHour: Int,          // не включительно, 24 = полночь
    val title: String,
    val level: DemandLevel,
    val note: String,
    val basis: String
) {
    val range: String get() = String.format("%02d:00–%02d:00", startHour, endHour % 24)
    fun contains(hour: Int) = hour in startHour until endHour
}

fun isWeekendDay(cal: Calendar): Boolean = isoDayOfWeek(cal) >= 6

fun dailyRhythm(cal: Calendar): List<RhythmSlot> {
    val dow = isoDayOfWeek(cal)
    return if (dow >= 6) weekendRhythm(dow) else weekdayRhythm()
}

private fun weekdayRhythm() = listOf(
    RhythmSlot(0, 7, "Ночь", DemandLevel.LOW, "Работают круглосуточные точки, аптеки и ночной фастфуд.", BASIS_HEURISTIC),
    RhythmSlot(7, 10, "Утро в будни", DemandLevel.MEDIUM, "Людей поднимает будильник: завтраки, кофе и выпечка в офисы.", BASIS_CHRONO),
    RhythmSlot(10, 12, "Позднее утро", DemandLevel.MEDIUM, "Перекусы и заказы в офисы к обеду.", BASIS_HEURISTIC),
    RhythmSlot(12, 15, "Обед", DemandLevel.HIGH, "Обеденный перерыв в бизнес-центрах.", BASIS_HEURISTIC),
    RhythmSlot(15, 17, "Затишье", DemandLevel.MEDIUM, "Спад между обедом и ужином.", BASIS_HEURISTIC),
    RhythmSlot(17, 21, "Вечерний пик", DemandLevel.PEAK, "Больше всего заказов за день; максимум 18:00–19:00.", BASIS_CHIBBIS),
    RhythmSlot(21, 23, "Поздний ужин", DemandLevel.MEDIUM, "Спрос идёт на спад.", BASIS_HEURISTIC),
    RhythmSlot(23, 24, "Ночь", DemandLevel.LOW, "Заказов заметно меньше.", BASIS_HEURISTIC)
)

private fun weekendRhythm(isoDow: Int): List<RhythmSlot> {
    val lunchNote = if (isoDow == 7) "Воскресенье: около трети заказов дня приходится на обеденный интервал."
    else "Суббота — самый сильный день недели по числу заказов."
    return listOf(
        RhythmSlot(0, 9, "Ночь и позднее пробуждение", DemandLevel.LOW, "В выходные люди встают позже — ранних заказов мало.", BASIS_CHRONO),
        RhythmSlot(9, 12, "Поздний завтрак", DemandLevel.MEDIUM, "Спрос постепенно растёт: бранчи, кофе, выпечка.", BASIS_CHRONO),
        RhythmSlot(12, 16, "Обед", DemandLevel.HIGH, lunchNote, BASIS_CHIBBIS),
        RhythmSlot(16, 17, "Переход к вечеру", DemandLevel.MEDIUM, "Спрос растёт к ужину.", BASIS_HEURISTIC),
        RhythmSlot(17, 21, "Вечерний пик", DemandLevel.PEAK, "Максимум заказов; пик 18:00–19:00 (в субботу чуть ниже пятницы по доле часа).", BASIS_CHIBBIS),
        RhythmSlot(21, 24, "Поздний ужин", DemandLevel.MEDIUM, "Спрос идёт на спад.", BASIS_HEURISTIC)
    )
}

/** Индекс интервала, в который попадает час. */
fun currentRhythmIndex(slots: List<RhythmSlot>, hour: Int): Int = slots.indexOfFirst { it.contains(hour) }.coerceAtLeast(0)

/** Честная справка по погоде: что учитывается, а что нет. */
fun weatherRhythmNote(weather: Weather): String = when (weather) {
    Weather.RAIN, Weather.STORM, Weather.LIGHT_RAIN, Weather.SLEET, Weather.SNOW ->
        "Осадки обычно повышают спрос на доставку (исследования китайских платформ) — учтено в баллах."
    Weather.CLOUDY ->
        "Влияние пасмурной погоды на заказы в исследованиях не подтверждено — в баллах не учитывается."
    else -> ""
}

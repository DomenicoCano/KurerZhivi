package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

/** Дата/время для тестов: месяц 1..12. */
private fun cal(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Calendar =
    Calendar.getInstance().apply { clear(); set(y, m - 1, d, h, min, 0) }

private fun weather(
    w: Weather = Weather.SUN, temp: Int = 10, wind: Double = 2.0, gusts: Double = 3.0,
    ice: Boolean = false, pressure: Int = 748, delta: Int = 0
) = DetailedWeather(w, temp, temp, 60, wind, gusts, pressureMmHg = pressure, pressureDeltaMmHg = delta, hasIceRisk = ice)

private fun shift(at: Calendar, gross: Int = 3000, hours: Double = 8.0, fuel: Int = 0, repair: Int = 0, other: Int = 0, km: Double = 0.0, orders: Int = 10) =
    ShiftEntry(at.timeInMillis, "x", hours, gross, orders, km, fuel, repair, other)

class CalendarLogicTest {
    @Test fun blackFridayIsLastFridayOfNovemberOnly() {
        // 2026: пятницы — 6, 13, 20, 27 ноября. Чёрная пятница = 27-е
        assertNotNull(checkHolidayFactor(cal(2026, 11, 27)))
        assertNull(checkHolidayFactor(cal(2026, 11, 20)))
        // 23 ноября 2029 — пятница, но не последняя (последняя 30-е)
        assertEquals(Calendar.FRIDAY, cal(2029, 11, 23).get(Calendar.DAY_OF_WEEK))
        assertNull(checkHolidayFactor(cal(2029, 11, 23)))
        assertNotNull(checkHolidayFactor(cal(2029, 11, 30)))
    }

    @Test fun newYearAndMarchEight() {
        assertEquals(30, checkHolidayFactor(cal(2026, 12, 31))!!.impact)
        assertEquals(30, checkHolidayFactor(cal(2026, 3, 8))!!.impact)
        assertNull(checkHolidayFactor(cal(2026, 7, 15)))
    }

    @Test fun paydayMovesToFridayWhenAnchorFallsOnWeekend() {
        // 5 сентября 2026 — суббота → выплата в пятницу 4-го
        assertEquals(Calendar.SATURDAY, cal(2026, 9, 5).get(Calendar.DAY_OF_WEEK))
        val p = checkSmartPayday(cal(2026, 9, 4))
        assertTrue(p.isPayday)
        assertTrue(p.description.contains("перенос"))
    }

    @Test fun regularPaydayAndOrdinaryDay() {
        assertTrue(checkSmartPayday(cal(2026, 11, 10)).isPayday)   // 10-е, вторник
        assertFalse(checkSmartPayday(cal(2026, 11, 17)).isPayday)
    }

    @Test fun hotZoneCoversEveryHour() {
        for (h in 0..23) for (d in listOf(cal(2026, 11, 11, h), cal(2026, 11, 14, h))) {
            val (title, text) = getHotZoneRecommendation(d, null)
            assertTrue("hour=$h", title.isNotBlank() && text.isNotBlank())
        }
    }

    @Test fun quietHours() {
        assertTrue(isQuietHour(23)); assertTrue(isQuietHour(0)); assertTrue(isQuietHour(6))
        assertFalse(isQuietHour(7)); assertFalse(isQuietHour(12)); assertFalse(isQuietHour(22))
    }
}

class PressureTest {
    @Test fun normalRangeIsFavourable() {
        val a = analyzeAtmosphericPressure(748)
        assertFalse(a.isUnfavorableForOrders); assertEquals(4, a.scoreImpact)
    }

    @Test fun extremesAreUnfavourable() {
        assertTrue(analyzeAtmosphericPressure(730).isUnfavorableForOrders)
        assertTrue(analyzeAtmosphericPressure(765).isUnfavorableForOrders)
        assertEquals(-12, analyzeAtmosphericPressure(748, -6).scoreImpact) // барический удар приоритетнее
    }
}

class ScoreTest {
    @Test fun scoreStaysWithinBounds() {
        val worst = calculateScore100(weather(Weather.SUN, 20, pressure = 765), emptyList(), cal(2026, 11, 3, 3))
        val best = calculateScore100(weather(Weather.STORM, -5, wind = 12.0, gusts = 20.0, ice = true), emptyList(), cal(2026, 12, 31, 19))
        assertTrue(worst.totalScore in 0..100)
        assertEquals(100, best.totalScore)
    }

    @Test fun eveningRainBeatsNightClear() {
        val evening = calculateScore100(weather(Weather.RAIN), emptyList(), cal(2026, 11, 13, 19)).totalScore
        val night = calculateScore100(weather(Weather.SUN), emptyList(), cal(2026, 11, 13, 3)).totalScore
        assertTrue(evening > night)
    }

    @Test fun unknownWeatherDoesNotGiveBatteryBonus() {
        val unknown = DetailedWeather(Weather.UNKNOWN, 0, 0, 0, 0.0)
        val r = calculateScore100(unknown, emptyList(), cal(2026, 11, 11, 12), TransportType.EBIKE)
        assertTrue(r.factors.none { it.title.contains("АКБ") })
    }

    @Test fun verdictMatchesScore() {
        val r = calculateScore100(weather(Weather.STORM, -5, ice = true), emptyList(), cal(2026, 12, 31, 19))
        assertTrue(r.verdict.startsWith("Горячее"))
    }

    @Test fun staleNewsAreIgnored() {
        val fresh = NewsItem(1, "ДТП", "s", "", isExtreme = true, isFresh = true)
        val old = NewsItem(2, "ДТП", "s", "", isExtreme = true, isFresh = false)
        val c = cal(2026, 11, 11, 12)
        val base = calculateScore100(weather(), emptyList(), c).totalScore
        assertEquals(base, calculateScore100(weather(), listOf(old), c).totalScore)
        assertTrue(calculateScore100(weather(), listOf(fresh), c).totalScore > base)
    }
}

class BatteryTest {
    @Test fun lossNeverDecreasesAsItGetsColder() {
        var prev = -1
        for (t in 25 downTo -30) {
            val loss = calculateBatteryDropPercent(weather(temp = t, wind = 0.0, gusts = 0.0))
            assertTrue("t=$t loss=$loss prev=$prev", loss >= prev)
            prev = loss
        }
    }

    @Test fun warmWeatherNoLossAndCapApplied() {
        assertEquals(0, calculateBatteryDropPercent(weather(temp = 25, wind = 0.0, gusts = 0.0)))
        assertTrue(calculateBatteryDropPercent(weather(Weather.SNOW, -30, wind = 20.0, gusts = 30.0, ice = true)) <= 65)
    }
}

class DarknessTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(h: Int, m: Int = 0) = GregorianCalendar(utc).apply { clear(); set(2026, Calendar.NOVEMBER, 11, h, m, 0) }.timeInMillis

    @Test fun daytimeShowsTimeToSunset() {
        val d = buildDarknessInfo("2026-11-11T07:00", "2026-11-11T17:00", utc, at(12))
        assertTrue(d.bannerTitle.contains("Темнеет в 17:00"))
        assertTrue(d.bannerTitle.contains("5 ч."))
    }

    @Test fun beforeSunriseAndAfterSunset() {
        assertTrue(buildDarknessInfo("2026-11-11T07:00", "2026-11-11T17:00", utc, at(3)).bannerTitle.contains("Рассвет в 07:00"))
        assertTrue(buildDarknessInfo("2026-11-11T07:00", "2026-11-11T17:00", utc, at(20)).bannerTitle.contains("закат был в 17:00"))
    }

    @Test fun cityTimezoneIsRespected() {
        // 12:00 UTC в Екатеринбурге (UTC+5) = 17:00 → после захода в 17:00 по местному времени
        val ekb = TimeZone.getTimeZone("GMT+5")
        assertTrue(buildDarknessInfo("2026-11-11T07:00", "2026-11-11T17:00", ekb, at(12, 30)).bannerTitle.contains("закат был"))
    }

    @Test fun missingDataHidesBannerInsteadOfInventingTimes() {
        val d = buildDarknessInfo("", "", utc, at(12))
        assertEquals("", d.bannerTitle)
    }
}

class RssDateTest {
    @Test fun blankIsFreshToday() = assertEquals(true to "Сегодня", parseRssDate(""))

    @Test fun oldDateIsNotFresh() {
        val (fresh, text) = parseRssDate("Mon, 01 Jan 2024 10:00:00 GMT")
        assertFalse(fresh); assertTrue(text.contains("дн. назад"))
    }
}

class AnalyticsTest {
    @Test fun shiftsOfMonthFiltersByCalendarMonth() {
        val now = cal(2026, 11, 11)
        val list = listOf(shift(cal(2026, 11, 2)), shift(cal(2026, 10, 30)), shift(cal(2025, 11, 5)))
        assertEquals(1, shiftsOfMonth(list, now).size)
    }

    @Test fun netSubtractsAllExpenseCategories() {
        val s = shift(cal(2026, 11, 1), gross = 3000, fuel = 200, repair = 300, other = 100, hours = 8.0)
        assertEquals(600, s.expenses); assertEquals(2400, s.net); assertEquals(300, s.perHour)
    }

    @Test fun netAllowsDeficitSoTotalsNeverLoseExpenses() {
        assertEquals(-400, shift(cal(2026, 11, 1), gross = 100, other = 500).net)
        val s1 = shift(cal(2026, 11, 1), gross = 5000, hours = 8.0)
        val s2 = shift(cal(2026, 11, 2), gross = 1000, repair = 2500, hours = 4.0)
        val expenseOnly = ShiftEntry(cal(2026, 11, 3).timeInMillis, "03.11 (Вт)", 0.0, 0, 0, expRepair = 1500)
        assertTrue(expenseOnly.isExpenseOnly)
        val t = totalsOf(listOf(s1, s2, expenseOnly))
        assertEquals(2, t.shifts)
        assertEquals(2000, t.net)
    }

    @Test fun dailyNetCoversRequestedDaysOldestFirst() {
        val now = cal(2026, 11, 11, 15)
        val list = listOf(shift(cal(2026, 11, 11, 9), gross = 1000), shift(cal(2026, 11, 10, 20), gross = 2000), shift(cal(2026, 11, 10, 8), gross = 500))
        val days = dailyNet(list, 7, now)
        assertEquals(7, days.size)
        assertEquals(1000, days.last().net)
        assertEquals(2500, days[days.size - 2].net)
        assertEquals(0, days.first().net)
    }

    @Test fun weekdayStatsRanksBestDayFirst() {
        // 14.11.2026 — суббота, 9.11.2026 — понедельник
        val list = listOf(shift(cal(2026, 11, 14), gross = 4000, hours = 8.0), shift(cal(2026, 11, 9), gross = 1600, hours = 8.0))
        val st = weekdayStats(list)
        assertEquals(2, st.size)
        assertEquals("Суббота", st[0].title); assertEquals(500, st[0].avgPerHour)
        assertEquals("Понедельник", st[1].title)
    }

    @Test fun weekComparison() {
        val now = cal(2026, 11, 11)
        val list = listOf(shift(cal(2026, 11, 10), gross = 1000), shift(cal(2026, 11, 2), gross = 700), shift(cal(2026, 10, 1), gross = 9999))
        val (cur, prev) = weekVsPrevious(list, now)
        assertEquals(1000, cur.net); assertEquals(700, prev.net)
    }

    @Test fun neededPerDayCountsTodayAndFinishesAtZero() {
        assertEquals(1500, neededPerDay(30000, 0, cal(2026, 11, 11)))   // 20 дней: с 11-го по 30-е
        assertEquals(0, neededPerDay(30000, 30000, cal(2026, 11, 11)))
    }

    @Test fun totalsAndPerKm() {
        val t = totalsOf(listOf(shift(cal(2026, 11, 1), gross = 2000, fuel = 200, km = 40.0), shift(cal(2026, 11, 2), gross = 2000, fuel = 200, km = 60.0)))
        assertEquals(3600, t.net); assertEquals(100.0, t.km, 0.01); assertEquals(36.0, t.perKm, 0.01); assertEquals(4.0, t.costPerKm, 0.01)
    }
}

class BackupTest {
    private val sample = """
        {"version":2,"region_id":"msk","region_name":"Москва","region_lat":55.75,"region_lon":37.61,
         "transport":"BIKE","theme":"LIGHT","monthly_goal":50000,
         "shifts":[{"id":1700000000000,"dateStr":"01.11","hours":8.0,"gross":3000,"orders":12,"km":40.5,"expFuel":100,"expRepair":0,"expOther":50}],
         "intercoms":[{"id":1,"address":"Ленина 1","code":"#1234","comment":"","category":"Домофон / ЖК"}]}
    """.trimIndent()

    @Test fun parsesNewFormat() {
        val d = Backup.parse(sample)
        assertEquals(TransportType.BIKE, d.transport); assertEquals(AppThemeMode.LIGHT, d.theme); assertEquals(50000, d.monthlyGoal)
        assertEquals(1, d.shifts!!.size); assertEquals(40.5, d.shifts!![0].km, 0.001)
        assertEquals("#1234", d.intercoms!![0].code)
        assertNull(d.yandexKey)
    }

    @Test fun parsesLegacyFormatWithArraysAsStrings() {
        val legacy = """{"region_id":"spb","region_name":"СПб","region_lat":59.9,"region_lon":30.3,
            "shifts_json":"[{\"id\":5,\"dateStr\":\"01.01\",\"hours\":6.0,\"gross\":2000,\"expenses\":300,\"orders\":9}]",
            "intercoms_json":"[]","yandex_key":"abc"}"""
        val d = Backup.parse(legacy)
        assertEquals(300, d.shifts!![0].expOther) // старая единая сумма расходов → «прочее»
        assertEquals("abc", d.yandexKey)
    }

    private fun assertRejected(json: String) {
        try { Backup.parse(json); fail("должен быть отклонён: $json") } catch (_: Exception) {}
    }

    @Test fun rejectsGarbageWithoutTouchingAnything() {
        assertRejected("не json")
        assertRejected("{}")
        assertRejected("""{"shifts":[{"id":1}]}""")                       // нет обязательных полей
        assertRejected("""{"transport":"SPACESHIP","shifts":[]}""")       // неизвестный транспорт
        assertRejected("""{"region_id":"x","region_lat":999,"region_lon":1}""") // координаты вне диапазона
    }
}

class SecurityTest {
    @Test fun devPasswordHashFormatAndSha256() {
        assertEquals(64, DEV_PASSWORD_SHA256.length)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        assertFalse(sha256Hex("ABC") == sha256Hex("abc"))
    }
}

class RhythmTest {
    @Test fun everyHourBelongsToExactlyOneSlotOnAnyDay() {
        for (day in 9..15) for (h in 0..23) {
            val slots = dailyRhythm(cal(2026, 11, day, h))
            assertEquals("day=$day hour=$h", 1, slots.count { it.contains(h) })
        }
    }

    @Test fun weekendMorningStartsLaterThanWeekday() {
        fun firstNonLow(c: Calendar) = dailyRhythm(c).first { it.level != DemandLevel.LOW && it.startHour > 0 }.startHour
        assertTrue(firstNonLow(cal(2026, 11, 14)) > firstNonLow(cal(2026, 11, 11))) // суббота позже среды
    }

    @Test fun eveningIsPeakEveryDay() {
        for (day in 9..15) assertEquals(DemandLevel.PEAK, dailyRhythm(cal(2026, 11, day, 18)).first { it.contains(18) }.level)
    }

    @Test fun everySlotHasABasis() {
        assertTrue(dailyRhythm(cal(2026, 11, 14)).plus(dailyRhythm(cal(2026, 11, 11))).all { it.basis.isNotBlank() })
    }

    @Test fun cloudsAddNoScoreFactor() {
        val c = cal(2026, 11, 11, 9)
        val cloudy = calculateScore100(weather(Weather.CLOUDY), emptyList(), c)
        assertTrue(cloudy.factors.none { it.title.contains("сон", ignoreCase = true) })
    }

    @Test fun weekendEarlyMorningScoresLowerThanWeekdayMorning() {
        val weekday = calculateScore100(weather(), emptyList(), cal(2026, 11, 11, 8)).totalScore  // среда
        val weekend = calculateScore100(weather(), emptyList(), cal(2026, 11, 15, 8)).totalScore  // воскресенье
        // у воскресенья выше «дневной» бонус, поэтому сравниваем именно утренний фактор
        val wf = calculateScore100(weather(), emptyList(), cal(2026, 11, 15, 8)).factors.first { it.title.startsWith("Выходное утро") }
        assertEquals(0, wf.impact)
        assertTrue(weekday > 0 && weekend > 0)
    }
}

class PersonalTest {
    private fun s(end: Calendar, gross: Int, hours: Double = 4.0, w: String = "") =
        ShiftEntry(end.timeInMillis, "x", hours, gross, 5, weather = w)

    private val evening = listOf(9, 10, 11).map { s(cal(2026, 11, it, 20), 2000, w = "RAIN") }   // середина 18:00, будни
    private val morning = listOf(12, 13, 16).map { s(cal(2026, 11, it, 10), 1000, w = "SUN") }   // середина 08:00, будни

    @Test fun tooFewShiftsGivesNoPersonalConclusions() {
        val p = personalProfile(evening + morning.take(1)) // 4 смены
        assertFalse(p.enough); assertEquals(1, p.shiftsToGo); assertTrue(p.byDayPart.isEmpty())
        assertNull(personalHintNow(evening + morning.take(1), cal(2026, 11, 18, 19)))
    }

    @Test fun profileRanksDayPartsAndComparesToOwnAverage() {
        val p = personalProfile(evening + morning)
        assertTrue(p.enough); assertEquals(375, p.overallPerHour)
        assertEquals("Вечер 17–23", p.byDayPart[0].label); assertEquals(500, p.byDayPart[0].perHour); assertEquals(33, p.byDayPart[0].deltaPct)
        assertEquals("Утро 05–11", p.byDayPart[1].label); assertEquals(-33, p.byDayPart[1].deltaPct)
    }

    @Test fun weatherGroupsUseSavedWeather() {
        val p = personalProfile(evening + morning)
        assertEquals(setOf("Осадки", "Ясно"), p.byWeather.map { it.label }.toSet())
    }

    @Test fun groupsBelowThresholdAreHidden() {
        val p = personalProfile(evening + morning.take(2) + s(cal(2026, 11, 14, 20), 4000, w = "SNOW")) // 6 смен; утро=2, выходные=1
        assertTrue(p.byDayPart.none { it.label.startsWith("Утро") })
        assertTrue(p.byDayType.none { it.label == "Выходные" })
    }

    @Test fun hintForMatchingMomentOnly() {
        val hint = personalHintNow(evening + morning, cal(2026, 11, 18, 19))
        assertNotNull(hint); assertTrue(hint!!.contains("500"))
        assertNull(personalHintNow(evening + morning, cal(2026, 11, 21, 19))) // суббота — выходных смен нет
    }

    @Test fun unknownWeatherIsNotClassified() {
        assertNull(weatherClassOf("")); assertNull(weatherClassOf("UNKNOWN"))
        assertEquals(WeatherClass.PRECIP, weatherClassOf("SNOW"))
    }
}

class NewFeaturesTest {
    @Test fun taxIsSubtractedFromNet() {
        assertEquals(0, taxFor(1000, 0))
        assertEquals(40, taxFor(1000, 4))
        assertEquals(60, taxFor(1000, 6))
        val s = ShiftEntry(1L, "x", 5.0, 3000, 10, 0.0, 200, 0, 100, "", tips = 300, tax = 120)
        assertEquals(3000 - 300 - 120, s.net)
    }

    @Test fun monthForecastScalesByDaysElapsed() {
        // 10 ноября (30 дней): 10 000 за 10 дней → 30 000 за месяц
        assertEquals(30000, monthForecast(10000, cal(2026, 11, 10)))
        assertEquals(31000, monthForecast(1000, cal(2026, 12, 1)))
    }

    @Test fun serviceStatsComparesPerHourAndSkipsEmptyService() {
        val now = cal(2026, 11, 10)
        val list = listOf(
            ShiftEntry(now.timeInMillis, "x", 5.0, 5000, 10, service = "Самокат", tips = 500),
            ShiftEntry(now.timeInMillis + 1, "x", 5.0, 3000, 10, service = "Яндекс Еда"),
            ShiftEntry(now.timeInMillis + 2, "x", 5.0, 9000, 10, service = "")
        )
        val st = serviceStats(list)
        assertEquals(listOf("Самокат", "Яндекс Еда"), st.map { it.service })
        assertEquals(1000, st[0].perHour)
        assertEquals(10, st[0].tipsShare)
        assertEquals(500, st[0].avgCheck)
    }

    @Test fun elapsedHoursRounding() {
        assertEquals(0.0, elapsedHours(0L, 1_000_000L), 0.0)
        assertEquals(1.5, elapsedHours(1_000L, 1_000L + 90 * 60_000L), 0.0)
        assertEquals(0.1, elapsedHours(1_000L, 1_000L + 60_000L), 0.0)
    }

    @Test fun csvHasBomHeaderAndRussianDecimals() {
        val csv = shiftsToCsv(listOf(ShiftEntry(cal(2026, 11, 10, 20).timeInMillis, "x", 7.5, 4000, 12, 35.5, service = "Купер; ночь")))
        assertTrue(csv.startsWith("\uFEFFДата;"))
        val row = csv.lines()[1]
        assertTrue(row, row.startsWith("10.11.2026 20:00;\"Купер; ночь\";7,5;12;4000;"))
        assertTrue(row.endsWith(";35,5"))
    }

    @Test fun cityStemForNewsSearch() {
        assertEquals("москв", cityStem("Москва"))
        assertEquals("петербург", cityStem("Санкт-Петербург"))
        assertEquals("казан", cityStem("Казань"))
        assertEquals("рязан", cityStem("Рязань (Рязанская область)"))
        assertEquals("сочи", cityStem("Сочи"))
    }

    @Test fun isoTimeParsingWithOffsetAndFraction() {
        val utc = parseIsoMs("2022-10-26T09:37:53Z")!!
        assertEquals(utc, parseIsoMs("2022-10-26T12:37:53.528758212+03:00"))
        assertEquals(utc - 53_000, parseIsoMs("2022-10-26T12:37+03:00"))
        assertNull(parseIsoMs("вчера"))
    }

    @Test fun yandexConditionsAndWindChill() {
        assertEquals(Weather.SUN, decodeYandexCondition("CLEAR", 5))
        assertEquals(Weather.STORM, decodeYandexCondition("THUNDERSTORM_WITH_RAIN", 15))
        assertEquals(Weather.SNOW, decodeYandexCondition("light-snow", -3)) // старый формат informers
        assertEquals(Weather.FROST, decodeYandexCondition("CLEAR", -25))
        assertEquals(15, windChill(15, 10.0))
        assertTrue(windChill(-10, 8.0) < -10)
    }

    @Test fun shiftsJsonReadsNewFieldsAndOldFormat() {
        val list = parseShiftsJson("""[{"id":1,"dateStr":"a","hours":5,"gross":3000,"tips":200,"tax":120,"service":"Самокат"},
            {"id":2,"dateStr":"b","hours":4,"gross":2000,"expenses":300}]""")
        assertEquals(200, list[0].tips); assertEquals(120, list[0].tax); assertEquals("Самокат", list[0].service)
        assertEquals(0, list[1].tips); assertEquals("", list[1].service); assertEquals(300, list[1].expOther)
    }
}

class RuWeatherParsingTest {
    private val html = "<h2><time dateTime=\"2026-11-16T19:14:32.271Z\">Рязань, Россия — текущее локальное время 22:14</time></h2>" +
        "<p><span class=\"AppFactTemperature_sign__x\">−</span><span class=\"AppFactTemperature_value__y\">3</span>°</p>" +
        "<span>Ощущается как −8°</span><span class=\"FactWithoutCharacter_warning__first_text__z\" aria-hidden=\"true\">Небольшой снег</span>" +
        "<li>4 м/с, СЗ</li><li>745</li><li>91%</li>" +
        "<p>22:00: -3°, небольшой снег, Ощущается как -8°.</p><p>23:00: -4°, облачно с прояснениями, Ощущается как -9°.</p>" +
        "<p>вторник, 00:00: -4°, ясно, Ощущается как -9°.</p><p>Восход 17 ноября 07:54</p><p>Закат 17 ноября 16:17</p>"

    @Test fun parsesCurrentAndHourly() {
        val y = parseYandexPogodaHtml(html)!!
        assertEquals(-3, y.current.temp)
        assertEquals(-8, y.current.feelsLike)
        assertEquals(Weather.SNOW, y.current.weather)
        assertEquals(4.0, y.current.windSpeed, 0.0)
        assertEquals(745, y.current.pressureMmHg)
        assertEquals(91, y.current.humidity)
        assertEquals(3 * 3600, y.offsetSec)
        assertEquals(3, y.hours.size)
        // 00:00 — уже следующие сутки, ровно через час после 23:00
        assertEquals(3_600_000L, y.hours[2].ms - y.hours[1].ms)
        assertEquals(parseIsoMs("2026-11-16T22:00+03:00"), y.hours[0].ms)
        assertEquals(parseIsoMs("2026-11-17T07:54+03:00"), y.sunriseMs)
        assertFalse(y.current.isDay)
    }

    @Test fun garbagePageGivesNull() {
        assertNull(parseYandexPogodaHtml("<html>Подтвердите, что вы не робот</html>"))
    }

    @Test fun ignoresRepeatedHourlyBlocksAndScriptNoiseAndParsesDailyForecast() {
        val singleBlock = (0..24).joinToString("") { idx ->
            val h = (22 + idx) % 24
            val hh = "%02d".format(h)
            "<p>$hh:00: -2°, пасмурно, Ощущается как -7°.</p>"
        }
        val fakeScript = "<script>window.__DATA__ = '23:00: +99°, ясно, Ощущается как +99°.';</script>"
        val daysHtml = """
            <div>Завтра, 19 ноября:</div><div>утром температура воздуха +1°, ощущается как −2°, пасмурно,</div><div>днём +4°, ощущается как 0°, небольшой дождь,</div><div>ночью +2°, ощущается как −1°,</div>
            <div>Пятница, 20 ноября:</div><div>утром температура воздуха 0°, ощущается как −4°, ясно,</div><div>днём +3°, ощущается как 0°, ясно,</div>
            <div>Суббота, 21 ноября:</div><div>утром температура воздуха −3°, ощущается как −8°, снег,</div><div>днём −1°, ощущается как −6°, снег,</div>
        """.trimIndent()
        val fullHtml = fakeScript + html.substringBefore("<p>22:00:") + singleBlock + singleBlock + singleBlock + daysHtml +
            "<p>Восход 17 ноября 07:54</p><p>Закат 17 ноября 16:17</p>"
        val y = parseYandexPogodaHtml(fullHtml)!!
        assertTrue("Expected <= 25 hours, got ${y.hours.size}", y.hours.size <= 25)
        assertEquals(3, y.dailyForecast.size)
        assertEquals("19 ноября (завтра)", y.dailyForecast[0].dateStr)
        assertEquals(1, y.dailyForecast[0].minTemp)
        assertEquals(4, y.dailyForecast[0].maxTemp)
        assertEquals(Weather.LIGHT_RAIN, y.dailyForecast[0].weather)
        val wd = y.toWeatherData(TransportType.EBIKE, parseIsoMs("2026-11-16T22:14+03:00")!!)
        assertEquals(3, wd.forecast.size)
        assertEquals("19 ноября (завтра)", wd.forecast[0].dateStr)
    }

    @Test fun ruTextAndRamblerIcons() {
        assertEquals(Weather.SUN, weatherFromRuText("Малооблачно", 5))
        assertEquals(Weather.CLOUDY, weatherFromRuText("облачно с прояснениями", 5))
        assertEquals(Weather.STORM, weatherFromRuText("гроза", 20))
        assertEquals(Weather.SLEET, weatherFromRuText("дождь со снегом", 1))
        assertEquals(Weather.LIGHT_RAIN, weatherFromRuText("небольшой дождь", 8))
        assertEquals(Weather.SNOW, weatherFromRamblerIcon("occ-snow", -5))
        assertEquals(Weather.SUN, weatherFromRamblerIcon("clear-night", 0))
    }
}

class RuCitiesTest {
    private val tsv = "# заголовок\n" +
        "7700000000000\tМосква\t\t55.75\t37.62\t11514330\n" +
        "7100000100000\tТула\tТульская область\t54.19\t37.61\t501129\n" +
        "3900001000000\tСоветск\tКалининградская область\t55.08\t21.88\t41705\n" +
        "4300000000000\tСоветск\tКировская область\t57.58\t48.96\t16528\n" +
        "6300000100000\tТольятти\tСамарская область\t53.51\t49.42\t719632\n" +
        "1200000000000\tЙошкар-Ола\tРеспублика Марий Эл\t56.63\t47.89\t248688\n"
    private val cities = parseRuCities(tsv)

    @Test fun parsesAndBuildsRegion() {
        assertEquals(6, cities.size)
        assertEquals(Region("ru_7100000100000", "Тула (Тульская область)", 54.19, 37.61), cities[1].toRegion())
        assertEquals("Москва", cities[0].toRegion().name)
    }

    @Test fun prefixFirstThenPopulation() {
        assertTrue(searchRuCities(cities, "т").isEmpty()) // слишком короткий запрос
        assertEquals(listOf("Тольятти", "Тула"), searchRuCities(cities, "то").map { it.name } + searchRuCities(cities, "ту").map { it.name })
        assertEquals(listOf("Тольятти"), searchRuCities(cities, "тол").map { it.name })
        // одноимённые города различаются регионом, крупный — первым
        assertEquals(listOf("Калининградская область", "Кировская область"), searchRuCities(cities, "Советск").map { it.region })
        // ё/е и регистр не важны, поиск по началу региона
        assertEquals("Йошкар-Ола", searchRuCities(cities, "марий").single().name)
        assertEquals("Тула", searchRuCities(cities, "ТУЛ").first().name)
        assertEquals("Москва", searchRuCities(cities, "мск").first().name)
    }
}

class CityNewsMatchTest {
    @Test fun avoidsFalsePositivesAndMatchesDeclensions() {
        assertTrue(matchesCityInHeadline("Пробки в Ростове-на-Дону из-за снегопада", "Ростов-на-Дону"))
        assertFalse(matchesCityInHeadline("Дональд Трамп выступил в Лондоне", "Ростов-на-Дону"))

        assertTrue(matchesCityInHeadline("В Йошкар-Оле перекрыли центр", "Йошкар-Ола"))
        assertFalse(matchesCityInHeadline("Новая школа открылась в области", "Йошкар-Ола"))

        assertTrue(matchesCityInHeadline("В Чите выпал первый снег", "Чита"))
        assertFalse(matchesCityInHeadline("Эксперт считает, что тарифы изменятся", "Чита"))
        assertFalse(matchesCityInHeadline("Он прочитал новость и пошел читать книгу", "Чита"))

        assertTrue(matchesCityInHeadline("В Омске и Омской области шторм", "Омск"))
        assertFalse(matchesCityInHeadline("Сбой в работе транспорта в Томске", "Омск"))

        assertTrue(matchesCityInHeadline("В Уфе перекрыли проспект", "Уфа"))
        assertFalse(matchesCityInHeadline("Триумфа спортсменов ждали болельщики", "Уфа"))

        assertTrue(matchesCityInHeadline("В Орле открыли новую развязку", "Орёл"))
        assertTrue(matchesCityInHeadline("В Нижнем Новгороде сильный ливень", "Нижний Новгород"))
        assertTrue(matchesCityInHeadline("В Петербурге начался снегопад", "Санкт-Петербург"))
        assertTrue(matchesCityInHeadline("На дорогах СПб пробки 9 баллов", "Санкт-Петербург"))
    }

    @Test fun parsesAllRssDateFormats() {
        val gmt = parseRssDateMs("Mon, 01 Jan 2024 10:00:00 GMT")
        val offset = parseRssDateMs("Mon, 01 Jan 2024 13:00:00 +0300")
        val iso = parseRssDateMs("2024-01-01T10:00:00Z")
        assertNotNull(gmt)
        assertEquals(gmt, offset)
        assertEquals(gmt, iso)
        assertNull(parseRssDateMs("некорректная дата"))
    }
}

class IntercomSearchTest {
    @Test fun fuzzyMatchesAddressesStopWordsAndYo() {
        val n1 = IntercomNote(1L, "ул. Ленина, д. 15, корп. 2", "#1234", "Вход со двора")
        val n2 = IntercomNote(2L, "Берёзовая аллея 15к2", "4455", "ЖК Сосны")
        assertTrue(matchesIntercomQuery(n1, "ленина 15 2"))
        assertTrue(matchesIntercomQuery(n1, "1234"))
        assertTrue(matchesIntercomQuery(n2, "березовая 15 к 2"))
        assertTrue(matchesIntercomQuery(n2, "жк"))
        assertFalse(matchesIntercomQuery(n1, "пушкина 15"))
    }
}

class ShiftPauseAndQuickInputTest {
    @Test fun activeElapsedSubtractsCompletedAndCurrentPause() {
        val start = 1_000_000L
        // 2 часа с начала, 30 мин прошлой паузы => 1.5 ч
        assertEquals(1.5, activeElapsedHours(start, pausedAtMs = 0L, pausedTotalMs = 30 * 60_000L, nowMs = start + 120 * 60_000L), 0.0)
        // сейчас на паузе, поставлено через 90 мин, до этого пауз 30 мин => 60 мин = 1.0 ч даже когда now ушёл далеко вперёд
        assertEquals(1.0, activeElapsedHours(start, pausedAtMs = start + 90 * 60_000L, pausedTotalMs = 30 * 60_000L, nowMs = start + 300 * 60_000L), 0.0)
    }

    @Test fun parsesQuickOrderFormats() {
        assertEquals(250 to 0, parseQuickOrderInput("250"))
        assertEquals(320 to 50, parseQuickOrderInput("320+50"))
        assertEquals(320 to 50, parseQuickOrderInput("320 50"))
        assertEquals(0 to 100, parseQuickOrderInput("+100"))
        assertNull(parseQuickOrderInput("0"))
        assertNull(parseQuickOrderInput("abc"))
        assertNull(parseQuickOrderInput(""))
    }

    @Test fun smartForecastHidesSingleFirstDayOutlier() {
        assertNull(smartMonthForecast(6000, monthShiftCount = 1, now = cal(2026, 11, 1)))
        assertEquals(180000, smartMonthForecast(6000, monthShiftCount = 2, now = cal(2026, 11, 1)))
        assertEquals(60000, smartMonthForecast(6000, monthShiftCount = 1, now = cal(2026, 11, 3)))
    }
}

class PersonalEstimateTest {
    @Test fun providesPreliminaryAndCalibratedEstimate() {
        assertNull(estimatePersonalPerHour(emptyList(), weather(), 60, cal(2026, 11, 11, 18)))
        val two = listOf(
            ShiftEntry(cal(2026, 11, 9, 20).timeInMillis, "x", 4.0, 2000, 8),
            ShiftEntry(cal(2026, 11, 10, 20).timeInMillis, "x", 4.0, 2000, 8)
        )
        val prelim = estimatePersonalPerHour(two, weather(), 75, cal(2026, 11, 11, 18))!!
        assertFalse(prelim.isCalibrated)
        assertTrue(prelim.estimatedPerHour > 500)

        val five = listOf(11, 12, 13, 16, 17).map {
            ShiftEntry(cal(2026, 11, it, 20).timeInMillis, "x", 4.0, 2400, 10, weather = "RAIN")
        }
        val calibrated = estimatePersonalPerHour(five, weather(Weather.RAIN), 80, cal(2026, 11, 18, 19))!!
        assertTrue(calibrated.isCalibrated)
        assertTrue(calibrated.estimatedPerHour >= 580)
    }
}

class BackupV4Test {
    @Test fun exportsAndParsesV4FieldsAndPinnedIntercoms() {
        val json = """
            {"version":4,"region_id":"msk","region_name":"Москва","region_lat":55.75,"region_lon":37.61,
             "transport":"EBIKE","theme":"DARK_AMOLED","monthly_goal":90000,
             "live_orders":3,"live_gross":1200,"live_tips":150,"shift_start_ms":1700000000000,
             "shift_paused_at_ms":1700001000000,"shift_paused_total_ms":600000,"last_order_price":350,
             "notif_enabled":true,"notif_ext_enabled":true,"quiet_enabled":false,"notif_hour":9,"notif_minute":30,"notif_tone":"MAT",
             "shifts":[],
             "intercoms":[{"id":1,"address":"Ленина 1","code":"#1234","comment":"","category":"Домофон / ЖК","pinned":true}]}
        """.trimIndent()
        val parsed = Backup.parse(json)
        assertEquals(150, parsed.liveTips)
        assertEquals(1700000000000L, parsed.shiftStartMs)
        assertEquals(1700001000000L, parsed.shiftPausedAtMs)
        assertEquals(600000L, parsed.shiftPausedTotalMs)
        assertEquals(350, parsed.lastOrderPrice)
        assertEquals(true, parsed.notifEnabled)
        assertEquals(false, parsed.quietHours)
        assertEquals(9, parsed.notifHour)
        assertEquals(30, parsed.notifMinute)
        assertEquals(NotificationTone.MAT, parsed.notifTone)
        assertTrue(parsed.intercoms!!.single().pinned)
    }
}

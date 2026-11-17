package com.example.myapplication

import java.util.Calendar
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import kotlin.math.abs

// ============================================================================
// АНАЛИТИКА АТМОСФЕРНОГО ДАВЛЕНИЯ И САМОЧУВСТВИЯ КЛИЕНТОВ
// ============================================================================

fun analyzeAtmosphericPressure(pressureMmHg: Int, deltaMmHg: Int = 0): PressureAnalysis {
    val absDelta = abs(deltaMmHg)
    return when {
        absDelta >= 5 -> PressureAnalysis(
            pressureMmHg = pressureMmHg,
            deltaMmHg = deltaMmHg,
            statusTitle = "Барический удар (перепад ${if (deltaMmHg > 0) "+$deltaMmHg" else "$deltaMmHg"} мм)",
            wellBeingVerdict = "Резкий скачок давления! У метеозависимых людей начинаются мигрени, слабость и падает аппетит — многим сейчас не до курьеров и плотной еды. Спрос на рестораны и продукты проседает, но чаще заказывают из аптек.",
            scoreImpact = -12,
            isUnfavorableForOrders = true
        )
        pressureMmHg <= 735 -> PressureAnalysis(
            pressureMmHg = pressureMmHg,
            deltaMmHg = deltaMmHg,
            statusTitle = "Сильная барическая депрессия ($pressureMmHg мм рт. ст.)",
            wellBeingVerdict = "Очень низкое давление: у гипотоников и метеочувствительных людей сонливость, упадок сил и тошнота. Желание заказывать еду и встречать курьеров снижено.",
            scoreImpact = -10,
            isUnfavorableForOrders = true
        )
        pressureMmHg >= 760 -> PressureAnalysis(
            pressureMmHg = pressureMmHg,
            deltaMmHg = deltaMmHg,
            statusTitle = "Сильный антициклон ($pressureMmHg мм рт. ст.)",
            wellBeingVerdict = "Аномально высокое давление: головные боли и скачки АД у клиентов. Аппетит снижается, люди реже делают крупные заказы продуктов и фастфуда.",
            scoreImpact = -10,
            isUnfavorableForOrders = true
        )
        pressureMmHg in 736..741 -> PressureAnalysis(
            pressureMmHg = pressureMmHg,
            deltaMmHg = deltaMmHg,
            statusTitle = "Пониженное давление ($pressureMmHg мм рт. ст.)",
            wellBeingVerdict = "Небольшая вялость у метеозависимых людей. Возможен легкий спад импульсивных дневных заказов, но растет спрос на кофе и сладкое.",
            scoreImpact = -5,
            isUnfavorableForOrders = true
        )
        pressureMmHg in 755..759 -> PressureAnalysis(
            pressureMmHg = pressureMmHg,
            deltaMmHg = deltaMmHg,
            statusTitle = "Повышенное давление ($pressureMmHg мм рт. ст.)",
            wellBeingVerdict = "Давление выше нормы: у части клиентов возможна усталость к вечеру и снижение аппетита, заказы из ресторанов могут быть чуть скромнее.",
            scoreImpact = -5,
            isUnfavorableForOrders = true
        )
        else -> PressureAnalysis(
            pressureMmHg = pressureMmHg,
            deltaMmHg = deltaMmHg,
            statusTitle = "Комфортная норма ($pressureMmHg мм рт. ст.)",
            wellBeingVerdict = "Оптимальное давление (742–754 мм рт. ст.). Самочувствие и аппетит у людей в полном порядке — метеофактор не мешает заказам.",
            scoreImpact = 4,
            isUnfavorableForOrders = false
        )
    }
}

// ============================================================================
// УМНЫЙ КАЛЕНДАРЬ (ТК РФ, ПРАЗДНИКИ, ГОРЯЧИЕ ЗОНЫ)
// ============================================================================

data class PaydayInfo(val isPayday: Boolean, val description: String)
data class HolidayInfo(val impact: Int, val description: String)

fun checkSmartPayday(cal: Calendar): PaydayInfo {
    val currentDay = cal.get(Calendar.DAY_OF_MONTH)
    for (anchor in listOf(5, 10, 15, 20, 25)) {
        val anchorCal = (cal.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, anchor) }
        val actualPayday = when (anchorCal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SATURDAY -> anchor - 1
            Calendar.SUNDAY -> anchor - 2
            else -> anchor
        }
        if (currentDay == actualPayday) {
            return if (actualPayday != anchor) PaydayInfo(true, "День ЗП/аванса (перенос с $anchor-го на пятницу по ТК РФ)!")
            else PaydayInfo(true, "День выплаты ЗП и авансов ($anchor-е число)! У людей есть деньги.")
        }
        if (currentDay == actualPayday + 1) return PaydayInfo(true, "Второй день после выплаты ЗП — высокий спрос.")
    }
    return PaydayInfo(false, "")
}

fun checkHolidayFactor(cal: Calendar): HolidayInfo? {
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    return when {
        m == 12 && d in 25..30 -> HolidayInfo(25, "Предновогодний ажиотаж и корпоративы! Спрос на пике.")
        m == 12 && d == 31 -> HolidayInfo(30, "31 Декабря! Огромный спрос и максимальные бонусы.")
        m == 1 && d == 1 -> HolidayInfo(25, "1 Января! Дефицит курьеров на линии — горящие кэфы.")
        m == 1 && d in 2..8 -> HolidayInfo(15, "Новогодние каникулы. Люди дома, стабильный спрос.")
        m == 2 && d == 14 -> HolidayInfo(25, "14 Февраля! Пик заказов из ресторанов и цветочных.")
        m == 2 && d in 22..23 -> HolidayInfo(20, "Праздники 23 Февраля! Высокий спрос.")
        m == 3 && d in 7..8 -> HolidayInfo(30, "8 Марта! Абсолютный рекорд спроса на доставку.")
        m == 5 && d in 1..3 -> HolidayInfo(10, "Майские праздники. Спрос в парках и спальных районах.")
        m == 5 && d == 9 -> HolidayInfo(15, "9 Мая. Высокий вечерний спрос и перекрытия улиц.")
        m == 6 && d == 12 -> HolidayInfo(15, "День России (Праздничный выходной).")
        m == 9 && d == 1 -> HolidayInfo(20, "1 Сентября. Много семейных заказов.")
        m == 10 && d == 31 -> HolidayInfo(15, "Хэллоуин. Вечерний всплеск заказов.")
        m == 11 && d == 4 -> HolidayInfo(15, "День народного единства (Выходной).")
        m == 11 && d == 11 -> HolidayInfo(15, "Распродажа 11.11 — всплеск доставок.")
        m == 11 && dow == Calendar.FRIDAY && d >= 24 -> HolidayInfo(20, "Черная пятница! Пик доставок.")
        else -> null
    }
}

fun getHotZoneRecommendation(cal: Calendar = Calendar.getInstance(), weather: DetailedWeather? = null): Pair<String, String> {
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    val isWeekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
    val badWeatherNote = if (weather != null && weather.weather in listOf(Weather.RAIN, Weather.STORM, Weather.SNOW, Weather.SLEET, Weather.FROST)) {
        " Из-за непогоды выбирай ТЦ с теплой зоной ожидания и плотные дарксторы."
    } else ""

    return when {
        hour in 8..11 -> "08:00–11:00 • Кофейни, пекарни, дарксторы и БЦ" to
                ("Утренний спрос: завтраки, выпечка, кофе в офисы и быстрые доставки из дарксторов.$badWeatherNote")
        hour in 12..15 && !isWeekend -> "12:00–15:00 (Будни) • Офисные кластеры и фудкорты ТЦ" to
                ("Обеденный перерыв в бизнес-центрах. Не стой в спальных районах — смещайся к деловым кварталам и крупным ТЦ.$badWeatherNote")
        hour in 12..16 && isWeekend -> "12:00–16:00 (Выходные) • Спальные районы, семейные рестораны и фастфуд" to
                ("В выходные люди просыпаются позже и заказывают семейные обеды, бургеры и продукты домой в жилые массивы.$badWeatherNote")
        hour == 16 && !isWeekend -> "16:00–17:00 • Пересменка (Дарксторы и ТЦ)" to
                ("Переходный час перед вечерним пиком. Начинай плавно смещаться из делового центра ближе к крупным ЖК.$badWeatherNote")
        hour in 17..23 -> "17:00–23:00 • Плотные ЖК, дарксторы у дома и суши/пиццерии" to
                ("Главный вечерний прайм-тайм! Все деньги сейчас в плотных жилых массивах вокруг суши, пиццерий и дарксторов.$badWeatherNote")
        else -> "00:00–08:00 • Ночные точки 24/7, фастфуд и аптеки" to
                ("Ночная смена: работают круглосуточный фастфуд, дежурные дарксторы и аптеки в центре и у крупных развязок.$badWeatherNote")
    }
}

/** Календарь в часовом поясе выбранного города (если известен смещение из погоды). */
fun cityCalendar(weather: DetailedWeather?, nowMs: Long = System.currentTimeMillis()): Calendar {
    val tz = weather?.utcOffsetSeconds?.let { SimpleTimeZone(it * 1000, "city") } ?: TimeZone.getDefault()
    return Calendar.getInstance(tz).apply { timeInMillis = nowMs }
}

private val ADDRESS_STOP_WORDS = setOf(
    "ул", "улица", "пр", "прт", "проспект", "пер", "переулок",
    "бул", "бр", "бульвар", "ш", "шоссе", "д", "дом",
    "к", "корп", "корпус", "стр", "строение", "п", "под", "подъезд", "кв", "жк"
)

private fun tokenizeAddress(s: String): List<String> = s
    .lowercase(Locale("ru"))
    .replace('ё', 'е')
    .replace(Regex("([а-яa-z])(\\d)"), "$1 $2")
    .replace(Regex("(\\d)([а-яa-z])"), "$1 $2")
    .replace(Regex("[^а-яa-z0-9]+"), " ")
    .trim()
    .split(' ')
    .filter { it.isNotEmpty() }

/** Нечёткий поиск по адресу, коду и комментарию: игнорирует ё/е, пунктуацию и сокращения вроде «ул.», «д.», «корп.». */
fun matchesIntercomQuery(note: IntercomNote, query: String): Boolean {
    if (query.isBlank()) return true
    val rawQueryTokens = tokenizeAddress(query)
    if (rawQueryTokens.isEmpty()) return true
    val meaningfulQuery = rawQueryTokens.filter { it !in ADDRESS_STOP_WORDS }
    val tokens = meaningfulQuery.ifEmpty { rawQueryTokens }
    val haystack = tokenizeAddress("${note.address} ${note.code} ${note.comment} ${note.category}")
    return tokens.all { tok -> haystack.any { it.contains(tok) } }
}

/** Тихие часы для фоновых уведомлений: 23:00–07:00. */
fun isQuietHour(hour: Int): Boolean = hour >= 23 || hour < 7

/** Пароль разработчика хранится только в виде SHA-256 (в APK нет открытого текста). */
const val DEV_PASSWORD_SHA256 = "b6347ec506d54257445d1953e5eea5a7d152fe112e1fb6978ec234cf20b11ce0"

fun sha256Hex(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

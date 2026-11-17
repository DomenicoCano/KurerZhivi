package com.example.myapplication

import kotlinx.coroutines.*
import org.json.JSONObject
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

// ============================================================================
// СЕТЕВЫЕ ЗАПРОСЫ (ГЕОКОДИНГ, ПОГОДА + ДАВЛЕНИЕ + ЗАКАТ, RSS)
// ============================================================================

suspend fun searchCitiesGeocoding(query: String): List<Region> = withContext(Dispatchers.IO) {
    if (query.trim().length < 2) return@withContext emptyList()
    try {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val conn = URL("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=6&language=ru&format=json").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        if (conn.responseCode != 200) return@withContext emptyList()
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        if (!json.has("results")) return@withContext emptyList()
        val results = json.getJSONArray("results")
        val list = mutableListOf<Region>()
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val name = item.optString("name", "")
            val admin1 = item.optString("admin1", "")
            val country = item.optString("country", "")
            val base = if (admin1.isNotBlank() && admin1 != name) "$name ($admin1)" else name
            val display = if (country.isNotBlank() && country != "Россия") "$base, $country" else base
            list.add(Region("geo_${item.optInt("id", i)}", display, item.getDouble("latitude"), item.getDouble("longitude")))
        }
        return@withContext list
    } catch (_: Exception) { return@withContext emptyList() }
}

data class DarknessInfo(val sunriseStr: String, val sunsetStr: String, val bannerTitle: String, val detailedHint: String)

fun buildDarknessInfo(sunriseIso: String, sunsetIso: String, tz: TimeZone = TimeZone.getDefault(), nowMs: Long = System.currentTimeMillis()): DarknessInfo {
    try {
        val sr = sunriseIso.substringAfter("T", "07:00")
        val ss = sunsetIso.substringAfter("T", "18:00")
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = tz }
        val sunriseDate = sdf.parse(sunriseIso)
        val sunsetDate = sdf.parse(sunsetIso)

        if (sunriseDate != null && sunsetDate != null) {
            return when {
                nowMs < sunriseDate.time -> {
                    val diffMin = ((sunriseDate.time - nowMs) / 60000).toInt().coerceAtLeast(1)
                    val h = diffMin / 60
                    val m = diffMin % 60
                    val timePart = if (h > 0) "через $h ч. $m мин." else "через $m мин."
                    DarknessInfo(
                        sr, ss,
                        "🌙 Темно • Рассвет в $sr ($timePart)",
                        "Сейчас темное время суток (рассвет в $sr). Обязательно включи фару/габариты, надень светоотражающие элементы и держи фонарик для поиска подъездов!"
                    )
                }
                nowMs < sunsetDate.time -> {
                    val diffMin = ((sunsetDate.time - nowMs) / 60000).toInt().coerceAtLeast(1)
                    val h = diffMin / 60
                    val m = diffMin % 60
                    val timePart = if (h > 0) "через $h ч. $m мин." else "через $m мин."
                    val hint = if (diffMin <= 150) {
                        "Темнеет в $ss ($timePart) — заранее проверь заряд фары, заднего габарита и фонарика!"
                    } else {
                        "Световой день. Темнеет в $ss ($timePart). Если смена до вечера — возьми фонарь и заряди габариты."
                    }
                    DarknessInfo(
                        sr, ss,
                        "🌅 Темнеет в $ss ($timePart)",
                        hint
                    )
                }
                else -> {
                    DarknessInfo(
                        sr, ss,
                        "🌙 Темное время суток (закат был в $ss) • Рассвет в $sr",
                        "На улице темно (закат в $ss). Включи фару и красный габарит, используй светоотражатели и фонарик (в темноте искать номера домов дольше)."
                    )
                }
            }
        }
    } catch (_: Exception) {}
    // Нет данных о закате — не выдумываем время, баннер скрывается
    return DarknessInfo("--:--", "--:--", "", "")
}

/**
 * Погода. [whitelist] — режим «белых списков» мобильной сети: Open-Meteo там заблокирован, поэтому его не ждём,
 * а сразу идём по российским источникам: API Яндекса (ключ) → страница Яндекс Погоды → Рамблер.Погода.
 */
suspend fun fetchExpandedWeather(lat: Double, lon: Double, yandexKey: String, transport: TransportType, whitelist: Boolean = false, regionId: String = ""): WeatherData = withContext(Dispatchers.IO) {
    if (whitelist) {
        val ru = fetchRuWeather(lat, lon, yandexKey, regionId)
        return@withContext ru?.toWeatherData(transport) ?: WeatherData(DetailedWeather(Weather.UNKNOWN, 0, 0, 0, 0.0, source = "Нет связи"), emptyList(), emptyList())
    }
    var detailedWeather: DetailedWeather? = null
    val forecastList = mutableListOf<DailyForecast>()
    val hourlyScoreList = mutableListOf<HourlyScoreItem>()

    val yandex = if (yandexKey.isNotBlank()) fetchYandexWeather(lat, lon, yandexKey) else null
    detailedWeather = yandex?.current

    try {
        val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m,wind_gusts_10m,uv_index,surface_pressure" +
                "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m,surface_pressure" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                "&wind_speed_unit=ms&timezone=auto&forecast_days=4"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000; conn.readTimeout = 6000
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        // Время в ответе — по часовому поясу ГОРОДА, а не телефона
        val offsetSec = json.optInt("utc_offset_seconds", TimeZone.getDefault().rawOffset / 1000)
        val tz = SimpleTimeZone(offsetSec * 1000, "city")

        val currentJson = json.getJSONObject("current")
        val omTemp = currentJson.getDouble("temperature_2m")
        val omFeels = currentJson.getDouble("apparent_temperature")
        val omHumidity = currentJson.getInt("relative_humidity_2m")
        val omIsDay = currentJson.getInt("is_day") == 1
        val omWindSpeed = currentJson.getDouble("wind_speed_10m")
        val omWindGusts = currentJson.optDouble("wind_gusts_10m", omWindSpeed)
        val omUv = currentJson.optDouble("uv_index", 0.0)
        val omPressureHpa = currentJson.optDouble("surface_pressure", 997.0)
        val omPressureMmHg = (omPressureHpa * 0.750062).roundToInt()
        val omWeather = decodeWeather(currentJson.getInt("weather_code"), omTemp)

        var upcomingAlert: String? = null
        var pressureDeltaMmHg = 0

        if (json.has("hourly")) {
            val hJson = json.getJSONObject("hourly")
            val hTimes = hJson.getJSONArray("time")
            val hTemps = hJson.getJSONArray("temperature_2m")
            val hProbs = hJson.getJSONArray("precipitation_probability")
            val hPrecip = hJson.getJSONArray("precipitation")
            val hCodes = hJson.getJSONArray("weather_code")
            val hWinds = hJson.getJSONArray("wind_speed_10m")
            val hPressures = hJson.optJSONArray("surface_pressure")

            val nowMs = System.currentTimeMillis()
            val sdfH = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = tz }
            var startIdx = 0
            for (i in 0 until hTimes.length()) {
                val d = sdfH.parse(hTimes.getString(i))
                // первый слот, который ещё не закончился (текущий час)
                if (d != null && d.time + 3_600_000L > nowMs) { startIdx = i; break }
            }

            if (hPressures != null && startIdx + 6 < hPressures.length()) {
                val pNow = (hPressures.optDouble(startIdx, omPressureHpa) * 0.750062).roundToInt()
                val pFuture = (hPressures.optDouble(startIdx + 6, omPressureHpa) * 0.750062).roundToInt()
                pressureDeltaMmHg = pFuture - pNow
            }

            if ((detailedWeather?.weather ?: omWeather) in listOf(Weather.SUN, Weather.CLOUDY)) {
                for (offset in 1..3) {
                    val idx = startIdx + offset
                    if (idx < hTimes.length()) {
                        val fw = decodeWeather(hCodes.getInt(idx), hTemps.getDouble(idx))
                        val fProb = hProbs.optInt(idx, 0)
                        if (fw in listOf(Weather.LIGHT_RAIN, Weather.RAIN, Weather.STORM, Weather.SLEET, Weather.SNOW) && (fProb >= 55 || hPrecip.optDouble(idx, 0.0) >= 0.3)) {
                            upcomingAlert = "Радар: через $offset ч. ожидается ${fw.title.lowercase()} ($fProb%)! Кэфы взлетят, выходи заранее."
                            break
                        }
                    }
                }
            }

            for (offset in 0 until 12) {
                val idx = startIdx + offset
                if (idx < hTimes.length()) {
                    val parsedDate = sdfH.parse(hTimes.getString(idx)) ?: Date()
                    val slotCal = Calendar.getInstance(tz).apply { time = parsedDate }
                    val slotHour = slotCal.get(Calendar.HOUR_OF_DAY)
                    val slotTemp = hTemps.getDouble(idx).roundToInt()
                    val slotW = decodeWeather(hCodes.getInt(idx), slotTemp.toDouble())
                    val slotPressMm = if (hPressures != null) (hPressures.optDouble(idx, omPressureHpa) * 0.750062).roundToInt() else omPressureMmHg
                    hourlyScoreList.add(hourlyItem(slotCal, slotTemp, slotW, hWinds.getDouble(idx), slotPressMm, transport))
                }
            }
        }

        val dailyJson = json.getJSONObject("daily")
        val darkInfo = buildDarknessInfo(
            dailyJson.optJSONArray("sunrise")?.optString(0, "") ?: "",
            dailyJson.optJSONArray("sunset")?.optString(0, "") ?: "",
            tz
        )

        if (detailedWeather == null) {
            val tInt = omTemp.roundToInt()
            val fInt = omFeels.roundToInt()
            val hasIce = tInt in -3..2 && (omHumidity >= 80 || omWeather in listOf(Weather.LIGHT_RAIN, Weather.RAIN, Weather.SLEET, Weather.SNOW))
            detailedWeather = DetailedWeather(
                weather = omWeather, temp = tInt, feelsLike = fInt, humidity = omHumidity,
                windSpeed = omWindSpeed, windGusts = omWindGusts, uvIndex = omUv,
                pressureMmHg = omPressureMmHg, pressureDeltaMmHg = pressureDeltaMmHg,
                isDay = omIsDay, hasIceRisk = hasIce, upcomingAlert = upcomingAlert,
                sunriseStr = darkInfo.sunriseStr, sunsetStr = darkInfo.sunsetStr,
                darknessBannerTitle = darkInfo.bannerTitle, darknessHint = darkInfo.detailedHint,
                source = "Open-Meteo",
                utcOffsetSeconds = offsetSec
            )
        } else {
            detailedWeather = detailedWeather.copy(
                uvIndex = omUv, upcomingAlert = upcomingAlert,
                windGusts = maxOf(detailedWeather.windGusts, omWindGusts),
                pressureDeltaMmHg = pressureDeltaMmHg,
                sunriseStr = darkInfo.sunriseStr, sunsetStr = darkInfo.sunsetStr,
                darknessBannerTitle = darkInfo.bannerTitle, darknessHint = darkInfo.detailedHint,
                utcOffsetSeconds = offsetSec
            )
        }

        val times = dailyJson.getJSONArray("time")
        val codes = dailyJson.getJSONArray("weather_code")
        val minTemps = dailyJson.getJSONArray("temperature_2m_min")
        val maxTemps = dailyJson.getJSONArray("temperature_2m_max")
        val sdfParse = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sdfDisplay = SimpleDateFormat("d MMMM (E)", Locale("ru"))
        for (i in 1 until times.length()) {
            val dateObj = sdfParse.parse(times.getString(i)) ?: Date()
            val minT = minTemps.getDouble(i).roundToInt()
            val maxT = maxTemps.getDouble(i).roundToInt()
            forecastList.add(DailyForecast(sdfDisplay.format(dateObj), decodeWeather(codes.getInt(i), (minT + maxT) / 2.0), minT, maxT))
        }
        return@withContext WeatherData(detailedWeather, forecastList, hourlyScoreList)
    } catch (_: Exception) {
        // Open-Meteo недоступен (например, белые списки мобильной сети) — берём всё из российских источников
        (yandex ?: fetchRuWeather(lat, lon, "", regionId))?.let { return@withContext it.toWeatherData(transport) }
        val emptyWeather = detailedWeather ?: DetailedWeather(Weather.UNKNOWN, 0, 0, 0, 0.0, source = "Нет связи")
        return@withContext WeatherData(emptyWeather, forecastList, hourlyScoreList)
    }
}

/** Балл одного часа почасового прогноза (общая логика для Open-Meteo и Яндекса). */
fun hourlyItem(slotCal: Calendar, temp: Int, w: Weather, wind: Double, pressMm: Int, transport: TransportType): HourlyScoreItem {
    val hour = slotCal.get(Calendar.HOUR_OF_DAY)
    val simW = DetailedWeather(
        weather = w, temp = temp, feelsLike = temp, humidity = 70, windSpeed = wind, pressureMmHg = pressMm,
        isDay = hour in 7..20, hasIceRisk = temp in -3..2 && w != Weather.SUN
    )
    val s = calculateScore100(simW, emptyList(), slotCal, transport)
    return HourlyScoreItem(hour, String.format(Locale.getDefault(), "%02d:00", hour), temp, w, s.totalScore, s.color, slotCal.timeInMillis)
}

// ============================================================================
// ЯНДЕКС ПОГОДА: API v3 (GraphQL, заголовок X-Yandex-Weather-Key); старые ключи — v2/informers.
// Яндекс доступен в режиме «белых списков» мобильной сети, Open-Meteo — нет.
// ============================================================================

data class YaHour(val ms: Long, val temp: Int, val weather: Weather, val prec: Double, val precProb: Int, val pressureMm: Int, val wind: Double)
data class YandexWeather(
    val current: DetailedWeather,
    val hours: List<YaHour>,
    val sunriseMs: Long?,
    val sunsetMs: Long?,
    val offsetSec: Int?,
    val dailyForecast: List<DailyForecast> = emptyList()
)

/** ISO 8601 ("2022-10-26T12:37:53.528+03:00", "...Z") → мс UTC. Без java.time (minSdk 24). */
fun parseIsoMs(s: String): Long? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""").find(s.trim()) ?: return null
    val g = m.groupValues
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
    }
    val tz = g[7]
    val offsetMin = if (tz.isEmpty() || tz == "Z") 0 else {
        val digits = tz.substring(1).replace(":", "")
        (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * (if (tz[0] == '-') -1 else 1)
    }
    return cal.timeInMillis - offsetMin * 60_000L
}

fun decodeYandexCondition(c: String, temp: Int): Weather {
    if (temp <= -20) return Weather.FROST
    return when (c.uppercase(Locale.US).replace('-', '_')) {
        "CLEAR" -> Weather.SUN
        "PARTLY_CLOUDY", "CLOUDY", "OVERCAST" -> Weather.CLOUDY
        "DRIZZLE", "LIGHT_RAIN" -> Weather.LIGHT_RAIN
        "RAIN", "MODERATE_RAIN" -> Weather.RAIN
        "HEAVY_RAIN", "CONTINUOUS_HEAVY_RAIN", "SHOWERS", "THUNDERSTORM", "THUNDERSTORM_WITH_RAIN", "THUNDERSTORM_WITH_HAIL" -> Weather.STORM
        "SLEET", "WET_SNOW", "HAIL" -> Weather.SLEET
        "LIGHT_SNOW", "SNOW", "SNOWFALL", "SNOW_SHOWERS" -> Weather.SNOW
        else -> Weather.CLOUDY
    }
}

/** «Ощущается как» (формула ветро-холодового индекса), если источник её не даёт. */
fun windChill(temp: Int, windMs: Double): Int {
    val v = windMs * 3.6
    if (temp > 10 || v < 4.8) return temp
    val p = Math.pow(v, 0.16)
    return Math.round(13.12 + 0.6215 * temp - 11.37 * p + 0.3965 * temp * p).toInt()
}

private fun iceRisk(temp: Int, humidity: Int, w: Weather) =
    temp in -3..2 && (humidity >= 80 || w in listOf(Weather.LIGHT_RAIN, Weather.RAIN, Weather.SLEET, Weather.SNOW))

suspend fun fetchYandexWeather(lat: Double, lon: Double, key: String): YandexWeather? = withContext(Dispatchers.IO) {
    fetchYandexGraphQl(lat, lon, key) ?: fetchYandexInformers(lat, lon, key)
}

private fun fetchYandexGraphQl(lat: Double, lon: Double, key: String): YandexWeather? = try {
    // Только поля, доступные на всех тарифах, включая «Тестовый» (feelsLike и uvIndex — нет)
    val q = "{ weatherByPoint(request: { lat: $lat, lon: $lon }) { location { timezone { offset } } " +
        "now { temperature condition daytime humidity pressure(unit: MM_HG) windSpeed windGust } " +
        "forecast { days(limit: 3) { sunriseTime sunsetTime hours { time temperature condition prec precProbability pressure(unit: MM_HG) windSpeed } } } } }"
    val conn = URL("https://api.weather.yandex.ru/graphql/query").openConnection() as HttpURLConnection
    conn.connectTimeout = 6000; conn.readTimeout = 6000
    conn.requestMethod = "POST"; conn.doOutput = true
    conn.setRequestProperty("X-Yandex-Weather-Key", key)
    conn.setRequestProperty("Content-Type", "application/json")
    conn.outputStream.use { it.write(JSONObject().put("query", q).toString().toByteArray(Charsets.UTF_8)) }
    if (conn.responseCode != 200) null else {
        val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val wbp = root.optJSONObject("data")?.optJSONObject("weatherByPoint")
        val now = wbp?.optJSONObject("now")
        if (wbp == null || now == null) null else {
            val temp = now.getInt("temperature")
            val wind = now.getDouble("windSpeed")
            val humidity = now.getInt("humidity")
            val w = decodeYandexCondition(now.getString("condition"), temp)
            val current = DetailedWeather(
                weather = w, temp = temp, feelsLike = windChill(temp, wind), humidity = humidity,
                windSpeed = wind, windGusts = now.optDouble("windGust", wind).let { if (it.isNaN()) wind else it },
                pressureMmHg = now.getInt("pressure"), isDay = now.optString("daytime") != "NIGHT",
                hasIceRisk = iceRisk(temp, humidity, w), source = "Яндекс Погода"
            )
            val hours = mutableListOf<YaHour>()
            var sunrise: Long? = null; var sunset: Long? = null
            val days = wbp.optJSONObject("forecast")?.optJSONArray("days")
            if (days != null) for (d in 0 until days.length()) {
                val day = days.getJSONObject(d)
                if (d == 0) { sunrise = parseIsoMs(day.optString("sunriseTime")); sunset = parseIsoMs(day.optString("sunsetTime")) }
                val hs = day.optJSONArray("hours") ?: continue
                for (i in 0 until hs.length()) {
                    val h = hs.getJSONObject(i)
                    val ms = parseIsoMs(h.optString("time")) ?: continue
                    val t = h.getInt("temperature")
                    val prob = h.optDouble("precProbability", 0.0).let { if (it.isNaN()) 0.0 else it }
                    hours.add(YaHour(ms, t, decodeYandexCondition(h.getString("condition"), t), h.optDouble("prec", 0.0),
                        (prob * 100).toInt(), h.optInt("pressure", current.pressureMmHg), h.optDouble("windSpeed", wind)))
                }
            }
            val offset = wbp.optJSONObject("location")?.optJSONObject("timezone")?.optInt("offset")
            YandexWeather(current, hours.sortedBy { it.ms }, sunrise, sunset, offset)
        }
    }
} catch (_: Exception) { null }

/** Старые ключи «Погода на вашем сайте» (заголовок X-Yandex-API-Key) — только текущая погода. */
private fun fetchYandexInformers(lat: Double, lon: Double, key: String): YandexWeather? = try {
    val conn = URL("https://api.weather.yandex.ru/v2/informers?lat=$lat&lon=$lon").openConnection() as HttpURLConnection
    conn.connectTimeout = 5000; conn.readTimeout = 5000
    conn.setRequestProperty("X-Yandex-API-Key", key)
    if (conn.responseCode != 200) null else {
        val fact = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("fact")
        val temp = fact.getDouble("temp").roundToInt()
        val windSpeed = fact.getDouble("wind_speed")
        val humidity = fact.getInt("humidity")
        val w = decodeYandexCondition(fact.getString("condition"), temp)
        YandexWeather(
            DetailedWeather(
                weather = w, temp = temp, feelsLike = fact.optDouble("feels_like", temp.toDouble()).roundToInt(), humidity = humidity,
                windSpeed = windSpeed, windGusts = fact.optDouble("wind_gust", windSpeed), pressureMmHg = fact.optInt("pressure_mm", 748),
                isDay = fact.optString("daytime") == "d", hasIceRisk = iceRisk(temp, humidity, w), source = "Яндекс Погода"
            ), emptyList(), null, null, null
        )
    }
} catch (_: Exception) { null }

/** Российские источники погоды по очереди: API Яндекса (если есть ключ) → страница Яндекс Погоды → Рамблер.Погода. */
suspend fun fetchRuWeather(lat: Double, lon: Double, yandexKey: String, regionId: String): YandexWeather? = withContext(Dispatchers.IO) {
    (if (yandexKey.isNotBlank()) fetchYandexWeather(lat, lon, yandexKey) else null)
        ?: fetchYandexPageWeather(lat, lon)
        ?: fetchRamblerWeather(regionId)
}

private fun httpGetText(url: String, timeoutMs: Int = 7000): String? = try {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
    conn.setRequestProperty("Accept-Language", "ru-RU,ru")
    if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
} catch (_: Exception) { null }

/** Страница yandex.ru/pogoda по координатам — без ключа; доступна при белых списках. */
fun fetchYandexPageWeather(lat: Double, lon: Double): YandexWeather? =
    httpGetText("https://yandex.ru/pogoda/?lat=$lat&lon=$lon")?.let { parseYandexPogodaHtml(it) }

/** Погода по русскому описанию Яндекса («облачно с прояснениями», «небольшой дождь» …). */
fun weatherFromRuText(text: String, temp: Int): Weather {
    val s = text.lowercase()
    return when {
        temp <= -20 -> Weather.FROST
        "гроз" in s || "ливень" in s || "ливни" in s || "сильный дождь" in s -> Weather.STORM
        "мокрый снег" in s || "дождь со снегом" in s || "дожди со снегом" in s || "град" in s -> Weather.SLEET
        "снег" in s -> if (temp >= 1) Weather.SLEET else Weather.SNOW
        "небольшой дождь" in s || "морось" in s -> Weather.LIGHT_RAIN
        "дожд" in s -> Weather.RAIN
        "ясно" in s || "малооблачно" in s -> Weather.SUN
        else -> Weather.CLOUDY
    }
}

private val PRECIP = listOf(Weather.LIGHT_RAIN, Weather.RAIN, Weather.STORM, Weather.SLEET, Weather.SNOW)

private fun ruInt(s: String): Int = s.replace('−', '-').replace("+", "").trim().toInt()

/**
 * Разбор HTML страницы Яндекс Погоды. Берётся доступный для скринридеров текст
 * («22:00: 0°, малооблачно, Ощущается как -5°.»), а не имена CSS-классов целиком — они меняются при каждой сборке сайта.
 * Возвращает null, если страница не похожа на прогноз (капча, другая вёрстка).
 */
fun parseYandexPogodaHtml(html: String): YandexWeather? = try {
    val cleanHtml = html
        .replace(Regex("<script\\b[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style\\b[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
    // Текущее местное время и UTC → смещение пояса города
    val clock = Regex("""dateTime="([^"]+)"[^>]*>[^<]*локальное время (\d{1,2}):(\d{2})""").find(cleanHtml)
    val utcMs = clock?.let { parseIsoMs(it.groupValues[1]) } ?: System.currentTimeMillis()
    val offsetSec = clock?.let {
        val local = it.groupValues[2].toInt() * 60 + it.groupValues[3].toInt()
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMs }
        var diff = local - (utcCal.get(Calendar.HOUR_OF_DAY) * 60 + utcCal.get(Calendar.MINUTE))
        if (diff > 720) diff -= 1440
        if (diff < -720) diff += 1440
        Math.round(diff / 15.0).toInt() * 15 * 60
    }
    val tz: TimeZone = offsetSec?.let { SimpleTimeZone(it * 1000, "city") } ?: TimeZone.getDefault()

    val text = cleanHtml.replace(Regex("<[^>]+>"), "|").replace("&nbsp;", " ").replace("&#160;", " ")
    val tempM = Regex("""AppFactTemperature_sign[^>]*>([^<]*)</span><span[^>]*AppFactTemperature_value[^>]*>(\d+)<""").find(cleanHtml)
        ?: throw IllegalStateException("нет текущей температуры")
    val temp = (if (tempM.groupValues[1].contains('−') || tempM.groupValues[1].contains('-')) -1 else 1) * tempM.groupValues[2].toInt()
    val feels = Regex("""Ощущается как ([+−-]?\d+)°""").find(text)?.let { ruInt(it.groupValues[1]) } ?: temp
    val condText = Regex("""warning__first_text[^>]*>([^<]+)<""").find(cleanHtml)?.groupValues?.get(1) ?: ""
    val details = Regex("""(\d+(?:[.,]\d)?) м/с(?:, [А-ЯЁ-]+)?\|+(\d{3})\|+(\d{1,3})%""").find(text)
    val wind = details?.groupValues?.get(1)?.replace(',', '.')?.toDouble() ?: 0.0
    val pressure = details?.groupValues?.get(2)?.toInt() ?: 748
    val humidity = details?.groupValues?.get(3)?.toInt() ?: 70

    // Почасовой прогноз: Яндекс рендерит адаптивную ленту 2–3 раза подряд — останавливаемся на первом цикле (до 25 ч)
    val localNow = Calendar.getInstance(tz).apply { timeInMillis = utcMs }
    val dayStart = (localNow.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val hours = mutableListOf<YaHour>()
    var dayShift = 0
    var prevHour = -1
    var firstHour = -1
    for (m in Regex("""(\d{2}):00: ([+−-]?\d+)°, ([^,|]+), Ощущается как""").findAll(text)) {
        val h = m.groupValues[1].toInt()
        if (firstHour < 0) firstHour = h
        else if (hours.size >= 24 && (h == firstHour || h == prevHour)) break
        if (h == prevHour) continue
        if (prevHour >= 0 && h < prevHour) dayShift++
        prevHour = h
        val t = ruInt(m.groupValues[2])
        val w = weatherFromRuText(m.groupValues[3], t)
        val ms = dayStart.timeInMillis + dayShift * 86_400_000L + h * 3_600_000L
        hours.add(YaHour(ms, t, w, if (w in PRECIP) 0.5 else 0.0, 0, pressure, wind))
        if (hours.size >= 25) break
    }

    // Прогноз на следующие 3 дня из текстовых блоков для скринридера («Завтра, 19 ноября:|...»)
    val shortDow = mapOf(
        "завтра" to "завтра", "понедельник" to "пн", "вторник" to "вт", "среда" to "ср",
        "четверг" to "чт", "пятница" to "пт", "суббота" to "сб", "воскресенье" to "вс"
    )
    val dailyForecast = mutableListOf<DailyForecast>()
    val seenDates = mutableSetOf<String>()
    val dayHeaderRe = Regex("""(Завтра|Понедельник|Вторник|Среда|Четверг|Пятница|Суббота|Воскресенье),\s*(\d{1,2}\s+[а-я]+):\|+""")
    val headers = dayHeaderRe.findAll(text).toList()
    for (idx in headers.indices) {
        val hm = headers[idx]
        val dayWord = hm.groupValues[1].lowercase(Locale("ru"))
        val datePart = hm.groupValues[2].trim()
        val bodyStart = hm.range.last + 1
        val nextStart = if (idx + 1 < headers.size) headers[idx + 1].range.first else text.length
        val body = text.substring(bodyStart, minOf(nextStart, bodyStart + 1200))
        val temps = Regex("""(?:температура воздуха |днём |вечером |ночью )([+−-]?\d+)°""")
            .findAll(body).mapNotNull { runCatching { ruInt(it.groupValues[1]) }.getOrNull() }.toList()
        if (temps.isEmpty() || !seenDates.add(datePart)) continue
        val dayCond = Regex("""днём\s+[+−-]?\d+°,\s*ощущается как\s+[+−-]?\d+°,\s*([^,|]+)""").find(body)?.groupValues?.get(1) ?: ""
        val maxT = temps.max()
        val minT = temps.min()
        dailyForecast.add(DailyForecast("$datePart (${shortDow[dayWord] ?: dayWord})", weatherFromRuText(dayCond, maxT), minT, maxT))
        if (dailyForecast.size >= 3) break
    }

    // Восход/закат из почасовой ленты: «Восход 17 ноября 07:54»
    fun sunEvent(name: String): Long? = Regex("""$name (\d{1,2}) [а-я]+ (\d{2}):(\d{2})""").find(text)?.let { m ->
        val plusDay = if (m.groupValues[1].toInt() != localNow.get(Calendar.DAY_OF_MONTH)) 1 else 0
        dayStart.timeInMillis + plusDay * 86_400_000L + m.groupValues[2].toInt() * 3_600_000L + m.groupValues[3].toInt() * 60_000L
    }
    val sunrise = sunEvent("Восход")
    val sunset = sunEvent("Закат")

    val w = weatherFromRuText(condText.ifBlank { hours.firstOrNull()?.weather?.title ?: "" }, temp)
    val isDay = if (sunrise != null && sunset != null) {
        val sr = if (sunrise > sunset) sunrise - 86_400_000L else sunrise
        utcMs in sr until sunset
    } else localNow.get(Calendar.HOUR_OF_DAY) in 8..17
    YandexWeather(
        DetailedWeather(
            weather = w, temp = temp, feelsLike = feels, humidity = humidity, windSpeed = wind, windGusts = wind,
            pressureMmHg = pressure, isDay = isDay, hasIceRisk = iceRisk(temp, humidity, w), source = "Яндекс Погода (сайт)",
            utcOffsetSeconds = offsetSec
        ), hours, sunrise, sunset, offsetSec, dailyForecast
    )
} catch (_: Exception) { null }

/** Адреса Рамблер.Погоды для встроенных городов (у Рамблера нет поиска по координатам). */
private val RAMBLER_PATHS = mapOf("msk" to "v-moskve", "spb" to "v-sankt-peterburge", "kzn" to "v-kazani", "krd" to "v-krasnodare", "ryz" to "v-ryazani")

fun weatherFromRamblerIcon(icon: String, temp: Int): Weather {
    val s = icon.lowercase()
    return when {
        temp <= -20 -> Weather.FROST
        "thunder" in s || "storm" in s -> Weather.STORM
        "sleet" in s || "hail" in s -> Weather.SLEET
        "snow" in s -> Weather.SNOW
        "rain" in s && ("light" in s || "occ" in s || "drizzle" in s) -> Weather.LIGHT_RAIN
        "rain" in s -> Weather.RAIN
        "clear" in s -> Weather.SUN
        else -> Weather.CLOUDY
    }
}

/** Рамблер.Погода (запасной источник, только для встроенных городов). Неофициальный JSON сайта — может измениться. */
fun fetchRamblerWeather(regionId: String): YandexWeather? {
    val path = RAMBLER_PATHS[regionId] ?: return null
    return try {
        val now = JSONObject(httpGetText("https://weather.rambler.ru/api/v3/now/?url_path=$path") ?: return null)
        val cur = now.getJSONObject("current_weather")
        val temp = cur.getInt("temperature")
        val wind = cur.optDouble("wind_speed", 0.0)
        val hw = now.optJSONArray("hour_weather")
        val humidity = hw?.optJSONObject(0)?.optInt("humidity", 70) ?: 70
        val localNow = now.optString("local_now")
        // Смещение пояса: местное время сайта против UTC
        val localAsUtc = parseIsoMs(localNow + "Z")
        val offsetSec = localAsUtc?.let { Math.round((it - System.currentTimeMillis()) / 900_000.0).toInt() * 900 }
        val tz: TimeZone = offsetSec?.let { SimpleTimeZone(it * 1000, "city") } ?: TimeZone.getDefault()
        val localCal = Calendar.getInstance(tz)
        val dayStart = (localCal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val hours = mutableListOf<YaHour>()
        var dayShift = 0; var prev = -1
        if (hw != null) for (i in 0 until hw.length()) {
            val o = hw.getJSONObject(i)
            val h = o.getInt("hour")
            if (prev >= 0 && h < prev) dayShift++
            prev = h
            val t = o.getInt("temperature")
            val w = weatherFromRamblerIcon(o.optString("icon"), t)
            val baseMs = dayStart.timeInMillis + dayShift * 86_400_000L + h * 3_600_000L
            // Рамблер даёт шаг 3 часа — раскладываем на часы, чтобы таймлайн был почасовым
            for (k in 0 until 3) hours.add(YaHour(baseMs + k * 3_600_000L, t, w, o.optDouble("prec_mm", 0.0), 0, o.optInt("pressure_mm", 748), o.optDouble("wind_speed", wind)))
        }
        val today = httpGetText("https://weather.rambler.ru/api/v3/today/?url_path=$path")?.let { JSONObject(it).optJSONObject("date_weather") }
        fun localIso(s: String?): Long? = s?.takeIf { it.isNotBlank() }?.let { v -> parseIsoMs(v + "Z")?.minus((offsetSec ?: 0) * 1000L) }
        val sunrise = localIso(today?.optString("sunrise"))
        val sunset = localIso(today?.optString("sunset"))
        val w = weatherFromRamblerIcon(cur.optString("icon"), temp)
        YandexWeather(
            DetailedWeather(
                weather = w, temp = temp, feelsLike = cur.optInt("temp_feels", temp), humidity = humidity, windSpeed = wind, windGusts = wind,
                uvIndex = today?.optDouble("uv", 0.0) ?: 0.0, pressureMmHg = cur.optInt("pressure_mm", 748),
                isDay = !cur.optString("icon").contains("night"), hasIceRisk = iceRisk(temp, humidity, w), source = "Рамблер.Погода"
            ), hours, sunrise, sunset, offsetSec
        )
    } catch (_: Exception) { null }
}

/** Полный набор данных только из Яндекса (когда Open-Meteo недоступен). */
fun YandexWeather.toWeatherData(transport: TransportType, nowMs: Long = System.currentTimeMillis()): WeatherData {
    val tz: TimeZone = offsetSec?.let { SimpleTimeZone(it * 1000, "city") } ?: TimeZone.getDefault()
    val upcoming = hours.filter { it.ms + 3_600_000L > nowMs }
    val timeline = upcoming.take(12).map { h ->
        hourlyItem(Calendar.getInstance(tz).apply { timeInMillis = h.ms }, h.temp, h.weather, h.wind, h.pressureMm, transport)
    }
    val delta = if (upcoming.size > 6) upcoming[6].pressureMm - upcoming[0].pressureMm else 0
    val alert = if (current.weather in listOf(Weather.SUN, Weather.CLOUDY)) upcoming.drop(1).take(3).withIndex().firstOrNull { (_, h) ->
        h.weather in listOf(Weather.LIGHT_RAIN, Weather.RAIN, Weather.STORM, Weather.SLEET, Weather.SNOW) && (h.precProb >= 55 || h.prec >= 0.3)
    }?.let { (i, h) -> "Радар: через ${i + 1} ч. ожидается ${h.weather.title.lowercase()}" + (if (h.precProb > 0) " (${h.precProb}%)" else "") + "! Кэфы взлетят, выходи заранее." } else null
    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = tz }
    // Источник может дать завтрашний восход и сегодняшний закат — приводим восход к тем же суткам
    val sunrise = if (sunriseMs != null && sunsetMs != null && sunriseMs > sunsetMs) sunriseMs - 86_400_000L else sunriseMs
    val dark = if (sunrise != null && sunsetMs != null) buildDarknessInfo(iso.format(Date(sunrise)), iso.format(Date(sunsetMs)), tz, nowMs)
    else DarknessInfo("--:--", "--:--", "", "")
    // Прогноз на следующие дни — либо готовый суточный от источника, либо min/max по часам
    val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
    val display = SimpleDateFormat("d MMMM (E)", Locale("ru")).apply { timeZone = tz }
    val today = dayFmt.format(Date(nowMs))
    val forecast = dailyForecast.ifEmpty {
        hours.groupBy { dayFmt.format(Date(it.ms)) }.filter { (day, list) -> day > today && list.size >= 6 }.toSortedMap().map { (_, list) ->
            val mid = list[list.size / 2]
            DailyForecast(display.format(Date(mid.ms)), list.maxByOrNull { it.prec }?.takeIf { it.prec >= 0.3 }?.weather ?: mid.weather,
                list.minOf { it.temp }, list.maxOf { it.temp })
        }
    }
    val cur = current.copy(
        pressureDeltaMmHg = delta, upcomingAlert = alert,
        sunriseStr = dark.sunriseStr, sunsetStr = dark.sunsetStr, darknessBannerTitle = dark.bannerTitle, darknessHint = dark.detailedHint,
        utcOffsetSeconds = offsetSec
    )
    return WeatherData(cur, forecast, timeline)
}

fun decodeWeather(code: Int, temp: Double): Weather {
    if (temp <= -20.0) return Weather.FROST
    return when (code) {
        0, 1 -> Weather.SUN
        2, 3, 45, 48 -> Weather.CLOUDY
        51, 53, 55, 61, 80 -> Weather.LIGHT_RAIN
        56, 57, 66, 67 -> Weather.SLEET
        63, 81 -> Weather.RAIN
        65, 82, 95, 96, 99 -> Weather.STORM
        71, 73, 75, 77, 85, 86 -> if (temp >= 0.0) Weather.SLEET else Weather.SNOW
        else -> Weather.CLOUDY
    }
}

fun parseRssDateMs(pubDateStr: String): Long? {
    val raw = pubDateStr.trim()
    if (raw.isEmpty()) return null
    val formats = listOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    )
    for (sdf in formats) {
        try {
            sdf.parse(raw)?.let { return it.time }
        } catch (_: Exception) {}
    }
    return parseIsoMs(raw)
}

fun parseRssDate(pubDateStr: String): Pair<Boolean, String> {
    val ms = parseRssDateMs(pubDateStr) ?: return true to "Сегодня"
    val mins = ((System.currentTimeMillis() - ms) / 60000).coerceAtLeast(1)
    val hours = mins / 60
    val ageText = when {
        mins < 60 -> "$mins мин. назад"
        hours < 24 -> "$hours ч. назад"
        else -> "${hours / 24} дн. назад"
    }
    return (hours <= 12) to ageText
}

private val NEWS_KEYWORDS = listOf("пробк", "авари", "перекрыт", "мчс", "шторм", "дтп", "снегопад", "ураган", "гололед", "ливень", "метель", "потоп", "занос", "матч", "концерт", "сбой", "закрыт")

private data class RssItem(val title: String, val link: String, val pubDate: String, val source: String)

private fun readRss(urlStr: String, defaultSource: String, timeoutMs: Int = 5000): List<RssItem> {
    val conn = URL(urlStr).openConnection() as HttpURLConnection
    conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) KurerZhivi")
    val doc = conn.inputStream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
    val items = doc.getElementsByTagName("item")
    return (0 until items.length).mapNotNull { i ->
        val el = items.item(i) as? Element ?: return@mapNotNull null
        RssItem(
            el.getElementsByTagName("title").item(0)?.textContent?.trim() ?: "",
            el.getElementsByTagName("link").item(0)?.textContent?.trim() ?: "",
            el.getElementsByTagName("pubDate").item(0)?.textContent ?: "",
            el.getElementsByTagName("source").item(0)?.textContent ?: defaultSource
        )
    }
}

private fun toNews(list: List<RssItem>): List<NewsItem> = list.mapIndexed { i, it ->
    val (isFresh, ageText) = parseRssDate(it.pubDate)
    NewsItem(i, it.title, it.source, it.link, isFresh && NEWS_KEYWORDS.any { k -> it.title.lowercase().contains(k) }, ageText, isFresh)
}

/** Основа названия города для поиска в заголовках: «Москва» → «москв», «Санкт-Петербург» → «петербург», «Казань» → «казан». */
fun cityStem(name: String): String {
    val base = name.substringBefore(" (").substringBefore(",").trim().substringAfterLast('-').lowercase()
    return if (base.length > 4 && base.last() in "аяеиоуыьй") base.dropLast(1) else base
}

/** Точное совпадение города в новостном заголовке с учётом падежей, дефисных названий и без ложных вхождений («Чита» ≠ «считает», «Омск» ≠ «Томск»). */
fun matchesCityInHeadline(title: String, regionName: String): Boolean {
    val ru = Locale("ru")
    val t = title.lowercase(ru).replace('ё', 'е')
    val city = regionName.lowercase(ru).replace('ё', 'е').substringBefore(" (").substringBefore(",").trim()
    if (city.isEmpty()) return false
    val words = Regex("[а-яa-z0-9-]+").findAll(t).map { it.value }.toList()
    when (city) {
        "санкт-петербург" -> return words.any { it.startsWith("петербург") || it.startsWith("санкт-петербург") || it == "спб" }
        "ростов-на-дону" -> return words.any { it.startsWith("ростов") }
        "комсомольск-на-амуре" -> return words.any { it.startsWith("комсомольск") }
        "йошкар-ола" -> return words.any { it.startsWith("йошкар-ол") }
        "улан-удэ" -> return words.any { it == "улан-удэ" }
        "орел" -> return words.any { it == "орел" || (it.startsWith("орл") && it.drop(3) in setOf("а", "е", "у", "ом", "овск", "овской", "овская", "овские", "овском")) }
        "уфа" -> return words.any { it.startsWith("уф") && it.drop(2) in setOf("а", "е", "у", "ы", "ой", "имск", "имский", "имской", "имские", "имском") }
    }
    val shortVowelEnd = setOf("а", "я", "е", "и", "у", "ю", "ы", "ой", "ей", "ою", "инск", "инской", "инский", "инском", "ьск", "ьской", "ьском")
    val shortConsEnd = setOf("а", "е", "у", "ом", "и", "ий", "ой", "ая", "ое", "ую", "ого", "ому", "их", "им", "ие")
    val cityParts = city.split(' ').filter { it.length >= 2 }
    if (cityParts.isEmpty()) return false
    return cityParts.all { p ->
        words.any { w ->
            if (p.length <= 4) {
                if (p.last() in "аяеиыь") {
                    val b = p.dropLast(1)
                    w == p || (w.startsWith(b) && w.drop(b.length) in shortVowelEnd)
                } else {
                    w == p || (w.startsWith(p) && w.drop(p.length) in shortConsEnd)
                }
            } else {
                val s = when {
                    p.length >= 6 && p.takeLast(2) in setOf("ий", "ый", "ой", "ые", "ая", "ое") -> p.dropLast(2)
                    p.last() in "аяеиоуыьй" -> p.dropLast(1)
                    else -> p
                }
                w.startsWith(s) && w.length <= s.length + 6
            }
        }
    }
}

/** Федеральные ленты на российских серверах — доступны при «белых списках» мобильной сети. */
private val RU_FEEDS = listOf(
    "https://ria.ru/export/rss2/archive/index.xml" to "РИА Новости",
    "https://tass.ru/rss/v2.xml" to "ТАСС",
    "https://lenta.ru/rss/news" to "Лента.ру",
    "https://rssexport.rbc.ru/rbcnews/news/30/full.rss" to "РБК",
    "https://rg.ru/xml/index.xml" to "Российская газета",
    "https://www.kommersant.ru/RSS/news.xml" to "Коммерсантъ",
    "https://news.rambler.ru/rss/head/" to "Рамблер",
    "https://www.mk.ru/rss/index.xml" to "МК",
    "https://aif.ru/rss/news.php" to "АиФ",
    "https://www.interfax.ru/rss.asp" to "Интерфакс" // при белых списках МегаФона недоступен — просто пропускается
)

/** Новости о городе из федеральных лент. Если о городе ничего нет — одна служебная запись (это не ошибка сети). */
suspend fun fetchRuFeedsNews(region: Region): List<NewsItem> = coroutineScope {
    val results = RU_FEEDS.map { (url, src) -> async(Dispatchers.IO) { try { readRss(url, src) } catch (_: Exception) { null } } }.awaitAll()
    if (results.all { it == null }) throw Exception("RU feeds unavailable")
    val local = results.filterNotNull().flatten()
        .filter { matchesCityInHeadline(it.title, region.name) }
        .distinctBy { it.title }
        .sortedByDescending { parseRssDateMs(it.pubDate) ?: 0L }
        .take(6)
    if (local.isEmpty()) listOf(NewsItem(1, "В российских новостных лентах свежих новостей о городе нет.", "Система", "", false, "Сейчас", false))
    else toNews(local)
}

/**
 * Новости города. «Белые списки» — сразу российские ленты; иначе Google News,
 * а при его недоступности — автоматически российские ленты.
 */
suspend fun fetchLocalNews(region: Region, safeSources: Boolean): List<NewsItem> = withContext(Dispatchers.IO) {
    if (safeSources) return@withContext fetchRuFeedsNews(region)
    try {
        val query = URLEncoder.encode(region.name.substringBefore(" (").trim(), "UTF-8")
        val list = readRss("https://news.google.com/rss/search?q=$query&hl=ru&gl=RU&ceid=RU:ru", "Google News").take(6)
        if (list.isEmpty()) throw Exception("Empty RSS")
        toNews(list)
    } catch (_: Exception) {
        fetchRuFeedsNews(region)
    }
}

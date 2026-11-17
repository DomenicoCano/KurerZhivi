package com.example.myapplication

import androidx.compose.ui.graphics.Color
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.math.roundToInt

// ============================================================================
// МОДЕЛИ ДАННЫХ
// ============================================================================

enum class Weather(val title: String, val emoji: String) {
    SUN("Ясно", "☀️"), CLOUDY("Облачно", "☁️"), LIGHT_RAIN("Морось", "🌦️"),
    RAIN("Дождь", "🌧️"), STORM("Ливень / Гроза", "⛈️"), SLEET("Мокрый снег", "🌨️"),
    SNOW("Снегопад", "❄️"), FROST("Мороз", "🥶"), UNKNOWN("Нет данных", "❓")
}

enum class TransportType(val title: String, val emoji: String) {
    WALK("Пеший", "🚶"), BIKE("Вело", "🚲"), EBIKE("Электро", "⚡"), CAR("Авто", "🚗")
}

data class Region(val id: String, val name: String, val lat: Double, val lon: Double)

val availableRegionsList = listOf(
    Region("msk", "Москва", 55.7558, 37.6173),
    Region("spb", "Санкт-Петербург", 59.9386, 30.3141),
    Region("kzn", "Казань", 55.7887, 49.1221),
    Region("krd", "Краснодар", 45.0393, 38.9806),
    Region("ryz", "Рязань", 54.6269, 39.6916)
)

data class PressureAnalysis(
    val pressureMmHg: Int,
    val deltaMmHg: Int,
    val statusTitle: String,
    val wellBeingVerdict: String,
    val scoreImpact: Int,
    val isUnfavorableForOrders: Boolean
)

data class DetailedWeather(
    val weather: Weather,
    val temp: Int,
    val feelsLike: Int,
    val humidity: Int,
    val windSpeed: Double,
    val windGusts: Double = 0.0,
    val uvIndex: Double = 0.0,
    val pressureMmHg: Int = 748,
    val pressureDeltaMmHg: Int = 0,
    val isDay: Boolean = true,
    val hasIceRisk: Boolean = false,
    val upcomingAlert: String? = null,
    val sunriseStr: String = "07:00",
    val sunsetStr: String = "18:00",
    val darknessBannerTitle: String = "",
    val darknessHint: String = "",
    val source: String = "Open-Meteo",
    val utcOffsetSeconds: Int? = null
)

data class DailyForecast(val dateStr: String, val weather: Weather, val minTemp: Int, val maxTemp: Int)
data class HourlyScoreItem(val hour: Int, val timeStr: String, val temp: Int, val weather: Weather, val score: Int, val color: Color, val timeMs: Long = 0L)
data class WeatherData(val current: DetailedWeather, val forecast: List<DailyForecast>, val hourlyTimeline: List<HourlyScoreItem>, val fetchedAtMs: Long = System.currentTimeMillis())
data class NewsItem(val id: Int, val title: String, val source: String, val url: String, val isExtreme: Boolean, val ageText: String = "", val isFresh: Boolean = true)
data class ScoreFactor(val title: String, val impact: Int)
data class ScoreResult(val totalScore: Int, val verdict: String, val factors: List<ScoreFactor>, val color: Color)

/** Смена. Хранится в Room; id = время создания в мс (по нему определяется месяц/неделя). */
@Entity(tableName = "shifts")
data class ShiftEntry(
    @PrimaryKey val id: Long,
    val dateStr: String,
    val hours: Double,
    val gross: Int,
    val orders: Int,
    val km: Double = 0.0,
    val expFuel: Int = 0,    // топливо / зарядка АКБ
    val expRepair: Int = 0,  // ремонт и ТО
    val expOther: Int = 0,   // еда, налог, прочее
    @ColumnInfo(defaultValue = "") val weather: String = "", // имя Weather на момент сохранения смены ("" — неизвестно)
    @ColumnInfo(defaultValue = "0") val tips: Int = 0,      // чаевые (уже входят в gross)
    @ColumnInfo(defaultValue = "0") val tax: Int = 0,       // налог самозанятого, посчитанный при сохранении
    @ColumnInfo(defaultValue = "") val service: String = "" // агрегатор/сервис ("" — не указан)
) {
    val expenses: Int get() = expFuel + expRepair + expOther
    /** Чистыми: грязными минус расходы и налог (может быть < 0 при крупном ремонте или внесменовом расходе). */
    val net: Int get() = gross - expenses - tax
    val isExpenseOnly: Boolean get() = hours <= 0.0 && gross == 0 && expenses > 0
    val perHour: Int get() = if (hours > 0) (net / hours).roundToInt() else 0
    val avgCheck: Int get() = if (orders > 0) (gross / orders) else 0
    val perKm: Double get() = if (km > 0) net / km else 0.0
}

@Entity(tableName = "intercoms")
data class IntercomNote(
    @PrimaryKey val id: Long,
    val address: String,
    val code: String,
    val comment: String,
    val category: String = "Домофон / ЖК",
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false
)

enum class AppThemeMode { SYSTEM, LIGHT, DARK_AMOLED }
enum class NotificationTone { NORMAL, MAT }
enum class Screen { MAIN, SETTINGS, DEVELOPER }

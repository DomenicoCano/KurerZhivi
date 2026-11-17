package com.example.myapplication

import android.content.Context

// ============================================================================
// ВСТРОЕННЫЙ СПРАВОЧНИК ГОРОДОВ РОССИИ (assets/ru_cities.tsv) — поиск без интернета.
// Нужен при «белых списках»: онлайн-геокодер Open-Meteo там заблокирован.
// Данные: hflabs/city (https://github.com/hflabs/city), лицензия CC BY-SA 4.0.
// ============================================================================

data class RuCity(val kladr: String, val name: String, val region: String, val lat: Double, val lon: Double, val population: Int) {
    /** Регион для выбора: id стабилен (по КЛАДР) — к нему привязан кэш прогноза. */
    fun toRegion() = Region("ru_$kladr", if (region.isBlank()) name else "$name ($region)", lat, lon)
}

private fun normalize(s: String) = s.lowercase().replace('ё', 'е').trim()

fun parseRuCities(tsv: String): List<RuCity> = tsv.lineSequence()
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 6) null else try {
            RuCity(f[0], f[1], f[2], f[3].toDouble(), f[4].toDouble(), f[5].toInt())
        } catch (_: NumberFormatException) { null }
    }.toList()

/**
 * Поиск: сначала города, чьё название начинается с запроса, затем содержащие его (в т.ч. по региону);
 * внутри групп — крупные города первыми. «ё» и «е» не различаются.
 */
/** Разговорные названия → официальные. */
private val ALIASES = mapOf("питер" to "санкт-петербург", "спб" to "санкт-петербург", "мск" to "москва", "екб" to "екатеринбург", "нск" to "новосибирск")

fun searchRuCities(cities: List<RuCity>, query: String, limit: Int = 8): List<RuCity> {
    val q = normalize(query).let { raw -> ALIASES.entries.firstOrNull { it.key.startsWith(raw) && raw.length >= 3 || it.key == raw }?.value ?: raw }
    if (q.length < 2) return emptyList()
    val prefix = cities.filter { normalize(it.name).startsWith(q) }
    val contains = cities.filter { c -> c !in prefix && (normalize(c.name).contains(q) || normalize(c.region).split(' ', '-').any { w -> w.startsWith(q) }) }
    return (prefix.sortedByDescending { it.population } + contains.sortedByDescending { it.population }).take(limit)
}

object RuCities {
    @Volatile private var cache: List<RuCity>? = null

    fun all(context: Context): List<RuCity> = cache ?: synchronized(this) {
        cache ?: try {
            parseRuCities(context.assets.open("ru_cities.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) { emptyList() }.also { cache = it }
    }

    fun search(context: Context, query: String): List<Region> = searchRuCities(all(context), query).map { it.toRegion() }
}

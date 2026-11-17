package com.example.myapplication

import androidx.compose.ui.graphics.Color
import java.util.Calendar
import kotlin.math.roundToInt

// ============================================================================
// РАСЧЕТ БАЛЛА СПРОСА, ТРАНСПОРТА, ДАВЛЕНИЯ, АКБ И ЭКИПИРОВКИ
// ============================================================================

fun calculateScore100(
    weather: DetailedWeather,
    news: List<NewsItem>,
    cal: Calendar = Calendar.getInstance(),
    transport: TransportType = TransportType.EBIKE
): ScoreResult {
    var score = 40
    val factors = mutableListOf<ScoreFactor>()
    val hour = cal.get(Calendar.HOUR_OF_DAY)

    if (weather.weather != Weather.UNKNOWN) {
        val (wScore, wText) = when (weather.weather) {
            Weather.SUN -> if (!weather.isDay) 5 to "Ясная ночь. Спрос стабильный." else -5 to "Ясный день. Люди чаще гуляют, заказов может быть меньше (оценка)."
            Weather.CLOUDY -> 5 to "Облачно. Комфортная погода, стабильный спрос."
            Weather.LIGHT_RAIN -> 12 to "Морось / легкий дождь. Спрос выше обычного."
            Weather.RAIN -> 20 to "Дождь! Люди сидят дома и заказывают доставку."
            Weather.STORM -> 28 to "Сильный ливень / Гроза! Взрывной спрос и горящие кэфы."
            Weather.SLEET -> 25 to "Мокрый снег с дождем. Мало курьеров, высокий спрос."
            Weather.SNOW -> 25 to "Снегопад. Экстремальный спрос и повышенные бонусы."
            Weather.FROST -> 30 to "Сильный мороз (${weather.temp}°C). Огромный спрос на горячую еду."
            else -> 0 to ""
        }
        score += wScore
        factors.add(ScoreFactor(wText, wScore))
        if (weather.hasIceRisk) { score += 15; factors.add(ScoreFactor("Гололед / Слякоть (${weather.temp}°C). Трафик замедлен, кэфы выше!", 15)) }
        if (weather.windGusts >= 14.0 || weather.windSpeed >= 10.0) { score += 10; factors.add(ScoreFactor("Штормовые порывы ветра (до ${weather.windGusts.roundToInt()} м/с).", 10)) }
        if (weather.upcomingAlert != null) { score += 10; factors.add(ScoreFactor(weather.upcomingAlert, 10)) }

        // Аналитика атмосферного давления
        val pressureInfo = analyzeAtmosphericPressure(weather.pressureMmHg, weather.pressureDeltaMmHg)
        score += pressureInfo.scoreImpact
        factors.add(ScoreFactor("${pressureInfo.statusTitle}: ${pressureInfo.wellBeingVerdict}", pressureInfo.scoreImpact))
    }

    // Специфика выбранного профиля транспорта
    when (transport) {
        TransportType.CAR -> {
            if (hour in 8..10 || hour in 17..20) {
                score -= 8
                factors.add(ScoreFactor("🚗 Профиль Авто: Час пик (пробки 7–9 баллов и сложная парковка у ЖК снижают темп заказов).", -8))
            }
            if (weather.temp <= -5) {
                score -= 4
                factors.add(ScoreFactor("🚗 Профиль Авто: Повышенный расход топлива на прогревы при ${weather.temp}°C.", -4))
            }
        }
        TransportType.EBIKE -> {
            val drop = if (weather.weather == Weather.UNKNOWN) 0 else calculateBatteryDropPercent(weather)
            if (drop >= 25) {
                factors.add(ScoreFactor("⚡ Профиль Электро: Просадка емкости АКБ ~${drop}%, но конкурентов на линии меньше!", 6))
                score += 6
            }
        }
        TransportType.WALK, TransportType.BIKE -> {
            if (weather.hasIceRisk) {
                factors.add(ScoreFactor("${transport.emoji} Профиль ${transport.title}: Скользкая плитка и разметка — двигайся осторожнее.", 0))
            }
        }
    }

    val isoDow = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1; Calendar.TUESDAY -> 2; Calendar.WEDNESDAY -> 3; Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5; Calendar.SATURDAY -> 6; Calendar.SUNDAY -> 7; else -> 1
    }
    when (hour) {
        in 0..6 -> { score -= 20; factors.add(ScoreFactor("Ночные часы (00:00–07:00). Активность минимальная.", -20)) }
        // Будни: людей поднимает будильник — утро начинается раньше. Выходные: встают позже (оценка, не измерение).
        7 -> if (isoDow <= 5) { score -= 8; factors.add(ScoreFactor("Будний день, 07:00–08:00: утренний спрос только просыпается.", -8)) }
        else { score -= 20; factors.add(ScoreFactor("Выходное утро (до 08:00): люди ещё спят, заказов почти нет.", -20)) }
        in 8..9 -> if (isoDow <= 5) { score += 5; factors.add(ScoreFactor("Утро (08:00–10:00). Завтраки и кофе в офисы.", 5)) }
        else { factors.add(ScoreFactor("Выходное утро (08:00–10:00): люди встают позже, спрос ещё не набрал ход.", 0)) }
        in 10..11 -> { score += 10; factors.add(ScoreFactor("Утренний скачок (10:00–12:00). Завтраки, бранчи и офисы.", 10)) }
        in 12..15 -> if (isoDow in 6..7) {
            val b = if (isoDow == 7) 20 else 15
            score += b; factors.add(ScoreFactor("Обед выходного дня. Спрос очень высокий!", b))
        } else { score += 8; factors.add(ScoreFactor("Обеденный перерыв в будни (12:00–16:00).", 8)) }
        in 16..19 -> if (hour in 18..19) { score += 25; factors.add(ScoreFactor("АБСОЛЮТНЫЙ ПИК (18:00–20:00). Максимум заказов!", 25)) }
        else { score += 20; factors.add(ScoreFactor("Вечерний прайм-тайм (16:00–18:00).", 20)) }
        in 20..21 -> { score += 15; factors.add(ScoreFactor("Поздний ужин (20:00–22:00). Высокая плотность.", 15)) }
        in 22..23 -> { score -= 10; factors.add(ScoreFactor("После 22:00. Постепенный спад активности.", -10)) }
    }

    when (isoDow) {
        1, 2, 3 -> { score -= 5; factors.add(ScoreFactor("Пн–Ср (~11.5% в неделю). Спокойные будни.", -5)) }
        4 -> { score += 5; factors.add(ScoreFactor("Четверг (~12.2%). Спрос начинает расти.", 5)) }
        5 -> { score += 15; factors.add(ScoreFactor("Пятница (~17.6%). Лидер по вечерним заказам!", 15)) }
        6 -> { score += 25; factors.add(ScoreFactor("Суббота (~19.8%). Самый прибыльный день недели!", 25)) }
        7 -> { score += 20; factors.add(ScoreFactor("Воскресенье (~16.4%). Отличный спрос весь день.", 20)) }
    }

    checkHolidayFactor(cal)?.let { score += it.impact; factors.add(ScoreFactor(it.description, it.impact)) }
    val payday = checkSmartPayday(cal)
    if (payday.isPayday) { score += 15; factors.add(ScoreFactor(payday.description, 15)) }
    else if (cal.get(Calendar.DAY_OF_MONTH) in 13..18 || cal.get(Calendar.DAY_OF_MONTH) in 28..31) {
        score -= 5; factors.add(ScoreFactor("Дни экономии перед выплатами.", -5))
    }

    val extNews = news.count { it.isExtreme && it.isFresh }
    if (extNews > 0) {
        val nScore = if (transport == TransportType.CAR) (extNews * 4).coerceAtMost(12) else (extNews * 8).coerceAtMost(24)
        val nTitle = if (transport == TransportType.CAR) {
            "Свежие ЧП/пробки в новостях ($extNews шт.): кэфы выше, но на Авто учитывай заторы на маршруте."
        } else {
            "Свежие ЧП/пробки в новостях ($extNews шт.) повышают спрос."
        }
        score += nScore; factors.add(ScoreFactor(nTitle, nScore))
    }

    val finalScore = score.coerceIn(0, 100)
    val color = when { finalScore >= 75 -> Color(0xFF00E676); finalScore >= 40 -> Color(0xFFFFD600); else -> Color(0xFFFF1744) }
    val verdict = when {
        finalScore >= 85 -> "Горячее время! Огромный спрос, выходи на линию немедленно!"
        finalScore >= 60 -> "Хороший спрос. Работы хватит всем, можно неплохо заработать."
        finalScore >= 35 -> "Средний спрос. Заказы есть, но возможны паузы."
        else -> "Тухляк. Заказов почти нет, лучше отдохни."
    }
    return ScoreResult(finalScore, verdict, factors, color)
}

fun calculateBatteryDropPercent(weather: DetailedWeather): Int {
    val t = weather.temp
    // Потеря емкости должна монотонно расти с понижением температуры
    var loss = when {
        t >= 20 -> 0
        t in 10..19 -> (20 - t) * 1
        t in 0..9 -> 10 + (10 - t) * 2
        t in -10..-1 -> 30 + (-t) * 2
        else -> 55
    }
    if (weather.windSpeed >= 6.0) loss += ((weather.windSpeed - 5.0) * 1.5).roundToInt().coerceAtMost(10)
    if (weather.windGusts >= 11.0) loss += 4
    if (weather.hasIceRisk || weather.weather == Weather.SNOW) loss += 6
    return loss.coerceIn(0, 65)
}

fun calculateCaloriesPerShift(weather: DetailedWeather, transport: TransportType, hours: Int = 8): Int {
    val basePerHour = when (transport) {
        TransportType.WALK -> 380
        TransportType.BIKE -> 490
        TransportType.EBIKE -> 210
        TransportType.CAR -> 130
    }
    val coldMultiplier = if (weather.temp < 5) 1.15 else 1.0
    val windMultiplier = if (weather.windSpeed >= 7.0 && transport != TransportType.CAR) 1.12 else 1.0
    return (basePerHour * hours * coldMultiplier * windMultiplier).roundToInt()
}

fun getGearRecommendations(weather: DetailedWeather, transport: TransportType): Triple<String, String, String> {
    if (weather.weather == Weather.UNKNOWN) return Triple("Нет данных", "Нет данных", "Нет данных")
    val clothes = mutableListOf<String>()
    val food = mutableListOf<String>()
    val tech = mutableListOf<String>()
    val t = weather.feelsLike
    val kcal8h = calculateCaloriesPerShift(weather, transport, 8)

    if (t > 25) {
        clothes.add("Легкая дышащая футболка, шорты, кепка от солнца (УФ ${weather.uvIndex}).")
        food.add("Пей минимум 2–3 литра воды с электролитами. За 8 ч. смены расход ~${kcal8h} ккал.")
    } else if (t in 15..25) {
        clothes.add("Ветровка или худи, удобные штаны, кроссовки.")
        food.add("Стандартный водный баланс. Расход за 8 ч. смены ~${kcal8h} ккал — возьми батончики или перекус.")
    } else if (t in 2..14) {
        clothes.add("Непродуваемая ветровка/куртка, тактическая флиска (средний слой), термобелье, перчатки и шапка.")
        food.add("Горячий чай в термосе. Плотный обед (гречка, мясо, суп). Расход энергии за 8 ч. смены: ~${kcal8h} ккал.")
    } else {
        clothes.add("Зимняя экипировка! Ветрозащитная куртка, теплая синтетическая флиска, термобелье, зимние ботинки под термоносок, балаклава и теплые перчатки/муфты на руль.")
        food.add("Обязательно термос с горячим сладким чаем. Расход за 8 ч. смены на холоде ~${kcal8h} ккал. Повербанк держи во внутреннем теплом кармане.")
    }

    if (weather.hasIceRisk) clothes.add("⚠️ ОСТОРОЖНО: Скользкая плитка, люки и дорожная разметка (${weather.temp}°C)! Выбирай обувь с цепким протектором.")
    if (weather.weather in listOf(Weather.LIGHT_RAIN, Weather.RAIN, Weather.STORM, Weather.SLEET)) {
        clothes.add("ОБЯЗАТЕЛЬНО: Дождевик, влагозащита обуви и гермочехол на смартфон.")
    }
    if (weather.windGusts >= 12.0) clothes.add("Сильные порывы ветра (до ${weather.windGusts.roundToInt()} м/с)! Надень плотную ветрозащиту.")
    if (weather.darknessHint.isNotEmpty()) clothes.add("🔦 " + weather.darknessHint)

    when (transport) {
        TransportType.EBIKE -> {
            val drop = calculateBatteryDropPercent(weather)
            tech.add("⚡ Калькулятор Li-ion АКБ: при ${weather.temp}°C и ветре ${weather.windSpeed} м/с реальная емкость падает на ~${drop}% (остаток эффективного запаса хода ~${100 - drop}%).")
            if (weather.temp < 8) tech.add("🔋 Утепли батарею неопреновым чехлом или фольгоизолоном. Храни АКБ в тепле до самого выхода и никогда не ставь ледяную батарею на зарядку сразу с мороза (дай прогреться 1.5–2 часа).")
            if (weather.weather in listOf(Weather.LIGHT_RAIN, Weather.RAIN, Weather.STORM, Weather.SLEET, Weather.SNOW)) {
                tech.add("🌧️ Защити отсек контроллера и дисплей от воды, смажь цепь влагостойкой смазкой.")
            } else {
                tech.add("🔧 Проверь давление в шинах и заряд фары/фонаря перед выездом.")
            }
        }
        TransportType.BIKE -> {
            tech.add("🚲 Расход калорий за 8 ч. смены: ~${kcal8h} ккал. Встречный ветер до ${weather.windGusts.roundToInt()} м/с увеличивает нагрузку на ноги на ~20%.")
            if (weather.hasIceRisk || weather.temp <= 2) {
                tech.add("⚠️ Внимание на мокрой плитке, рельсах и белой дорожной разметке! Снизь давление в покрышках на 0.3–0.5 бар для лучшего сцепления и смажь цепь.")
            } else {
                tech.add("🔧 Проверь тормозные колодки, смазку цепи и габаритные фонари.")
            }
        }
        TransportType.CAR -> {
            val extraFuel = if (weather.temp < 0) "+1.8–2.5 л/100 км на прогревах и печке" else "+0.8–1.2 л/100 км в городском цикле"
            tech.add("🚗 Учет для Авто: дополнительный расход топлива ($extraFuel). В часы пик закладывай время на пробки.")
            tech.add("🅿️ Парковка у ЖК: вечером дворы плотных ЖК забиты — паркуйся у въезда/шлагбаума и проходи 100 м пешком, чтобы не застрять в тупиковом проезде.")
            if (weather.hasIceRisk) tech.add("❄️ На въездах во дворы возможна ледяная колея — соблюдай дистанцию.")
        }
        TransportType.WALK -> {
            tech.add("🚶 Расход энергии пешком за 8 ч. смены: ~${kcal8h} ккал (~22–28 тыс. шагов). Держи телефон на длинном проводе от повербанка во внутреннем кармане.")
            if (weather.hasIceRisk || weather.weather in listOf(Weather.SLEET, Weather.SNOW, Weather.RAIN)) {
                tech.add("⚠️ Скользкая плитка у ТЦ, ступеньки подъездов и переходы! Не наступай на металлические люки и глянцевую разметку.")
            }
        }
    }

    return Triple(clothes.joinToString("\n\n"), food.joinToString("\n\n"), tech.joinToString("\n\n"))
}

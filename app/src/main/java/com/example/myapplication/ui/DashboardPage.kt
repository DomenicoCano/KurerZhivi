package com.example.myapplication

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import kotlinx.coroutines.*
import java.util.Calendar

// ============================================================================
// ВКЛАДКА 1: ГЛАВНАЯ (С ТАЙМЕРОМ ТЕМНОТЫ, ДАВЛЕНИЕМ И ГОРЯЧИМИ ЗОНАМИ)
// ============================================================================

@Composable
fun DashboardPage(viewModel: CourierViewModel, uiState: CourierUiState) {
    val weather = uiState.detailedWeather
    val hotZone = remember(uiState.lastUpdatedStr, weather, uiState.currentDateStr) {
        getHotZoneRecommendation(cityCalendar(weather), weather)
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(16.dp))
        if (uiState.isLoading && uiState.currentDateStr.isEmpty()) CircularProgressIndicator(modifier = Modifier.size(64.dp))
        else ScoreIndicator100(uiState.scoreResult)

        Spacer(modifier = Modifier.height(16.dp))
        Text(uiState.scoreResult.verdict, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))

        Spacer(modifier = Modifier.height(14.dp))
        PersonalRateBanner(viewModel, uiState)

        if (weather != null && (weather.upcomingAlert != null || weather.hasIceRisk)) {
            Spacer(modifier = Modifier.height(14.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFD600).copy(alpha = 0.18f)), shape = RoundedCornerShape(12.dp)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFFFD600))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(weather.upcomingAlert ?: "На дорогах гололедица / слякоть (${weather.temp}°C). Повышенный спрос!", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }

        // Плашка Таймера темноты (Закат / Рассвет) и видимости
        if (weather != null && weather.darknessBannerTitle.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WbTwilight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(weather.darknessBannerTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (weather.darknessHint.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(weather.darknessHint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
                    }
                }
            }
        }

        // Аналитика давления и влияния на желание людей заказывать курьера
        if (weather != null && weather.weather != Weather.UNKNOWN) {
            val pressAnalysis = remember(weather) { analyzeAtmosphericPressure(weather.pressureMmHg, weather.pressureDeltaMmHg) }
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (pressAnalysis.isUnfavorableForOrders) Color(0xFFFF1744).copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = if (pressAnalysis.isUnfavorableForOrders) Color(0xFFFF5252) else Color(0xFF00E676)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Барометр спроса (оценка): ${pressAnalysis.statusTitle}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        pressAnalysis.wellBeingVerdict,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // Горячие зоны по времени суток
        Spacer(modifier = Modifier.height(12.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Куда смещаться: ${hotZone.first}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(hotZone.second, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        DemandRhythmCard(weather, "${uiState.lastUpdatedStr}_${uiState.currentDateStr}", uiState.shifts)

        if (uiState.hourlyTimeline.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            HourlyTimelineCard(uiState.hourlyTimeline)
        }

        Spacer(modifier = Modifier.height(16.dp))
        ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Подробный разбор факторов:", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 4.dp))
                Text("Баллы — оценка по общим данным и допущениям, а не гарантия заработка. Ваши реальные цифры — на вкладке «Статистика».", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                uiState.scoreResult.factors.forEach { factor ->
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
                        val icon = if (factor.impact > 0) Icons.Rounded.TrendingUp else if (factor.impact < 0) Icons.Rounded.TrendingDown else Icons.Rounded.Remove
                        val color = if (factor.impact > 0) Color(0xFF00E676) else if (factor.impact < 0) Color(0xFFFF1744) else Color.Gray
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(end = 8.dp))
                        Text(factor.title, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
                        Text(if (factor.impact > 0) "+${factor.impact}" else "${factor.impact}", fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun HourlyTimelineCard(timeline: List<HourlyScoreItem>) {
    val bestHour = remember(timeline) { timeline.maxByOrNull { it.score } }
    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Таймлайн смены (12 ч.)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                if (bestHour != null) Text("Пик в ${bestHour.timeStr} (${bestHour.score} б.)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = bestHour.color)
            }
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(timeline, key = { it.timeMs }) { item ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(item.timeStr, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.weather.emoji, fontSize = 18.sp)
                        Text("${item.temp}°", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(modifier = Modifier.width(26.dp).height(44.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.BottomCenter) {
                            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight((item.score / 100f).coerceIn(0.1f, 1f)).clip(RoundedCornerShape(6.dp)).background(item.color))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${item.score}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = item.color)
                    }
                }
            }
        }
    }
}

@Composable
fun ScoreIndicator100(score: ScoreResult) {
    val animatedProgress by animateFloatAsState(targetValue = score.totalScore / 100f, animationSpec = tween(1500, easing = FastOutSlowInEasing), label = "")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
        CircularProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxSize(), color = score.color, strokeWidth = 16.dp, trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = StrokeCap.Round)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "${score.totalScore}", fontSize = 64.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
            Text(text = "из 100 баллов", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PersonalRateBanner(viewModel: CourierViewModel, uiState: CourierUiState) {
    val weather = uiState.detailedWeather
    val est = remember(uiState.shifts, weather, uiState.scoreResult.totalScore, uiState.currentDateStr) {
        estimatePersonalPerHour(uiState.shifts, weather, uiState.scoreResult.totalScore, cityCalendar(weather))
    }
    if (est != null) {
        val sign = if (est.deltaPct >= 0) "+" else ""
        val deltaColor = when {
            est.deltaPct >= 5 -> Color(0xFF00E676)
            est.deltaPct <= -5 -> Color(0xFFFF5252)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "💰 Ваш ориентир сейчас: ~${est.estimatedPerHour} ₽/ч",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF00E676)
                    )
                    Text(
                        "$sign${est.deltaPct}%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = deltaColor
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Диапазон ${est.rangeLow}–${est.rangeHigh} ₽/ч чистыми • ${est.basisText}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { viewModel.selectTab(1) },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Payments, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "💡 Записывай смены во вкладке «Смена» — здесь появится твой личный прогноз ₽/ч",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
